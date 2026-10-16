package httpapi

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	commands "accounting/backend/internal/sync"
	"net/http"

	"github.com/jackc/pgx/v5"
)

func MountPlanning(mux *http.ServeMux, auth *identity.Service, options AuthOptions) {
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/rules/preview", func(w http.ResponseWriter, r *http.Request) {
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
		var items []ledger.RuleBatch
		var more bool
		err = auth.WithSessionIsolation(r.Context(), token, pgx.RepeatableRead, func(tx pgx.Tx, p identity.Principal) error {
			if err := identity.RequireMembership(r.Context(), tx, p.Profile.ID, workspace); err != nil {
				return err
			}
			q, err := reportQuery(r)
			if err != nil {
				return err
			}
			if q.AccountID != "" || q.CategoryID != "" || q.TagID != "" || q.Status != "all" {
				return fail(400, "bad_request")
			}
			items, more, err = ledger.ReadRulePreview(r.Context(), tx, workspace, q.From, q.To)
			return err
		})
		if err != nil {
			reject(w, err)
			return
		}
		respond(w, 200, map[string]any{"batches": items, "has_more": more})
	})

	for _, resource := range []struct{ path, kind string }{{"budgets", "budget"}, {"rules", "rule"}} {
		kind, path := resource.kind, resource.path
		for _, method := range []string{"POST", "PUT", "DELETE"} {
			route := method + " /api/v1/workspaces/{workspace_id}/" + path
			if method != "POST" {
				route += "/{id}"
			}
			mux.HandleFunc(route, commandHTTPHandler(options, func(body []byte) error { _, err := ledger.DecodePlanning(body, kind, method); return err }, func(r *http.Request, request commands.Request) (commands.Result, error) {
				return ledger.PlanningExecute(r.Context(), auth, request, kind, r.PathValue("id"))
			}))
		}
		mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/"+path, func(w http.ResponseWriter, r *http.Request) {
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
			var items any
			err = auth.WithSessionIsolation(r.Context(), token, pgx.RepeatableRead, func(tx pgx.Tx, p identity.Principal) error {
				if err := identity.RequireMembership(r.Context(), tx, p.Profile.ID, workspace); err != nil {
					return err
				}
				if r.URL.RawQuery != "" {
					return fail(400, "bad_request")
				}
				var e error
				if kind == "budget" {
					items, e = ledger.ReadBudgets(r.Context(), tx, workspace)
				} else {
					items, e = ledger.ListPlanning(r.Context(), tx, workspace, kind)
				}
				return e
			})
			if err != nil {
				reject(w, err)
				return
			}
			respond(w, 200, map[string]any{"items": items, "next_cursor": nil})
		})
	}
}
