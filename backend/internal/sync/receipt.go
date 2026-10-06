package sync

import (
	"accounting/backend/internal/identity"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"github.com/jackc/pgx/v5"
	"strconv"
	"time"
)

func replay(ctx context.Context, tx pgx.Tx, r Request, actor string, hash []byte, result *Result) (bool, error) {
	var storedHash, body []byte
	var status int
	var expires time.Time
	e := tx.QueryRow(ctx, `SELECT canonical_request_hash,original_http_status,response_body,response_expires_at FROM actions WHERE workspace_id=$1 AND actor_user_id=$2 AND action_id=$3`, r.WorkspaceID, actor, r.ActionID).Scan(&storedHash, &status, &body, &expires)
	if errors.Is(e, pgx.ErrNoRows) {
		return false, nil
	}
	if e != nil {
		return false, e
	}
	if subtle.ConstantTimeCompare(storedHash, hash) != 1 {
		return true, problem(409, "idempotency_conflict")
	}
	var now time.Time
	if e = tx.QueryRow(ctx, "SELECT clock_timestamp()").Scan(&now); e != nil {
		return true, e
	}
	if len(body) == 0 || !now.Before(expires) {
		return true, problem(409, "action_result_expired")
	}
	*result = Result{Status: status, Body: body, Replayed: true}
	return true, nil
}
func storeReceipt(ctx context.Context, tx pgx.Tx, r Request, actor string, hash []byte, state string, status int, body, refs []byte, completed time.Time, sequence int64, generation *string) error {
	var group any
	if generation != nil {
		group = sequence
	}
	_, e := tx.Exec(ctx, `INSERT INTO actions(workspace_id,actor_user_id,action_id,canonical_request_hash,state,original_http_status,response_body,completed_at,response_expires_at,group_sequence,generation_id,entity_refs) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)`, r.WorkspaceID, actor, r.ActionID, hash, state, status, body, completed, completed.Add(30*24*time.Hour), group, generation, refs)
	return e
}
func saveRejection(ctx context.Context, tx pgx.Tx, r Request, actor string, hash []byte, rejection *Rejection, result *Result) error {
	allowed := map[string]bool{"version_conflict": true, "idempotency_conflict": true, "dependent_transactions": true, "account_archived": true, "amount_out_of_range": true, "no_change": true, "validation_error": true, "sync_group_too_large": true, "sync_generation_conflict": true}
	if !allowed[rejection.Code] {
		return problem(500, "internal_error")
	}
	fields, versions := rejection.FieldErrors, rejection.CurrentVersions
	if fields == nil {
		fields = []any{}
	}
	if versions == nil {
		versions = []any{}
	}
	body, e := json.Marshal(map[string]any{"request_id": r.RequestID, "code": rejection.Code, "message": rejection.Message, "field_errors": fields, "current_versions": versions, "retry_after_seconds": nil})
	if e != nil {
		return problem(500, "internal_error")
	}
	var completed time.Time
	if e = tx.QueryRow(ctx, "SELECT clock_timestamp()").Scan(&completed); e != nil {
		return e
	}
	if e = storeReceipt(ctx, tx, r, actor, hash, "rejected", rejection.Status, body, []byte("[]"), completed, 0, nil); e != nil {
		return e
	}
	*result = Result{Status: rejection.Status, Body: body}
	return nil
}

type Outcome struct {
	ActionID    string          `json:"action_id"`
	WorkspaceID string          `json:"workspace_id"`
	State       string          `json:"state"`
	Completed   time.Time       `json:"completed_at"`
	Expires     time.Time       `json:"response_expires_at"`
	Status      int             `json:"original_http_status"`
	Sequence    *string         `json:"group_sequence"`
	Entities    json.RawMessage `json:"entity_refs"`
	Body        json.RawMessage `json:"response_body"`
	Generation  *string         `json:"generation_id"`
}

func (r *Runner) Outcome(ctx context.Context, credential, workspace, action string) (Outcome, error) {
	if !uuidPattern.MatchString(workspace) || !uuidPattern.MatchString(action) {
		return Outcome{}, problem(400, "bad_request")
	}
	var outcome Outcome
	err := r.Identity.WithSession(ctx, credential, false, func(tx pgx.Tx, p identity.Principal) error {
		if e := identity.RequireMembership(ctx, tx, p.Profile.ID, workspace); e != nil {
			return e
		}
		var sequence *int64
		e := tx.QueryRow(ctx, `SELECT action_id::text,workspace_id::text,state,completed_at,response_expires_at,original_http_status,group_sequence,entity_refs,CASE WHEN response_expires_at>clock_timestamp() THEN response_body ELSE NULL END,generation_id::text FROM actions WHERE workspace_id=$1 AND actor_user_id=$2 AND action_id=$3`, workspace, p.Profile.ID, action).Scan(&outcome.ActionID, &outcome.WorkspaceID, &outcome.State, &outcome.Completed, &outcome.Expires, &outcome.Status, &sequence, &outcome.Entities, &outcome.Body, &outcome.Generation)
		if errors.Is(e, pgx.ErrNoRows) {
			return problem(404, "not_found")
		}
		if e != nil {
			return e
		}
		if sequence != nil {
			s := strconv.FormatInt(*sequence, 10)
			outcome.Sequence = &s
		}
		outcome.Completed = outcome.Completed.UTC()
		outcome.Expires = outcome.Expires.UTC()
		return nil
	})
	if err != nil {
		var domain *identity.Error
		if errors.As(err, &domain) {
			return Outcome{}, domain
		}
		return Outcome{}, problem(503, "service_unavailable")
	}
	return outcome, nil
}
