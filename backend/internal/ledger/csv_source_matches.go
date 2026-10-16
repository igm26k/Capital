package ledger

import (
	"accounting/backend/internal/identity"
	"context"
	"encoding/json"
	"errors"
	"github.com/jackc/pgx/v5"
)

func csvCandidates(ctx context.Context, tx pgx.Tx, workspace, account string, row ImportRow) ([]string, error) {
	rows, err := tx.Query(ctx, `SELECT t.id::text FROM transactions t JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id)
 WHERE t.workspace_id=$1 AND e.account_id=$2 AND t.deleted_at IS NULL AND t.kind IN ('expense','income','transfer','refund') AND e.amount_minor=$3::bigint
 AND (($5<>'' AND t.occurred_at=$4::timestamptz AND lower(t.payee)=lower($5)) OR
 (t.occurred_at BETWEEN $4::timestamptz-interval '72 hours' AND $4::timestamptz+interval '72 hours' AND EXISTS(SELECT 1 FROM transaction_sources s WHERE s.workspace_id=t.workspace_id AND s.transaction_id=t.id))) ORDER BY t.id LIMIT 20`, workspace, account, *row.Amount, *row.Occurred, row.Payee)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// Refresh a stale preview before accepting a create that did not show a new bank
// candidate. The next confirmation is explicit; known candidates may still be
// independent equal purchases. No financial rows are queued by this refresh.
func refreshBankPreview(ctx context.Context, tx pgx.Tx, workspace string, preview ImportPreview, input ImportConfirm) (bool, error) {
	byNumber := map[int]*ImportRow{}
	for i := range preview.Rows {
		byNumber[preview.Rows[i].Number] = &preview.Rows[i]
	}
	changed := false
	for _, decision := range input.Decisions {
		row := byNumber[decision.Number]
		if row == nil {
			return false, invalid()
		}
		if decision.Action != "create" || row.SourceID != nil || row.Amount == nil || row.Occurred == nil || row.Status == "invalid" {
			continue
		}
		var exactTarget *string
		exactErr := tx.QueryRow(ctx, `SELECT s.transaction_id::text FROM csv_rows r JOIN csv_sources s ON s.workspace_id=r.workspace_id AND s.account_id=$3 AND s.fingerprint=r.fingerprint WHERE r.workspace_id=$1 AND r.import_id=$2 AND r.row_number=$4`, workspace, preview.ID, preview.Account, decision.Number).Scan(&exactTarget)
		if exactErr == nil {
			if exactTarget == nil {
				return false, &identity.Error{Status: 409, Code: "import_source_pending"}
			}
			continue
		}
		if !errors.Is(exactErr, pgx.ErrNoRows) {
			return false, exactErr
		}
		var queued bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM source_events s WHERE s.workspace_id=$1 AND s.account_id=$2 AND s.state='queued' AND s.job->>'decision'='create_pending' AND (s.payload->>'amount_minor')::bigint*CASE WHEN s.payload->>'kind'='expense' THEN -1 ELSE 1 END=$3::bigint AND (s.payload->>'observed_at')::timestamptz BETWEEN $4::timestamptz-interval '72 hours' AND $4::timestamptz+interval '72 hours')`, workspace, preview.Account, *row.Amount, *row.Occurred).Scan(&queued); err != nil {
			return false, err
		}
		if queued {
			return false, &identity.Error{Status: 409, Code: "import_source_pending"}
		}
		candidates, err := csvCandidates(ctx, tx, workspace, preview.Account, *row)
		if err != nil {
			return false, err
		}
		known := map[string]bool{}
		for _, id := range row.Candidates {
			known[id] = true
		}
		added := false
		for _, id := range candidates {
			if !known[id] {
				added = true
			}
		}
		if added {
			row.Candidates = candidates
			row.Status = "ambiguous"
			changed = true
		}
	}
	if !changed {
		return false, nil
	}
	raw, err := json.Marshal(preview)
	if err != nil {
		return false, err
	}
	_, err = tx.Exec(ctx, `UPDATE csv_imports SET preview=$3,version=version+1,error_code='version_conflict' WHERE workspace_id=$1 AND id=$2`, workspace, preview.ID, raw)
	return true, err
}
