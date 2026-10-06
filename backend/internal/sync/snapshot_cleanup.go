package sync

import (
	"context"
	"errors"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"math"
	"time"
)

type SyncCleanupResult struct {
	Snapshots int64
	Groups    int64
}

// CleanupSync locks only a head, never user/session/membership locks. Pages and
// materialization hold head SHARE, so payload removal cannot race their reads.
func CleanupSync(ctx context.Context, db *pgxpool.Pool, limit int) (SyncCleanupResult, error) {
	var result SyncCleanupResult
	if limit < 1 || limit > 1000 {
		return result, errors.New("invalid sync cleanup limit")
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	tx, e := db.Begin(ctx)
	if e != nil {
		return result, errors.New("sync cleanup unavailable")
	}
	defer func() {
		clean, stop := context.WithTimeout(context.Background(), time.Second)
		defer stop()
		_ = tx.Rollback(clean)
	}()
	var workspace, generation string
	var minimum, head int64
	e = tx.QueryRow(ctx, `SELECT h.workspace_id::text,h.generation_id::text,h.min_available_sequence,h.last_sequence FROM sync_heads h WHERE EXISTS(SELECT 1 FROM sync_snapshots s WHERE s.workspace_id=h.workspace_id AND s.payload_cleared_at IS NULL AND (s.expires_at<=clock_timestamp() OR s.generation_id<>h.generation_id OR s.cursor_key_id IS NULL)) OR EXISTS(SELECT 1 FROM sync_groups g WHERE g.workspace_id=h.workspace_id AND g.generation_id=h.generation_id AND g.sequence=h.min_available_sequence AND g.sequence<9223372036854775807 AND g.created_at<=clock_timestamp()-interval '90 days') ORDER BY h.workspace_id LIMIT 1 FOR UPDATE OF h SKIP LOCKED`).Scan(&workspace, &generation, &minimum, &head)
	if errors.Is(e, pgx.ErrNoRows) {
		return result, nil
	}
	if e != nil {
		return result, errors.New("sync cleanup unavailable")
	}
	var now time.Time
	if e = tx.QueryRow(ctx, `SELECT clock_timestamp()`).Scan(&now); e != nil {
		return result, errors.New("sync cleanup unavailable")
	}
	cleared, e := tx.Exec(ctx, `WITH expired AS MATERIALIZED(SELECT actor_user_id,id FROM sync_snapshots WHERE workspace_id=$1 AND payload_cleared_at IS NULL AND (expires_at<=$3 OR generation_id<>$2 OR cursor_key_id IS NULL) ORDER BY expires_at,actor_user_id,id LIMIT $4), removed AS (DELETE FROM sync_snapshot_items i USING expired s WHERE i.workspace_id=$1 AND i.actor_user_id=s.actor_user_id AND i.snapshot_id=s.id) UPDATE sync_snapshots s SET payload_cleared_at=$3 FROM expired e WHERE s.workspace_id=$1 AND s.actor_user_id=e.actor_user_id AND s.id=e.id`, workspace, generation, now, limit)
	if e != nil {
		return result, errors.New("snapshot cleanup failed")
	}
	result.Snapshots = cleared.RowsAffected()
	rows, e := tx.Query(ctx, `SELECT sequence,created_at FROM sync_groups WHERE workspace_id=$1 AND generation_id=$2 AND sequence>=$3 ORDER BY sequence LIMIT $4`, workspace, generation, minimum, limit)
	if e != nil {
		return result, errors.New("journal cleanup unavailable")
	}
	last := minimum - 1
	cutoff := now.Add(-90 * 24 * time.Hour)
	for rows.Next() {
		var seq int64
		var created time.Time
		if e = rows.Scan(&seq, &created); e != nil {
			rows.Close()
			return result, errors.New("journal cleanup unavailable")
		}
		if seq != last+1 {
			rows.Close()
			return result, errors.New("journal continuity invalid")
		}
		if created.After(cutoff) || seq == math.MaxInt64 {
			break
		}
		last = seq
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return result, errors.New("journal cleanup unavailable")
	}
	if last >= minimum {
		deleted, e := tx.Exec(ctx, `DELETE FROM sync_groups WHERE workspace_id=$1 AND generation_id=$2 AND sequence BETWEEN $3 AND $4`, workspace, generation, minimum, last)
		if e != nil || deleted.RowsAffected() != last-minimum+1 {
			return result, errors.New("journal cleanup failed")
		}
		if _, e = tx.Exec(ctx, `UPDATE sync_heads SET min_available_sequence=$2 WHERE workspace_id=$1`, workspace, last+1); e != nil {
			return result, errors.New("journal cleanup failed")
		}
		result.Groups = deleted.RowsAffected()
	}
	if e = tx.Commit(ctx); e != nil {
		return SyncCleanupResult{}, errors.New("sync cleanup commit failed")
	}
	return result, nil
}
