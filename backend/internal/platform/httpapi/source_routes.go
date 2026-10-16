package httpapi

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	commands "accounting/backend/internal/sync"
	"github.com/jackc/pgx/v5"
	"net/http"
)

func MountSources(mux *http.ServeMux, auth *identity.Service, options AuthOptions) {
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/source-events", commandHTTPHandler(options, func(body []byte) error { _, err := ledger.DecodeSourceEvent(body); return err }, func(r *http.Request, req commands.Request) (commands.Result, error) {
		v, _ := ledger.DecodeSourceEvent(req.Body)
		return ledger.ExecuteImportSetting(r.Context(), auth, req, ledger.CreateSourceEvent(req.WorkspaceID, req.GenerationID, v))
	}))
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/source-events/{id}/confirm", commandHTTPHandler(options, func(body []byte) error { _, err := ledger.DecodeSourceDecision(body); return err }, func(r *http.Request, req commands.Request) (commands.Result, error) {
		if !validUUID(r.PathValue("id")) {
			return commands.Result{}, fail(400, "bad_request")
		}
		input, _ := ledger.DecodeSourceDecision(req.Body)
		return ledger.ExecuteImportSetting(r.Context(), auth, req, ledger.ConfirmSourceEvent(req.WorkspaceID, r.PathValue("id"), req.GenerationID, input))
	}))
	read := func(w http.ResponseWriter, r *http.Request) {
		token, _, err := credential(r)
		if err != nil {
			reject(w, err)
			return
		}
		workspace := r.PathValue("workspace_id")
		if !validUUID(workspace) || r.URL.RawQuery != "" {
			reject(w, fail(400, "bad_request"))
			return
		}
		var result any
		err = auth.WithSessionIsolation(r.Context(), token, pgx.RepeatableRead, func(tx pgx.Tx, p identity.Principal) error {
			if err := identity.RequireMembership(r.Context(), tx, p.Profile.ID, workspace); err != nil {
				return err
			}
			if id := r.PathValue("id"); id != "" {
				var err error
				result, err = ledger.ReadSourceEvent(r.Context(), tx, workspace, id, p.Profile.ID)
				return err
			}
			items, more, err := ledger.ListSourceEvents(r.Context(), tx, workspace, p.Profile.ID)
			result = map[string]any{"items": items, "has_more": more}
			return err
		})
		if err != nil {
			reject(w, err)
			return
		}
		respond(w, 200, result)
	}
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/source-events", read)
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/source-events/{id}", read)
}
