package httpapi

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	commands "accounting/backend/internal/sync"
	"github.com/jackc/pgx/v5"
	"net/http"
	"net/url"
	"strconv"
)

func MountSources(mux *http.ServeMux, auth *identity.Service, options AuthOptions) {
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/source-events", commandHTTPHandler(options, func(body []byte) error { _, err := ledger.DecodeSourceEvent(body); return err }, func(r *http.Request, req commands.Request) (commands.Result, error) {
		v, _ := ledger.DecodeSourceEvent(req.Body)
		return ledger.ExecuteImportSetting(r.Context(), auth, req, ledger.CreateSourceEvent(req.WorkspaceID, req.GenerationID, v))
	}))
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/source-events/auto", commandHTTPHandler(options, func(body []byte) error { _, err := ledger.DecodeSourceEvent(body); return err }, func(r *http.Request, req commands.Request) (commands.Result, error) {
		input, _ := ledger.DecodeSourceEvent(req.Body)
		return ledger.ExecuteImportSetting(r.Context(), auth, req, ledger.CreateAutomaticSourceEvent(req.WorkspaceID, req.GenerationID, input))
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
		if !validUUID(workspace) {
			reject(w, fail(400, "bad_request"))
			return
		}
		query, queryError := url.ParseQuery(r.URL.RawQuery)
		before := query.Get("before_id")
		limit := 100
		invalidQuery := queryError != nil
		for key, values := range query {
			if (key != "before_id" && key != "limit") || len(values) != 1 || values[0] == "" {
				invalidQuery = true
			}
		}
		if value := query.Get("limit"); value != "" {
			parsed, e := strconv.Atoi(value)
			if e != nil || parsed < 1 || parsed > 100 {
				invalidQuery = true
			} else {
				limit = parsed
			}
		}
		if before != "" && !validUUID(before) {
			invalidQuery = true
		}
		if invalidQuery || (r.PathValue("id") != "" && r.URL.RawQuery != "") {
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
			items, next, err := ledger.SourceEventPage(r.Context(), tx, workspace, p.Profile.ID, before, limit)
			page := map[string]any{"items": items, "has_more": next != nil}
			if query.Has("limit") || query.Has("before_id") {
				page["next_before_id"] = next
			}
			result = page
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
