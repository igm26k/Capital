package ledger

import (
	"accounting/backend/internal/identity"
	"context"
	"encoding/json"
	"errors"
	"github.com/jackc/pgx/v5"
	"time"
)

// Source pages use immutable receipt time and ID, scoped to the private owner.
// The opaque anchor cannot expose another actor's observations.
func SourceEventPage(ctx context.Context, tx pgx.Tx, workspace, owner, before string, limit int) ([]SourceEvent, *string, error) {
	if limit < 1 || limit > 100 {
		return nil, nil, invalid()
	}
	var at time.Time
	if before != "" {
		err := tx.QueryRow(ctx, `SELECT received_at FROM source_events WHERE workspace_id=$1 AND actor_user_id=$2 AND id=$3`, workspace, owner, before).Scan(&at)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil, &identity.Error{Status: 404, Code: "not_found"}
		}
		if err != nil {
			return nil, nil, err
		}
	}
	rows, err := tx.Query(ctx, `SELECT payload,workspace_id::text,version::text,state,received_at,(SELECT transaction_id::text FROM transaction_sources s WHERE s.workspace_id=source_events.workspace_id AND s.event_id=source_events.id),error_code FROM source_events WHERE workspace_id=$1 AND actor_user_id=$2 AND ($3::uuid IS NULL OR (received_at,id)<($4::timestamptz,$3::uuid)) ORDER BY received_at DESC,id DESC LIMIT $5`, workspace, owner, nullableSourceAnchor(before), at, limit+1)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	items := []SourceEvent{}
	for rows.Next() {
		var value SourceEvent
		var raw []byte
		if err = rows.Scan(&raw, &value.Workspace, &value.Version, &value.State, &value.ReceivedAt, &value.TransactionID, &value.ErrorCode); err != nil {
			return nil, nil, err
		}
		if err = json.Unmarshal(raw, &value.SourceEventInput); err != nil {
			return nil, nil, err
		}
		items = append(items, value)
	}
	if err = rows.Err(); err != nil {
		return nil, nil, err
	}
	var next *string
	if len(items) > limit {
		items = items[:limit]
		id := items[len(items)-1].ID
		next = &id
	}
	return items, next, nil
}
func nullableSourceAnchor(id string) any {
	if id == "" {
		return nil
	}
	return id
}
