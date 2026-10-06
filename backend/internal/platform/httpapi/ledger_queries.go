package httpapi

import (
	"accounting/backend/internal/ledger"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"github.com/jackc/pgx/v5"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

func transactionList(r *http.Request, tx pgx.Tx, workspace, token string) (any, error) {
	q, scope, limit, e := transactionQuery(r, token)
	if e != nil {
		return nil, e
	}
	items, e := ledger.ListTransactions(r.Context(), tx, workspace, q)
	if e != nil {
		return nil, e
	}
	var next any
	if len(items) > limit {
		items = items[:limit]
		var last struct {
			ID       string `json:"id"`
			Occurred string `json:"occurred_at"`
		}
		if e = json.Unmarshal(items[limit-1], &last); e != nil {
			return nil, e
		}
		next = signedCursor(token, scope+last.Occurred+"|"+last.ID)
	}
	return map[string]any{"items": items, "next_cursor": next}, nil
}
func transactionQuery(r *http.Request, token string) (ledger.TransactionQuery, string, int, error) {
	out := ledger.TransactionQuery{Limit: 51}
	limit := 50
	values, e := url.ParseQuery(r.URL.RawQuery)
	if e != nil {
		return out, "", 0, fail(400, "bad_request")
	}
	allowed := map[string]bool{"limit": true, "cursor": true, "account_id": true, "category_id": true, "tag_id": true, "from": true, "to": true, "kind": true, "status": true, "q": true}
	for k, v := range values {
		if !allowed[k] || len(v) != 1 {
			return out, "", 0, fail(400, "bad_request")
		}
	}
	if values.Has("limit") {
		n, e := strconv.Atoi(values.Get("limit"))
		if e != nil || n < 1 || n > 100 {
			return out, "", 0, fail(400, "bad_request")
		}
		limit = n
	}
	out.Limit = limit + 1
	for key, target := range map[string]*string{"account_id": &out.AccountID, "category_id": &out.CategoryID, "tag_id": &out.TagID} {
		if values.Has(key) {
			id := strings.ToLower(values.Get(key))
			if !validUUID(id) {
				return out, "", 0, fail(400, "bad_request")
			}
			*target = id
			values.Set(key, id)
		}
	}
	for key, target := range map[string]**time.Time{"from": &out.From, "to": &out.To} {
		if values.Has(key) {
			s := values.Get(key)
			t, e := ledger.ParseInstant(s)
			if e != nil || !strings.HasSuffix(s, "Z") {
				return out, "", 0, fail(400, "bad_request")
			}
			*target = &t
			values.Set(key, t.Format(time.RFC3339Nano))
		}
	}
	if out.From != nil && out.To != nil && !out.From.Before(*out.To) {
		return out, "", 0, fail(400, "bad_request")
	}
	if values.Has("kind") {
		out.Kind = values.Get("kind")
		if out.Kind != "opening" && out.Kind != "expense" && out.Kind != "income" && out.Kind != "transfer" && out.Kind != "refund" && out.Kind != "adjustment" {
			return out, "", 0, fail(400, "bad_request")
		}
	}
	if values.Has("status") {
		out.Status = values.Get("status")
		if out.Status != "posted" && out.Status != "pending" {
			return out, "", 0, fail(400, "bad_request")
		}
	}
	out.Search = values.Get("q")
	if !utf8.ValidString(out.Search) || utf8.RuneCountInString(out.Search) > 200 || strings.ContainsRune(out.Search, 0) {
		return out, "", 0, fail(400, "bad_request")
	}
	if out.Search == "" {
		values.Del("q")
	}
	cursor, hasCursor := values["cursor"]
	values.Del("cursor")
	values.Del("limit")
	sum := sha256.Sum256([]byte(values.Encode()))
	scope := strings.ToLower(r.URL.Path) + ":" + hex.EncodeToString(sum[:]) + ":"
	if hasCursor {
		encoded := cursor[0]
		parts := strings.Split(encoded, ".")
		if len(encoded) == 0 || len(encoded) > 2048 || len(parts) != 2 {
			return out, "", 0, fail(400, "cursor_invalid")
		}
		b, e := base64.RawURLEncoding.DecodeString(parts[0])
		value := string(b)
		if e != nil || !strings.HasPrefix(value, scope) || subtle.ConstantTimeCompare([]byte(encoded), []byte(signedCursor(token, value))) != 1 {
			return out, "", 0, fail(400, "cursor_invalid")
		}
		after := strings.Split(strings.TrimPrefix(value, scope), "|")
		if len(after) != 2 || !validUUID(after[1]) {
			return out, "", 0, fail(400, "cursor_invalid")
		}
		t, e := ledger.ParseInstant(after[0])
		if e != nil || !strings.HasSuffix(after[0], "Z") {
			return out, "", 0, fail(400, "cursor_invalid")
		}
		out.AfterDate = &t
		out.AfterID = after[1]
	}
	return out, scope, limit, nil
}
