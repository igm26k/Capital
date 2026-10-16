package httpapi

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	commands "accounting/backend/internal/sync"
	"net/http"

	"github.com/jackc/pgx/v5"
)

func MountImports(mux *http.ServeMux, auth *identity.Service, options AuthOptions) {
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/imports", commandHTTPHandlerLimit(options, func(body []byte) error { _, err := ledger.DecodeImport(body); return err }, func(r *http.Request, request commands.Request) (commands.Result, error) {
		v, _ := ledger.DecodeImport(request.Body)
		return ledger.ExecuteImportSetting(r.Context(), auth, request, ledger.CreateImport(request.WorkspaceID, request.GenerationID, v))
	}, 14*1024*1024))
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/imports/{id}/confirm", commandHTTPHandler(options, func(body []byte) error { _, err := ledger.DecodeImportConfirm(body); return err }, func(r *http.Request, request commands.Request) (commands.Result, error) {
		if !validUUID(r.PathValue("id")) {
			return commands.Result{}, fail(400, "bad_request")
		}
		v, _ := ledger.DecodeImportConfirm(request.Body)
		return ledger.ExecuteImportSetting(r.Context(), auth, request, ledger.ConfirmImport(request.WorkspaceID, r.PathValue("id"), request.GenerationID, v))
	}))
	read := func(w http.ResponseWriter, r *http.Request) {
		token, _, err := credential(r)
		if err != nil {
			reject(w, err)
			return
		}
		workspace := r.PathValue("workspace_id")
		if !validUUID(workspace) {
			reject(w, fail(400, "bad_request"))
			return
		}
		var result any
		err = auth.WithSessionIsolation(r.Context(), token, pgx.RepeatableRead, func(tx pgx.Tx, p identity.Principal) error {
			if err := identity.RequireMembership(r.Context(), tx, p.Profile.ID, workspace); err != nil {
				return err
			}
			var err error
			if r.PathValue("id") != "" {
				if r.URL.RawQuery != "" {
					return fail(400, "bad_request")
				}
				result, err = ledger.ReadImport(r.Context(), tx, workspace, r.PathValue("id"), p.Profile.ID)
			} else {
				q, e := reportQuery(r)
				if e != nil {
					return e
				}
				result, err = ledger.ExportCSV(r.Context(), tx, workspace, q)
			}
			return err
		})
		if err != nil {
			reject(w, err)
			return
		}
		respond(w, 200, result)
	}
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/imports/{id}", read)
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/exports", read)
}
