package sync

import (
	"accounting/backend/internal/identity"
	"context"
	"encoding/json"
	"errors"
	"github.com/jackc/pgx/v5"
	"strconv"
	"strings"
	"time"
)

const MaxSnapshotItems = 50000
const MaxSnapshotBytes = 64 * 1024 * 1024

// AggregateReader reuses ledger readers without introducing a ledger/sync cycle.
type AggregateReader func(context.Context, pgx.Tx, string, string, string) (json.RawMessage, error)
type SnapshotService struct {
	Identity      *identity.Service
	Keys          *CursorKeys
	ReadAggregate AggregateReader
}
type Snapshot struct {
	ID         string    `json:"id"`
	Workspace  string    `json:"workspace_id"`
	Generation string    `json:"generation_id"`
	Base       string    `json:"base_sequence"`
	Resume     string    `json:"resume_cursor"`
	Created    time.Time `json:"created_at"`
	Expires    time.Time `json:"expires_at"`
	Count      int       `json:"item_count"`
	First      string    `json:"first_page_token"`
}
type SnapshotItem struct {
	Kind string          `json:"entity_type"`
	Data json.RawMessage `json:"data"`
}
type SnapshotPage struct {
	ID         string         `json:"snapshot_id"`
	Workspace  string         `json:"workspace_id"`
	Generation string         `json:"generation_id"`
	Base       string         `json:"base_sequence"`
	Items      []SnapshotItem `json:"items"`
	Next       *string        `json:"next_page_token"`
}
type snapshotRecord struct {
	Snapshot
	Key     string
	Cleared *time.Time
	Bytes   int64
}

