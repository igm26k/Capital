package ledger

import (
	"accounting/backend/internal/identity"
	"context"
	"github.com/jackc/pgx/v5"
)

// Relinking is explicit metadata repair after a financial tombstone. It never
// deletes a live operation, restores a deleted one, or guesses a target.
func relinkDeletedSource(ctx context.Context, tx pgx.Tx, workspace string, event SourceEvent, target, version string, p identity.Principal) error {
	var count int
	if err := tx.QueryRow(ctx, `SELECT jsonb_array_length(job_history) FROM source_events WHERE workspace_id=$1 AND id=$2`, workspace, event.ID).Scan(&count); err != nil {
		return err
	}
	if count >= 100 {
		return &identity.Error{Status: 422, Code: "source_event_limit"}
	}
	tag, err := tx.Exec(ctx, `UPDATE transaction_sources SET transaction_id=$3 WHERE workspace_id=$1 AND event_id=$2 AND transaction_id=$4 AND EXISTS(SELECT 1 FROM transactions t WHERE t.workspace_id=$1 AND t.id=$4 AND t.deleted_at IS NOT NULL)`, workspace, event.ID, target, *event.TransactionID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() != 1 {
		return &identity.Error{Status: 409, Code: "source_event_not_ready"}
	}
	_, err = tx.Exec(ctx, `UPDATE source_events SET job_history=job_history||jsonb_build_array(jsonb_build_object('link',jsonb_build_object('from_transaction_id',$3::text,'to_transaction_id',$4::text,'expected_target_version',$5::text,'session_id',$6::text,'recorded_at',clock_timestamp()))) WHERE workspace_id=$1 AND id=$2`, workspace, event.ID, *event.TransactionID, target, version, p.Session.ID)
	return err
}
