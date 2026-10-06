package httpapi

import (
	"accounting/backend/internal/identity"
	"context"
	"crypto/subtle"
	"encoding/base64"
	"github.com/jackc/pgx/v5"
	"net/url"
	"strconv"
	"strings"
)

func list(ctx context.Context, tx pgx.Tx, p identity.Principal, token string, u *url.URL) (any, error) {
	limit := 50
	q := u.Query()
	for k, v := range q {
		if (k != "limit" && k != "cursor") || len(v) != 1 {
			return nil, fail(400, "bad_request")
		}
	}
	if q.Has("limit") {
		n, e := strconv.Atoi(q.Get("limit"))
		if e != nil || n < 1 || n > 100 {
			return nil, fail(400, "bad_request")
		}
		limit = n
	}
	after := "00000000-0000-0000-0000-000000000000"
	if q.Has("cursor") {
		cursor := q.Get("cursor")
		if len(cursor) > 2048 {
			return nil, fail(400, "cursor_invalid")
		}
		parts := strings.Split(cursor, ".")
		if len(parts) != 2 {
			return nil, fail(400, "cursor_invalid")
		}
		raw, e := base64.RawURLEncoding.DecodeString(parts[0])
		value := string(raw)
		if e != nil || !strings.HasPrefix(value, u.Path+":") {
			return nil, fail(400, "cursor_invalid")
		}
		after = strings.TrimPrefix(value, u.Path+":")
		if !validUUID(after) || subtle.ConstantTimeCompare([]byte(cursor), []byte(signedCursor(token, value))) != 1 {
			return nil, fail(400, "cursor_invalid")
		}
	}
	items := []any{}
	last := ""
	var err error
	if u.Path == "/api/v1/sessions" {
		rows, e := tx.Query(ctx, `SELECT id::text,device_name,created_at,last_seen_at,expires_at,created_at+interval '720 hours' FROM sessions WHERE user_id=$1 AND id>$2 AND revoked_at IS NULL AND expires_at>now() AND created_at+interval '720 hours'>now() ORDER BY id LIMIT $3`, p.Profile.ID, after, limit+1)
		if e != nil {
			return nil, fail(503, "service_unavailable")
		}
		defer rows.Close()
		for rows.Next() {
			var s identity.Session
			if e = rows.Scan(&s.ID, &s.Device, &s.Created, &s.LastSeen, &s.Expires, &s.Absolute); e != nil {
				return nil, fail(503, "service_unavailable")
			}
			s.Created = s.Created.UTC()
			s.LastSeen = s.LastSeen.UTC()
			s.Expires = s.Expires.UTC()
			s.Absolute = s.Absolute.UTC()
			s.Current = s.ID == p.Session.ID
			items = append(items, s)
			if len(items) <= limit {
				last = s.ID
			}
		}
		err = rows.Err()
	} else {
		rows, e := tx.Query(ctx, `SELECT w.id::text,w.name,w.version::text,m.role,w.sync_generation_id::text FROM memberships m JOIN workspaces w ON w.id=m.workspace_id WHERE m.user_id=$1 AND m.revoked_at IS NULL AND w.id>$2 ORDER BY w.id LIMIT $3 FOR SHARE OF m`, p.Profile.ID, after, limit+1)
		if e != nil {
			return nil, fail(503, "service_unavailable")
		}
		defer rows.Close()
		for rows.Next() {
			var w identity.Workspace
			if e = rows.Scan(&w.ID, &w.Name, &w.Version, &w.Role, &w.Generation); e != nil {
				return nil, fail(503, "service_unavailable")
			}
			items = append(items, w)
			if len(items) <= limit {
				last = w.ID
			}
		}
		err = rows.Err()
	}
	if err != nil {
		return nil, fail(503, "service_unavailable")
	}
	var next any
	if len(items) > limit {
		items = items[:limit]
		next = signedCursor(token, u.Path+":"+last)
	}
	return map[string]any{"items": items, "next_cursor": next}, nil
}
