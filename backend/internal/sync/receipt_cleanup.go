package sync

import (
	"context"
	"errors"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"time"
)

// CompactReceipts is trusted worker maintenance, never an unauthenticated HTTP command.
// It locks one available workspace head and removes only expired full responses.
func CompactReceipts(ctx context.Context, database *pgxpool.Pool, limit int) (int64, error) {
	if limit < 1 || limit > 1000 {
		return 0, errors.New("invalid receipt cleanup limit")
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	tx, e := database.Begin(ctx)
	if e != nil {
		return 0, errors.New("receipt cleanup unavailable")
	}
	defer func() {
		cleanup, stop := context.WithTimeout(context.Background(), time.Second)
		defer stop()
		_ = tx.Rollback(cleanup)
	}()
	var workspace string
	e = tx.QueryRow(ctx, `SELECT h.workspace_id::text FROM sync_heads h WHERE EXISTS(SELECT 1 FROM actions a WHERE a.workspace_id=h.workspace_id AND a.response_body IS NOT NULL AND a.response_expires_at<=clock_timestamp()) ORDER BY h.workspace_id LIMIT 1 FOR UPDATE OF h SKIP LOCKED`).Scan(&workspace)
	if errors.Is(e, pgx.ErrNoRows) {
		return 0, nil
	}
	if e != nil {
		return 0, errors.New("receipt cleanup unavailable")
	}
	result, e := tx.Exec(ctx, `WITH expired AS (SELECT actor_user_id,action_id FROM actions WHERE workspace_id=$1 AND response_body IS NOT NULL AND response_expires_at<=clock_timestamp() ORDER BY completed_at,actor_user_id,action_id LIMIT $2) UPDATE actions a SET response_body=NULL FROM expired e WHERE a.workspace_id=$1 AND a.actor_user_id=e.actor_user_id AND a.action_id=e.action_id`, workspace, limit)
	if e != nil {
		return 0, errors.New("receipt cleanup failed")
	}
	if e = tx.Commit(ctx); e != nil {
		return 0, errors.New("receipt cleanup commit failed")
	}
	return result.RowsAffected(), nil
}
