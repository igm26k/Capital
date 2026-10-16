package httpapi

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	"net/http"
	"net/url"
	"strings"

	"github.com/jackc/pgx/v5"
)

func MountReports(mux *http.ServeMux, auth *identity.Service) {
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/reports", func(w http.ResponseWriter, r *http.Request) {
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
		var result ledger.Report
		err = auth.WithSessionIsolation(r.Context(), token, pgx.RepeatableRead, func(tx pgx.Tx, p identity.Principal) error {
			if err := identity.RequireMembership(r.Context(), tx, p.Profile.ID, workspace); err != nil {
				return err
			}
			query, err := reportQuery(r)
			if err != nil {
				return err
			}
			result, err = ledger.ReadReport(r.Context(), tx, workspace, query)
			return err
		})
		if err != nil {
			reject(w, err)
			return
		}
		respond(w, 200, result)
	})
}

func reportQuery(r *http.Request) (ledger.ReportQuery, error) {
	out := ledger.ReportQuery{Status: "all"}
	values, err := url.ParseQuery(r.URL.RawQuery)
	if err != nil {
		return out, fail(400, "bad_request")
	}
	allowed := map[string]bool{"from": true, "to": true, "account_id": true, "category_id": true, "tag_id": true, "status": true}
	for k, v := range values {
		if !allowed[k] || len(v) != 1 {
			return out, fail(400, "bad_request")
		}
	}
	if !strings.HasSuffix(values.Get("from"), "Z") || !strings.HasSuffix(values.Get("to"), "Z") {
		return out, fail(400, "bad_request")
	}
	out.From, err = ledger.ParseInstant(values.Get("from"))
	if err != nil {
		return out, fail(400, "bad_request")
	}
	out.To, err = ledger.ParseInstant(values.Get("to"))
	if err != nil {
		return out, fail(400, "bad_request")
	}
	for k, target := range map[string]*string{"account_id": &out.AccountID, "category_id": &out.CategoryID, "tag_id": &out.TagID} {
		if values.Has(k) {
			value := strings.ToLower(values.Get(k))
			if !validUUID(value) {
				return out, fail(400, "bad_request")
			}
			*target = value
		}
	}
	if values.Has("status") {
		out.Status = values.Get("status")
	}
	return out, nil
}
