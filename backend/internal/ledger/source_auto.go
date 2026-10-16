package ledger

import (
	"accounting/backend/internal/identity"
	"context"
	"github.com/jackc/pgx/v5"
	"strings"
)

// Auto intake is an explicit opt-in API capability. It never changes an existing
// source decision, guesses a merge, or confirms a bank charge as posted.
func CreateAutomaticSourceEvent(workspace, generation string, input SourceEventInput) importSetting {
	return func(ctx context.Context, tx pgx.Tx, p identity.Principal) (any, int, error) {
		value, status, err := CreateSourceEvent(workspace, generation, input)(ctx, tx, p)
		if err != nil || status != 201 {
			return value, status, err
		}
		if input.NotificationKeyHash == nil || strings.TrimSpace(input.Merchant) == "" {
			return value, status, nil
		}
		var possible bool
		err = tx.QueryRow(ctx, `SELECT
   EXISTS(SELECT 1 FROM source_events old WHERE old.workspace_id=$1 AND old.id<>$6 AND old.payload->>'package_name'=$8 AND old.payload->>'notification_key_hash'=$9::text)
   OR EXISTS(SELECT 1 FROM transactions t JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id)
     WHERE t.workspace_id=$1 AND e.account_id=$2 AND e.amount_minor=$3::bigint*CASE WHEN $4='expense' THEN -1 ELSE 1 END
       AND t.occurred_at BETWEEN $5::timestamptz-interval '72 hours' AND $5::timestamptz+interval '72 hours')
   OR EXISTS(SELECT 1 FROM csv_imports c WHERE c.workspace_id=$1 AND c.account_id=$2 AND c.state='queued')
   OR EXISTS(SELECT 1 FROM source_events s WHERE s.workspace_id=$1 AND s.id<>$6 AND s.account_id=$2
       AND s.payload->>'amount_minor'=$3::text AND s.payload->>'kind'=$4 AND s.payload->>'currency'=$7
       AND (s.payload->>'observed_at')::timestamptz BETWEEN $5::timestamptz-interval '72 hours' AND $5::timestamptz+interval '72 hours')`, workspace, input.AccountID, input.Amount, input.Kind, input.ObservedAt, input.ID, input.Currency, input.PackageName, *input.NotificationKeyHash).Scan(&possible)
		if err != nil {
			return nil, 0, err
		}
		if possible {
			return value, status, nil
		}
		result, _, err := ConfirmSourceEvent(workspace, input.ID, generation, SourceDecision{Version: "1", Decision: "create_pending"})(ctx, tx, p)
		return result, status, err
	}
}
