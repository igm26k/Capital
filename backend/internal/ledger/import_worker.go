package ledger

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
)

// ProcessImports commits one row per financial command. Job/row/source state commits
// in the same PostgreSQL transaction as entries, journal and receipt.
func ProcessImports(ctx context.Context, auth *identity.Service, limit int) (int, error) {
	count := 0
	for count < limit {
		var workspace, id, owner, session, generation, action string
		var number int
		var body []byte
		err := auth.DB.QueryRow(ctx, `SELECT i.workspace_id::text,i.id::text,i.actor_user_id::text,i.session_id::text,i.generation_id::text,r.row_number,r.action_id::text,r.payload
   FROM csv_imports i JOIN csv_rows r ON r.workspace_id=i.workspace_id AND r.import_id=i.id
   WHERE i.state='queued' AND r.state='queued' ORDER BY i.workspace_id,i.id,r.row_number LIMIT 1`).Scan(&workspace, &id, &owner, &session, &generation, &number, &action, &body)
		if errors.Is(err, pgx.ErrNoRows) {
			return count, nil
		}
		if err != nil {
			return count, err
		}
		var input BasicInput
		if err := json.Unmarshal(body, &input); err != nil {
			return count, err
		}
		request := commands.Request{RequestID: newID(), WorkspaceID: workspace, ActionID: action, GenerationID: generation, Method: "POST", Path: fmt.Sprintf("/api/v1/workspaces/%s/imports/%s/rows/%d", workspace, id, number), Body: body, Authorize: func(p identity.Principal) error {
			if p.Profile.ID != owner || p.Session.ID != session {
				return &identity.Error{Status: 401, Code: "unauthenticated"}
			}
			return nil
		}}
		result, err := (&commands.Runner{Identity: auth}).ExecuteForWorker(ctx, request, session, func(ctx context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
			var current string
			if err := tx.QueryRow(ctx, `SELECT state FROM csv_rows WHERE workspace_id=$1 AND import_id=$2 AND row_number=$3 FOR UPDATE`, workspace, id, number).Scan(&current); err != nil {
				return commands.Mutation{}, err
			}
			if current != "queued" {
				return commands.Mutation{}, &identity.Error{Status: 409, Code: "import_row_changed"}
			}
			mutation, err := CreateBasic(workspace, input)(ctx, tx, p)
			if err != nil {
				return mutation, err
			}
			if _, err := tx.Exec(ctx, `UPDATE csv_rows SET state='applied',resolved_id=$4 WHERE workspace_id=$1 AND import_id=$2 AND row_number=$3`, workspace, id, number, input.ID); err != nil {
				return commands.Mutation{}, err
			}
			if _, err := tx.Exec(ctx, `UPDATE csv_sources SET transaction_id=$4 WHERE workspace_id=$1 AND import_id=$2 AND row_number=$3`, workspace, id, number, input.ID); err != nil {
				return commands.Mutation{}, err
			}
			if _, err := tx.Exec(ctx, `UPDATE csv_imports SET state=CASE WHEN EXISTS(SELECT 1 FROM csv_rows WHERE workspace_id=$1 AND import_id=$2 AND state='queued') THEN 'queued' ELSE 'committed' END,version=version+1 WHERE workspace_id=$1 AND id=$2`, workspace, id); err != nil {
				return commands.Mutation{}, err
			}
			return mutation, nil
		})
		code := ""
		if err != nil {
			var failure *identity.Error
			if !errors.As(err, &failure) {
				return count, err
			}
			if failure.Status == 503 {
				return count, err
			}
			code = failure.Code
		}
		if result.Status >= 400 {
			var failure struct {
				Code string `json:"code"`
			}
			json.Unmarshal(result.Body, &failure)
			code = failure.Code
		}
		if code != "" {
			if _, err := auth.DB.Exec(ctx, `UPDATE csv_imports SET state='failed',version=version+1,error_code=$3 WHERE workspace_id=$1 AND id=$2 AND state='queued'`, workspace, id, code); err != nil {
				return count, err
			}
		}
		count++
	}
	return count, nil
}
