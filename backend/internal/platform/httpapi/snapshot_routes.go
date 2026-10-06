package httpapi

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	commands "accounting/backend/internal/sync"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"github.com/jackc/pgx/v5"
	"io"
	"mime"
	"net/http"
	"net/url"
	"strings"
)

func MountSnapshots(mux *http.ServeMux, service *commands.SnapshotService, options AuthOptions) {
	service.ReadAggregate = func(ctx context.Context, tx pgx.Tx, workspace, kind, id string) (json.RawMessage, error) {
		switch kind {
		case "account":
			return ledger.ReadAccount(ctx, tx, workspace, id)
		case "transaction":
			return ledger.ReadTransaction(ctx, tx, workspace, id)
		case "category", "tag":
			return ledger.ReadClassification(ctx, tx, workspace, id, kind)
		}
		return nil, fail(503, "service_unavailable")
	}
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/sync/snapshots", func(w http.ResponseWriter, r *http.Request) {
		token, transport, e := credential(r)
		if e != nil {
			reject(w, e)
			return
		}
		if r.URL.RawQuery != "" || len(r.Header.Values("Idempotency-Key")) != 1 {
			reject(w, fail(400, "bad_request"))
			return
		}
		media, _, e := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if e != nil || media != "application/json" {
			reject(w, fail(400, "bad_request"))
			return
		}
		body, e := io.ReadAll(http.MaxBytesReader(w, r.Body, 262144))
		if e != nil {
			reject(w, fail(413, "bad_request"))
			return
		}
		if _, e = commands.CanonicalJSON(body); e != nil {
			reject(w, fail(400, "bad_request"))
			return
		}
		var input struct {
			ID string `json:"id"`
		}
		var fields map[string]json.RawMessage
		if json.Unmarshal(body, &input) != nil || json.Unmarshal(body, &fields) != nil || len(fields) != 1 || fields["id"] == nil || !validUUID(input.ID) {
			reject(w, fail(422, "validation_error"))
			return
		}
		if !validUUID(r.Header.Get("Idempotency-Key")) || !strings.EqualFold(input.ID, r.Header.Get("Idempotency-Key")) {
			reject(w, fail(400, "bad_request"))
			return
		}
		authorize := func(p identity.Principal) error {
			if transport == "cookie" {
				sum := sha256.Sum256([]byte(r.Header.Get("X-CSRF-Token")))
				if len(r.Header.Values("Origin")) != 1 || r.Header.Get("Origin") != options.Origin || len(r.Header.Values("X-CSRF-Token")) != 1 || subtle.ConstantTimeCompare(sum[:], p.CSRFHash) != 1 {
					return fail(403, "csrf_invalid")
				}
			}
			return nil
		}
		snapshot, e := service.Create(r.Context(), token, r.PathValue("workspace_id"), input.ID, authorize)
		if e != nil {
			reject(w, e)
			return
		}
		respond(w, 201, snapshot)
	})
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/sync/snapshots/{id}", func(w http.ResponseWriter, r *http.Request) {
		token, _, e := credential(r)
		if e != nil {
			reject(w, e)
			return
		}
		if r.URL.RawQuery != "" {
			reject(w, fail(400, "bad_request"))
			return
		}
		snapshot, e := service.Get(r.Context(), token, r.PathValue("workspace_id"), r.PathValue("id"))
		if e != nil {
			reject(w, e)
			return
		}
		respond(w, 200, snapshot)
	})
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/sync/snapshots/{id}/pages", func(w http.ResponseWriter, r *http.Request) {
		token, _, e := credential(r)
		if e != nil {
			reject(w, e)
			return
		}
		q, e := url.ParseQuery(r.URL.RawQuery)
		if e != nil || len(q) != 1 || len(q["page_token"]) != 1 {
			reject(w, fail(400, "bad_request"))
			return
		}
		page, e := service.Page(r.Context(), token, r.PathValue("workspace_id"), r.PathValue("id"), q.Get("page_token"))
		if e != nil {
			reject(w, e)
			return
		}
		respond(w, 200, page)
	})
}
