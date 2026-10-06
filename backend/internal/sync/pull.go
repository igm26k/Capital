package sync

import (
	"accounting/backend/internal/identity"
	"context"
	"encoding/json"
	"github.com/jackc/pgx/v5"
	"strconv"
)

const MaxPullBytes = 4 * 1024 * 1024

type PullGroup struct {
	Sequence   string  `json:"sequence"`
	Action     string  `json:"action_id"`
	Workspace  string  `json:"workspace_id"`
	Generation string  `json:"generation_id"`
	Changes    []event `json:"changes"`
}
type PullPage struct {
	Workspace  string      `json:"workspace_id"`
	Generation string      `json:"generation_id"`
	Groups     []PullGroup `json:"groups"`
	Next       string      `json:"next_cursor"`
	More       bool        `json:"has_more"`
	Head       string      `json:"head_sequence"`
	Minimum    string      `json:"min_available_sequence"`
}
type PullReader struct {
	Identity *identity.Service
	Keys     *CursorKeys
}

func (r *PullReader) Read(ctx context.Context, credential, workspace, cursor string, limit int) (PullPage, error) {
	var result PullPage
	e := r.Identity.WithSessionIsolation(ctx, credential, pgx.RepeatableRead, func(tx pgx.Tx, p identity.Principal) error {
		if e := identity.RequireMembership(ctx, tx, p.Profile.ID, workspace); e != nil {
			return e
		}
		if limit < 1 || limit > 100 {
			return problem(400, "bad_request")
		}
		pos, after, e := r.Keys.parse(cursor, p.Profile.ID, workspace)
		if e != nil {
			return e
		}
		var generation string
		var head, minimum int64
		if e = tx.QueryRow(ctx, `SELECT generation_id::text,last_sequence,min_available_sequence FROM sync_heads WHERE workspace_id=$1`, workspace).Scan(&generation, &head, &minimum); e != nil {
			return e
		}
		if pos.Generation != generation || after < minimum-1 {
			return problem(410, "cursor_expired")
		}
		if after > head {
			return problem(400, "cursor_invalid")
		}
		result = PullPage{Workspace: workspace, Generation: generation, Groups: []PullGroup{}, Next: cursor, Head: strconv.FormatInt(head, 10), Minimum: strconv.FormatInt(minimum, 10)}
		rows, e := tx.Query(ctx, `SELECT sequence,action_id::text,change_count FROM sync_groups WHERE workspace_id=$1 AND generation_id=$2 AND sequence>$3 AND sequence<=$4 ORDER BY sequence LIMIT $5`, workspace, generation, after, head, limit)
		if e != nil {
			return e
		}
		type meta struct {
			seq    int64
			action string
			count  int
		}
		metas := []meta{}
		for rows.Next() {
			var m meta
			if e = rows.Scan(&m.seq, &m.action, &m.count); e != nil {
				rows.Close()
				return e
			}
			metas = append(metas, m)
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return e
		}
		for _, m := range metas {
			if m.seq != after+1 {
				return problem(503, "service_unavailable")
			}
			g := PullGroup{Sequence: strconv.FormatInt(m.seq, 10), Action: m.action, Workspace: workspace, Generation: generation, Changes: []event{}}
			changes, e := tx.Query(ctx, `SELECT ordinal,change_id::text,created_at,operation,entity_type,payload FROM sync_changes WHERE workspace_id=$1 AND generation_id=$2 AND sequence=$3 ORDER BY ordinal`, workspace, generation, m.seq)
			if e != nil {
				return e
			}
			for changes.Next() {
				v := event{Schema: 1, ActionID: m.action, WorkspaceID: workspace, Generation: generation, Sequence: g.Sequence, GroupSize: m.count}
				if e = changes.Scan(&v.Ordinal, &v.ChangeID, &v.Created, &v.Operation, &v.EntityType, &v.Data); e != nil {
					changes.Close()
					return e
				}
				if v.Ordinal != len(g.Changes)+1 {
					changes.Close()
					return problem(503, "service_unavailable")
				}
				v.Created = v.Created.UTC()
				g.Changes = append(g.Changes, v)
			}
			e = changes.Err()
			changes.Close()
			if e != nil {
				return e
			}
			if len(g.Changes) != m.count {
				return problem(503, "service_unavailable")
			}
			next, e := r.Keys.Sign(p.Profile.ID, workspace, generation, m.seq)
			if e != nil {
				return e
			}
			candidate := result
			candidate.Groups = append(append([]PullGroup{}, result.Groups...), g)
			candidate.Next = next
			candidate.More = m.seq < head
			encoded, e := json.Marshal(candidate)
			if e != nil {
				return e
			}
			if len(encoded)+1 > MaxPullBytes {
				if len(result.Groups) == 0 {
					return problem(503, "service_unavailable")
				}
				break
			}
			result = candidate
			after = m.seq
		}
		if after < head && len(result.Groups) < limit { // A byte boundary is valid; missing groups are not.
			if len(metas) == 0 || metas[len(metas)-1].seq == after {
				return problem(503, "service_unavailable")
			}
		}
		result.More = after < head
		return nil
	})
	return result, e
}