func (s *SnapshotService) authorized(ctx context.Context, credential, workspace string, authorize func(identity.Principal) error, callback func(pgx.Tx, identity.Principal, string) error) error {
	return s.Identity.WithSession(ctx, credential, false, func(tx pgx.Tx, p identity.Principal) error {
		if authorize != nil {
			if e := authorize(p); e != nil {
				return e
			}
		}
		if !uuidPattern.MatchString(workspace) {
			return problem(400, "bad_request")
		}
		if e := identity.RequireMembership(ctx, tx, p.Profile.ID, workspace); e != nil {
			return e
		}
		var generation string
		if e := tx.QueryRow(ctx, `SELECT generation_id::text FROM sync_heads WHERE workspace_id=$1 FOR SHARE`, workspace).Scan(&generation); e != nil {
			return e
		}
		return callback(tx, p, generation)
	})
}
func readSnapshot(ctx context.Context, tx pgx.Tx, actor, workspace, id string) (snapshotRecord, error) {
	var r snapshotRecord
	r.ID = id
	r.Workspace = workspace
	e := tx.QueryRow(ctx, `SELECT generation_id::text,base_sequence::text,created_at,expires_at,item_count,payload_bytes,payload_cleared_at,coalesce(cursor_key_id,'') FROM sync_snapshots WHERE workspace_id=$1 AND actor_user_id=$2 AND id=$3`, workspace, actor, id).Scan(&r.Generation, &r.Base, &r.Created, &r.Expires, &r.Count, &r.Bytes, &r.Cleared, &r.Key)
	if errors.Is(e, pgx.ErrNoRows) {
		return r, problem(404, "not_found")
	}
	r.Created = r.Created.UTC()
	r.Expires = r.Expires.UTC()
	return r, e
}
func (s *SnapshotService) live(ctx context.Context, tx pgx.Tx, actor, generation string, r snapshotRecord) (Snapshot, *CursorKeys, error) {
	var now time.Time
	if e := tx.QueryRow(ctx, `SELECT clock_timestamp()`).Scan(&now); e != nil {
		return Snapshot{}, nil, e
	}
	if r.Generation != generation || !now.Before(r.Expires) || r.Cleared != nil {
		return Snapshot{}, nil, problem(410, "snapshot_expired")
	}
	keys, e := s.Keys.snapshotKey(r.Key)
	if e != nil {
		return Snapshot{}, nil, e
	}
	base, e := strconv.ParseInt(r.Base, 10, 64)
	if e != nil {
		return Snapshot{}, nil, e
	}
	r.Resume, e = keys.Sign(actor, r.Workspace, r.Generation, base)
	if e != nil {
		return Snapshot{}, nil, e
	}
	r.First, e = keys.pageToken(actor, r.Snapshot, 0)
	return r.Snapshot, keys, e
}
func (s *SnapshotService) Create(ctx context.Context, credential, workspace, id string, authorize func(identity.Principal) error) (Snapshot, error) {
	workspace = strings.ToLower(workspace)
	id = strings.ToLower(id)
	var result Snapshot
	if !uuidPattern.MatchString(id) {
		return result, problem(422, "validation_error")
	}
	e := s.authorized(ctx, credential, workspace, authorize, func(tx pgx.Tx, p identity.Principal, generation string) error {
		// Head SHARE freezes aggregates. A scoped advisory lock serializes quota and
		// equal-ID creation without promoting authorization row locks.
		if _, e := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1,0))`, workspace+":"+p.Profile.ID); e != nil {
			return e
		}
		existing, e := readSnapshot(ctx, tx, p.Profile.ID, workspace, id)
		if e == nil {
			result, _, e = s.live(ctx, tx, p.Profile.ID, generation, existing)
			return e
		}
		var missing *identity.Error
		if !errors.As(e, &missing) || missing.Status != 404 {
			return e
		}
		if s.Keys == nil || s.ReadAggregate == nil {
			return problem(503, "service_unavailable")
		}
		var count int
		if e = tx.QueryRow(ctx, `SELECT count(*) FROM sync_snapshots WHERE workspace_id=$1 AND actor_user_id=$2 AND generation_id=$3 AND expires_at>clock_timestamp() AND payload_cleared_at IS NULL AND cursor_key_id IS NOT NULL`, workspace, p.Profile.ID, generation).Scan(&count); e != nil {
			return e
		}
		if count >= 2 {
			return problem(429, "rate_limited")
		}
		rows, e := tx.Query(ctx, `SELECT entity_type,id::text FROM (SELECT 1 rank,'category' entity_type,id FROM categories WHERE workspace_id=$1 AND deleted_at IS NULL UNION ALL SELECT 2,'tag',id FROM tags WHERE workspace_id=$1 AND deleted_at IS NULL UNION ALL SELECT 3,'account',id FROM accounts WHERE workspace_id=$1 AND deleted_at IS NULL UNION ALL SELECT 4,'transaction',id FROM transactions WHERE workspace_id=$1 AND deleted_at IS NULL) entities ORDER BY rank,id LIMIT 50001`, workspace)
		if e != nil {
			return e
		}
		type ref struct{ kind, id string }
		refs := []ref{}
		for rows.Next() {
			var r ref
			if e = rows.Scan(&r.kind, &r.id); e != nil {
				rows.Close()
				return e
			}
			refs = append(refs, r)
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return e
		}
		if len(refs) > MaxSnapshotItems {
			return problem(422, "snapshot_too_large")
		}
		items := make([][]any, 0, len(refs))
		var size int64
		for i, r := range refs {
			raw, e := s.ReadAggregate(ctx, tx, workspace, r.kind, r.id)
			if e != nil {
				return e
			}
			payload, e := json.Marshal(raw)
			if e != nil {
				return e
			}
			size += int64(len(payload))
			if size > MaxSnapshotBytes {
				return problem(422, "snapshot_too_large")
			}
			items = append(items, []any{workspace, p.Profile.ID, id, i + 1, r.kind, r.id, payload})
		}
		var now time.Time
		var base int64
		if e = tx.QueryRow(ctx, `SELECT clock_timestamp(),last_sequence FROM sync_heads WHERE workspace_id=$1`, workspace).Scan(&now, &base); e != nil {
			return e
		}
		now = now.UTC()
		result = Snapshot{ID: id, Workspace: workspace, Generation: generation, Base: strconv.FormatInt(base, 10), Created: now, Expires: now.Add(15 * time.Minute), Count: len(items)}
		result.Resume, e = s.Keys.Sign(p.Profile.ID, workspace, generation, base)
		if e != nil {
			return e
		}
		result.First, e = s.Keys.pageToken(p.Profile.ID, result, 0)
		if e != nil {
			return e
		}
		if _, e = tx.Exec(ctx, `INSERT INTO sync_snapshots(workspace_id,actor_user_id,id,generation_id,base_sequence,created_at,expires_at,item_count,payload_bytes,cursor_key_id) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`, workspace, p.Profile.ID, id, generation, base, now, result.Expires, len(items), size, s.Keys.Active); e != nil {
			return e
		}
		if len(items) > 0 {
			n, e := tx.CopyFrom(ctx, pgx.Identifier{"sync_snapshot_items"}, []string{"workspace_id", "actor_user_id", "snapshot_id", "position", "entity_type", "entity_id", "payload"}, pgx.CopyFromRows(items))
			if e != nil {
				return e
			}
			if n != int64(len(items)) {
				return problem(503, "service_unavailable")
			}
		}
		return nil
	})
	return result, e
}
func (s *SnapshotService) Get(ctx context.Context, credential, workspace, id string) (Snapshot, error) {
	workspace = strings.ToLower(workspace)
	id = strings.ToLower(id)
	var result Snapshot
	if !uuidPattern.MatchString(id) {
		return result, problem(400, "bad_request")
	}
	e := s.authorized(ctx, credential, workspace, nil, func(tx pgx.Tx, p identity.Principal, generation string) error {
		r, e := readSnapshot(ctx, tx, p.Profile.ID, workspace, id)
		if e != nil {
			return e
		}
		result, _, e = s.live(ctx, tx, p.Profile.ID, generation, r)
		return e
	})
	return result, e
}
func (s *SnapshotService) Page(ctx context.Context, credential, workspace, id, token string) (SnapshotPage, error) {
	workspace = strings.ToLower(workspace)
	id = strings.ToLower(id)
	var result SnapshotPage
	if !uuidPattern.MatchString(id) {
		return result, problem(400, "bad_request")
	}
	e := s.authorized(ctx, credential, workspace, nil, func(tx pgx.Tx, p identity.Principal, generation string) error {
		r, e := readSnapshot(ctx, tx, p.Profile.ID, workspace, id)
		if e != nil {
			return e
		}
		snapshot, keys, e := s.live(ctx, tx, p.Profile.ID, generation, r)
		if e != nil {
			return e
		}
		position, e := keys.pagePosition(token, p.Profile.ID, snapshot)
		if e != nil {
			return e
		}
		result = SnapshotPage{ID: id, Workspace: workspace, Generation: generation, Base: snapshot.Base, Items: []SnapshotItem{}}
		rows, e := tx.Query(ctx, `SELECT position,entity_type,payload FROM sync_snapshot_items WHERE workspace_id=$1 AND actor_user_id=$2 AND snapshot_id=$3 AND position>$4 AND position<=$5 ORDER BY position`, workspace, p.Profile.ID, id, position, position+100)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var n int
			var item SnapshotItem
			if e = rows.Scan(&n, &item.Kind, &item.Data); e != nil {
				return e
			}
			if n != position+len(result.Items)+1 {
				return problem(503, "service_unavailable")
			}
			result.Items = append(result.Items, item)
		}
		if e = rows.Err(); e != nil {
			return e
		}
		expected := snapshot.Count - position
		if expected > 100 {
			expected = 100
		}
		if len(result.Items) != expected {
			return problem(503, "service_unavailable")
		}
		if position+len(result.Items) < snapshot.Count {
			next, e := keys.pageToken(p.Profile.ID, snapshot, position+100)
			if e != nil {
				return e
			}
			result.Next = &next
		}
		return nil
	})
	return result, e
}
