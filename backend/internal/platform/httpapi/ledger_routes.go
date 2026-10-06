package httpapi

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	commands "accounting/backend/internal/sync"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/jackc/pgx/v5"
)

func MountLedger(mux *http.ServeMux, runner *commands.Runner, options AuthOptions) {
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/accounts", mutationHandler(runner, options, func(body []byte) error { _, e := ledger.DecodeAccountCreate(body); return e }, func(r *http.Request, body []byte) commands.Command {
		v, _ := ledger.DecodeAccountCreate(body)
		return ledger.CreateAccount(r.PathValue("workspace_id"), v)
	}))
	mux.HandleFunc("PUT /api/v1/workspaces/{workspace_id}/accounts/{id}", mutationHandler(runner, options, func(body []byte) error { _, e := ledger.DecodeAccountUpdate(body); return e }, func(r *http.Request, body []byte) commands.Command {
		v, _ := ledger.DecodeAccountUpdate(body)
		return ledger.UpdateAccount(r.PathValue("workspace_id"), r.PathValue("id"), v)
	}))
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/accounts/{id}/adjustments", mutationHandler(runner, options, func(body []byte) error { _, e := ledger.DecodeAdjustmentCreate(body); return e }, func(r *http.Request, body []byte) commands.Command {
		v, _ := ledger.DecodeAdjustmentCreate(body)
		return ledger.CreateAdjustment(r.PathValue("workspace_id"), r.PathValue("id"), v)
	}))
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/transactions/classification", mutationHandler(runner, options, func(body []byte) error { _, e := ledger.DecodeBulkClassification(body); return e }, func(r *http.Request, body []byte) commands.Command {
		v, _ := ledger.DecodeBulkClassification(body)
		return ledger.ClassifyTransactions(r.PathValue("workspace_id"), v)
	}))
	for _, resource := range []struct{ path, kind string }{{"categories", "category"}, {"tags", "tag"}} {
		kind, path := resource.kind, resource.path
		mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/"+path, mutationHandler(runner, options, func(body []byte) error { _, e := ledger.DecodeClassification(body, kind, false); return e }, func(r *http.Request, body []byte) commands.Command {
			v, _ := ledger.DecodeClassification(body, kind, false)
			return ledger.ClassificationCommand(r.PathValue("workspace_id"), "", kind, false, v)
		}))
		mux.HandleFunc("PUT /api/v1/workspaces/{workspace_id}/"+path+"/{id}", mutationHandler(runner, options, func(body []byte) error { _, e := ledger.DecodeClassification(body, kind, true); return e }, func(r *http.Request, body []byte) commands.Command {
			v, _ := ledger.DecodeClassification(body, kind, true)
			return ledger.ClassificationCommand(r.PathValue("workspace_id"), r.PathValue("id"), kind, true, v)
		}))
	}
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/transactions", mutationHandler(runner, options, validateTransactionCreate, func(r *http.Request, body []byte) commands.Command {
		if transactionKind(body) == "transfer" {
			v, _ := ledger.DecodeTransferCreate(body)
			return ledger.CreateTransfer(r.PathValue("workspace_id"), v)
		}
		if transactionKind(body) == "refund" {
			v, _ := ledger.DecodeRefundCreate(body)
			return ledger.CreateRefund(r.PathValue("workspace_id"), v)
		}
		v, _ := ledger.DecodeBasic(body, false)
		return ledger.CreateBasic(r.PathValue("workspace_id"), v)
	}))
	mux.HandleFunc("PUT /api/v1/workspaces/{workspace_id}/transactions/{id}", mutationHandler(runner, options, func(body []byte) error {
		if transactionKind(body) == "adjustment" {
			_, e := ledger.DecodeAdjustmentReplace(body)
			return e
		}
		if transactionKind(body) == "transfer" {
			_, e := ledger.DecodeTransferReplace(body)
			return e
		}
		if transactionKind(body) == "refund" {
			_, e := ledger.DecodeRefundReplace(body)
			return e
		}
		_, e := ledger.DecodeBasic(body, true)
		return e
	}, func(r *http.Request, body []byte) commands.Command {
		if transactionKind(body) == "adjustment" {
			v, _ := ledger.DecodeAdjustmentReplace(body)
			return ledger.ReplaceAdjustment(r.PathValue("workspace_id"), r.PathValue("id"), v)
		}
		if transactionKind(body) == "transfer" {
			v, _ := ledger.DecodeTransferReplace(body)
			return ledger.ReplaceTransfer(r.PathValue("workspace_id"), r.PathValue("id"), v)
		}
		if transactionKind(body) == "refund" {
			v, _ := ledger.DecodeRefundReplace(body)
			return ledger.ReplaceRefund(r.PathValue("workspace_id"), r.PathValue("id"), v)
		}
		v, _ := ledger.DecodeBasic(body, true)
		return ledger.ReplaceBasic(r.PathValue("workspace_id"), r.PathValue("id"), v)
	}))
	mux.HandleFunc("DELETE /api/v1/workspaces/{workspace_id}/transactions/{id}", mutationHandler(runner, options, func(body []byte) error { _, e := ledger.DecodeTransactionDelete(body); return e }, func(r *http.Request, body []byte) commands.Command {
		v, _ := ledger.DecodeTransactionDelete(body)
		return ledger.DeleteTransaction(r.PathValue("workspace_id"), r.PathValue("id"), v)
	}))
	read := func(w http.ResponseWriter, r *http.Request) {
		token, _, e := credential(r)
		if e != nil {
			reject(w, e)
			return
		}
		workspace := r.PathValue("workspace_id")
		id := r.PathValue("id")
		if workspace != "" && !validUUID(workspace) || id != "" && !validUUID(id) {
			reject(w, fail(400, "bad_request"))
			return
		}
		var result any
		e = runner.Identity.WithSession(r.Context(), token, false, func(tx pgx.Tx, p identity.Principal) error {
			if workspace != "" {
				if e := identity.RequireMembership(r.Context(), tx, p.Profile.ID, workspace); e != nil {
					return e
				}
			}
			if r.URL.Path == "/api/v1/currencies" {
				if r.URL.RawQuery != "" {
					return fail(400, "bad_request")
				}
				rows, e := tx.Query(r.Context(), `SELECT code,scale FROM currencies ORDER BY code`)
				if e != nil {
					return e
				}
				defer rows.Close()
				items := []any{}
				for rows.Next() {
					var code string
					var scale int
					if e = rows.Scan(&code, &scale); e != nil {
						return e
					}
					items = append(items, map[string]any{"code": code, "scale": scale})
				}
				if e = rows.Err(); e != nil {
					return e
				}
				result = map[string]any{"items": items, "next_cursor": nil}
				return nil
			}
			resource := r.URL.Path
			kind := ""
			if strings.Contains(resource, "/categories") {
				kind = "category"
			} else if strings.Contains(resource, "/tags") {
				kind = "tag"
			}
			if strings.Contains(resource, "/transactions") {
				if id == "" {
					list, e := transactionList(r, tx, workspace, token)
					result = list
					return e
				}
				if r.URL.RawQuery != "" {
					return fail(400, "bad_request")
				}
				data, e := ledger.ReadTransaction(r.Context(), tx, workspace, id)
				result = data
				return e
			}
			if id != "" {
				if r.URL.RawQuery != "" {
					return fail(400, "bad_request")
				}
				var data json.RawMessage
				var e error
				if kind != "" {
					data, e = ledger.ReadClassification(r.Context(), tx, workspace, id, kind)
				} else {
					data, e = ledger.ReadAccount(r.Context(), tx, workspace, id)
				}
				result = data
				return e
			}
			q, e := ledgerAccountQuery(r, token)
			if e != nil {
				return e
			}
			var items []json.RawMessage
			var ids []string
			if kind != "" {
				items, ids, e = ledger.ListClassification(r.Context(), tx, workspace, q.after, q.archived, kind, q.limit+1)
			} else {
				items, ids, e = ledger.ListAccounts(r.Context(), tx, workspace, q.after, q.archived, q.limit+1)
			}
			if e != nil {
				return e
			}
			var next any
			if len(items) > q.limit {
				items = items[:q.limit]
				next = signedCursor(token, q.scope+ids[q.limit-1])
			}
			result = map[string]any{"items": items, "next_cursor": next}
			return nil
		})
		if e != nil {
			reject(w, e)
			return
		}
		respond(w, 200, result)
	}
	for _, path := range []string{"categories", "tags"} {
		mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/"+path, read)
		mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/"+path+"/{id}", read)
	}
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/transactions/{id}", read)
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/transactions", read)
	mux.HandleFunc("GET /api/v1/currencies", read)
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/accounts", read)
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/accounts/{id}", read)
}

