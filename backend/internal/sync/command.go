package sync

import (
	"accounting/backend/internal/identity"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"math"
	"strings"
	"time"
)

type Request struct {
	Credential, WorkspaceID, ActionID, GenerationID, Method, Path, RequestID string
	Body                                                                     []byte
	Authorize                                                                func(identity.Principal) error
}
type Result struct {
	Status   int
	Body     json.RawMessage
	Replayed bool
}
type Change struct {
	EntityType, ID, Version, Operation string
	Data                               json.RawMessage
}
type Mutation struct {
	Status  int
	Changes []Change
}
type Rejection struct {
	Status                       int
	Code, Message                string
	FieldErrors, CurrentVersions []any
}

func (e *Rejection) Error() string { return e.Code }

type Runner struct{ Identity *identity.Service }
type Command func(context.Context, pgx.Tx, identity.Principal) (Mutation, error)

func problem(status int, code string) error { return &identity.Error{Status: status, Code: code} }
func newUUID() string {
	b := make([]byte, 16)
	if _, e := rand.Read(b); e != nil {
		panic("identifier unavailable")
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	s := hex.EncodeToString(b)
	return s[:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:]
}

// The callback may only change this PostgreSQL transaction: retries must not repeat external effects.
func (r *Runner) Execute(ctx context.Context, request Request, command Command) (Result, error) {
	if !uuidPattern.MatchString(request.RequestID) || !uuidPattern.MatchString(request.WorkspaceID) || !uuidPattern.MatchString(request.ActionID) || !uuidPattern.MatchString(request.GenerationID) || command == nil {
		return Result{}, problem(400, "bad_request")
	}
	request.WorkspaceID = strings.ToLower(request.WorkspaceID)
	request.ActionID = strings.ToLower(request.ActionID)
	request.GenerationID = strings.ToLower(request.GenerationID)
	expectedPrefix := "/api/v1/workspaces/" + request.WorkspaceID + "/"
	normalizedPath := pathUUID.ReplaceAllStringFunc(request.Path, strings.ToLower)
	if !strings.HasPrefix(normalizedPath, expectedPrefix) {
		return Result{}, problem(400, "bad_request")
	}
	hash, e := RequestHash(request.Method, request.Path, request.GenerationID, request.Body)
	if e != nil {
		return Result{}, problem(400, "bad_request")
	}
	for attempt := 0; attempt < 3; attempt++ {
		var result Result
		err := r.Identity.WithSession(ctx, request.Credential, false, func(tx pgx.Tx, p identity.Principal) error {
			if request.Authorize != nil {
				if e := request.Authorize(p); e != nil {
					return e
				}
			}
			if e := identity.RequireMembership(ctx, tx, p.Profile.ID, request.WorkspaceID); e != nil {
				return e
			}
			var generation string
			var head int64
			e := tx.QueryRow(ctx, `SELECT generation_id::text,last_sequence FROM sync_heads WHERE workspace_id=$1 FOR UPDATE`, request.WorkspaceID).Scan(&generation, &head)
			if e != nil {
				return e
			}
			found, e := replay(ctx, tx, request, p.Profile.ID, hash[:], &result)
			if e != nil || found {
				return e
			}
			if generation != request.GenerationID {
				return problem(409, "sync_generation_conflict")
			}
			if head == math.MaxInt64 {
				return problem(503, "service_unavailable")
			}
			if _, e = tx.Exec(ctx, "SAVEPOINT domain_command"); e != nil {
				return e
			}
			mutation, e := command(ctx, tx, p)
			var rejection *Rejection
			if e != nil {
				if !errors.As(e, &rejection) || rejection.Status != 409 && rejection.Status != 422 {
					return e
				}
				if _, e = tx.Exec(ctx, "ROLLBACK TO SAVEPOINT domain_command"); e != nil {
					return e
				}
				return saveRejection(ctx, tx, request, p.Profile.ID, hash[:], rejection, &result)
			}
			events, body, refs, payloadBytes, e := buildJournal(request, mutation, head+1)
			if e != nil {
				if errors.As(e, &rejection) {
					if _, e = tx.Exec(ctx, "ROLLBACK TO SAVEPOINT domain_command"); e != nil {
						return e
					}
					return saveRejection(ctx, tx, request, p.Profile.ID, hash[:], rejection, &result)
				}
				return e
			}
			var completed time.Time
			if e = tx.QueryRow(ctx, "SELECT clock_timestamp()").Scan(&completed); e != nil {
				return e
			}
			completed = completed.UTC()
			for i := range events {
				events[i].Created = completed
			}
			// Count the finalized event envelopes including commit timestamps.
			size, e := journalSize(request, events, head+1)
			if e != nil {
				return e
			}
			payloadBytes = size
			if payloadBytes > 1048576 {
				if _, e = tx.Exec(ctx, "ROLLBACK TO SAVEPOINT domain_command"); e != nil {
					return e
				}
				return saveRejection(ctx, tx, request, p.Profile.ID, hash[:], &Rejection{Status: 422, Code: "sync_group_too_large", Message: "Группа изменений слишком велика."}, &result)
			}
			if e = storeReceipt(ctx, tx, request, p.Profile.ID, hash[:], "applied", mutation.Status, body, refs, completed, head+1, &generation); e != nil {
				return e
			}
			if _, e = tx.Exec(ctx, `INSERT INTO sync_groups(workspace_id,generation_id,sequence,action_id,actor_user_id,created_at,change_count,payload_bytes) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`, request.WorkspaceID, generation, head+1, request.ActionID, p.Profile.ID, completed, len(events), payloadBytes); e != nil {
				return e
			}
			for _, event := range events {
				if _, e = tx.Exec(ctx, `INSERT INTO sync_changes(workspace_id,generation_id,sequence,ordinal,change_id,entity_type,entity_id,entity_version,operation,payload,created_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)`, request.WorkspaceID, generation, head+1, event.Ordinal, event.ChangeID, event.EntityType, event.DataID, event.Version, event.Operation, []byte(event.Data), completed); e != nil {
					return e
				}
				if _, e = tx.Exec(ctx, `INSERT INTO audit_events(id,workspace_id,actor_user_id,action_id,entity_type,entity_id,event_type,timestamp) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`, newUUID(), request.WorkspaceID, p.Profile.ID, request.ActionID, event.EntityType, event.DataID, event.Operation, completed); e != nil {
					return e
				}
			}
			if _, e = tx.Exec(ctx, `UPDATE sync_heads SET last_sequence=$1 WHERE workspace_id=$2`, head+1, request.WorkspaceID); e != nil {
				return e
			}
			result = Result{Status: mutation.Status, Body: body}
			return nil
		})
		if err == nil {
			return result, nil
		}
		var postgres *pgconn.PgError
		if errors.As(err, &postgres) && (postgres.Code == "40P01" || postgres.Code == "40001") && attempt < 2 {
			select {
			case <-ctx.Done():
				return Result{}, problem(503, "service_unavailable")
			case <-time.After(time.Duration(attempt+1) * 10 * time.Millisecond):
				continue
			}
		}
		var domain *identity.Error
		if errors.As(err, &domain) {
			return Result{}, domain
		}
		return Result{}, problem(503, "service_unavailable")
	}
	return Result{}, problem(503, "service_unavailable")
}