type accountQuery struct {
	limit                  int
	after, archived, scope string
}

func ledgerAccountQuery(r *http.Request, token string) (accountQuery, error) {
	out := accountQuery{limit: 50, archived: "exclude"}
	q, e := url.ParseQuery(r.URL.RawQuery)
	if e != nil {
		return out, fail(400, "bad_request")
	}
	for key, values := range q {
		if len(values) != 1 || (key != "limit" && key != "cursor" && key != "archived") {
			return out, fail(400, "bad_request")
		}
	}
	if values, ok := q["limit"]; ok {
		n, e := strconv.Atoi(values[0])
		if e != nil || n < 1 || n > 100 {
			return out, fail(400, "bad_request")
		}
		out.limit = n
	}
	if values, ok := q["archived"]; ok {
		out.archived = values[0]
		if out.archived != "exclude" && out.archived != "include" && out.archived != "only" {
			return out, fail(400, "bad_request")
		}
	}
	out.scope = strings.ToLower(r.URL.Path) + ":" + out.archived + ":"
	if values, ok := q["cursor"]; ok {
		cursor := values[0]
		if len(cursor) == 0 || len(cursor) > 2048 {
			return out, fail(400, "cursor_invalid")
		}
		parts := strings.Split(cursor, ".")
		if len(parts) != 2 {
			return out, fail(400, "cursor_invalid")
		}
		b, e := base64.RawURLEncoding.DecodeString(parts[0])
		if e != nil {
			return out, fail(400, "cursor_invalid")
		}
		value := string(b)
		if !strings.HasPrefix(value, out.scope) {
			return out, fail(400, "cursor_invalid")
		}
		out.after = strings.TrimPrefix(value, out.scope)
		if !validUUID(out.after) || subtle.ConstantTimeCompare([]byte(cursor), []byte(signedCursor(token, value))) != 1 {
			return out, fail(400, "cursor_invalid")
		}
	}
	return out, nil
}

func transactionKind(body []byte) string {
	var discriminator struct {
		Kind string `json:"kind"`
	}
	if json.Unmarshal(body, &discriminator) != nil {
		return ""
	}
	return discriminator.Kind
}
func validateTransactionCreate(body []byte) error {
	if transactionKind(body) == "refund" {
		_, e := ledger.DecodeRefundCreate(body)
		return e
	}
	if transactionKind(body) == "transfer" {
		_, e := ledger.DecodeTransferCreate(body)
		return e
	}
	_, e := ledger.DecodeBasic(body, false)
	return e
}
