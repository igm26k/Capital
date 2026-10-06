package integration

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/platform"
	commands "accounting/backend/internal/sync"
	"accounting/backend/migrate"
	"accounting/backend/migrations"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type accessLog struct {
	mu sync.Mutex
	bytes.Buffer
}

func (l *accessLog) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.Buffer.Write(p)
}
func (l *accessLog) text() string { l.mu.Lock(); defer l.mu.Unlock(); return l.Buffer.String() }

type accessObserved struct {
	Status int             `json:"status"`
	Schema string          `json:"schema"`
	Body   json.RawMessage `json:"body"`
	Method string          `json:"method"`
	Path   string          `json:"path"`
}
type accessFixture struct {
	t        *testing.T
	ctx      context.Context
	db       *pgxpool.Pool
	auth     *identity.Service
	a, b     identity.Auth
	keys     *commands.CursorKeys
	server   *httptest.Server
	logs     *accessLog
	counter  atomic.Int64
	mu       sync.Mutex
	observed []accessObserved
}

func newAccessFixture(t *testing.T) *accessFixture {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 180*time.Second)
	t.Cleanup(cancel)
	cfg, e := pgxpool.ParseConfig(databaseURL)
	if e != nil || cfg.ConnConfig.Database != "accounting_test" {
		t.Fatal("requires isolated accounting_test")
	}
	admin, e := pgxpool.NewWithConfig(ctx, cfg)
	if e != nil {
		t.Fatal("database connection failed")
	}
	t.Cleanup(admin.Close)
	schema := fmt.Sprintf("access_%d", time.Now().UnixNano())
	ident := pgx.Identifier{schema}.Sanitize()
	if _, e = admin.Exec(ctx, "CREATE SCHEMA "+ident); e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() {
		clean, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		if _, e := admin.Exec(clean, "DROP SCHEMA "+ident+" CASCADE"); e != nil {
			t.Error("schema cleanup failed")
		}
	})
	scoped := cfg.Copy()
	scoped.ConnConfig.RuntimeParams["search_path"] = schema
	db, e := pgxpool.NewWithConfig(ctx, scoped)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(db.Close)
	if _, e = migrate.Apply(ctx, db, migrations.Files); e != nil {
		t.Fatal(e)
	}
	auth, e := identity.New(db, 2)
	if e != nil {
		t.Fatal(e)
	}
	user, e := auth.Register(ctx, "access@example.test", "synthetic transfer password", "advanced", "UTC")
	if e != nil {
		t.Fatal(e)
	}
	other, e := auth.Register(ctx, "other-access@example.test", "synthetic other password", "other", "UTC")
	if e != nil {
		t.Fatal(e)
	}
	keys, e := commands.NewCursorKeys("v1", map[string][]byte{"v1": bytes.Repeat([]byte{71}, 32)})
	if e != nil {
		t.Fatal(e)
	}
	logs := &accessLog{}
	handler, e := platform.NewAPIHandler(db, platform.Config{Environment: "test", CursorKeys: keys, PublicOrigin: "https://advanced.example.test", AuthHashConcurrency: 2}, slog.New(slog.NewTextHandler(logs, nil)))
	if e != nil {
		t.Fatal(e)
	}
	server := httptest.NewTLSServer(handler)
	t.Cleanup(server.Close)

	return &accessFixture{t: t, ctx: ctx, db: db, auth: auth, a: user, b: other, keys: keys, server: server, logs: logs, observed: []accessObserved{}}
}

func (f *accessFixture) id() string {
	return fmt.Sprintf("a3000000-0000-4000-8000-%012d", f.counter.Add(1))
}
func accessRoot(a identity.Auth) string { return "/api/v1/workspaces/" + a.Workspace.ID }

type accessRequest struct {
	method, path string
	body         any
	actor        identity.Auth
	key, cookie  string
	headers      map[string]string
}
type accessResponse struct {
	status int
	raw    json.RawMessage
	body   map[string]any
	header http.Header
	err    error
}

var accessUUID = regexp.MustCompile(`[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}`)

func accessTemplate(path string) string {
	u, _ := url.Parse(path)
	path = strings.TrimPrefix(u.Path, "/api/v1")
	path = accessUUID.ReplaceAllString(path, "{id}")
	return strings.Replace(path, "/workspaces/{id}/", "/workspaces/{workspace_id}/", 1)
}
func accessSchema(method, path string, status int) string {
	if status >= 400 {
		return "Error"
	}
	p := accessTemplate(path)
	switch p {
	case "/me":
		return "Profile"
	case "/sessions":
		return "SessionList"
	case "/sessions/{id}", "/auth/logout":
		return "Acknowledgement"
	case "/auth/register", "/auth/login", "/auth/session", "/auth/renew":
		return "AuthResult"
	case "/workspaces":
		return "WorkspaceList"
	case "/currencies":
		return "CurrencyList"
	}
	if strings.Contains(p, "/actions/") {
		return "ActionOutcome"
	}
	if strings.HasSuffix(p, "/sync/changes") {
		return "SyncChanges"
	}
	if strings.HasSuffix(p, "/pages") {
		return "SnapshotPage"
	}
	if strings.Contains(p, "/sync/snapshots") {
		return "Snapshot"
	}
	if method != "GET" {
		return "MutationResult"
	}
	for plural, singular := range map[string]string{"accounts": "Account", "transactions": "Transaction", "categories": "Category", "tags": "Tag"} {
		if strings.HasSuffix(p, "/"+plural) {
			return singular + "List"
		}
		if strings.HasSuffix(p, "/"+plural+"/{id}") {
			return singular
		}
	}
	return "Error"
}
func (f *accessFixture) send(q accessRequest) accessResponse {
	raw, e := json.Marshal(q.body)
	if e != nil {
		return accessResponse{err: e}
	}
	if q.body == nil {
		raw = nil
	}
	request, e := http.NewRequest(q.method, f.server.URL+q.path, bytes.NewReader(raw))
	if e != nil {
		return accessResponse{err: e}
	}
	if q.actor.Credential != "" {
		request.Header.Set("Authorization", "Bearer "+q.actor.Credential)
	}
	if q.cookie != "" {
		request.AddCookie(&http.Cookie{Name: "__Host-accounting_session", Value: q.cookie})
	}
	if q.body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if q.key != "" {
		request.Header.Set("Idempotency-Key", q.key)
	}
	if strings.HasPrefix(q.path, "/api/v1/workspaces/") && q.method != "GET" && !strings.Contains(q.path, "/sync/snapshots") {
		request.Header.Set("X-Sync-Generation", q.actor.Workspace.Generation)
	}
	for k, v := range q.headers {
		if k == "Authorization" && v == "" {
			request.Header.Del(k)
		} else {
			request.Header.Set(k, v)
		}
	}
	response, e := f.server.Client().Do(request)
	if e != nil {
		return accessResponse{err: e}
	}
	defer response.Body.Close()
	data, e := io.ReadAll(response.Body)
	if e != nil {
		return accessResponse{err: e}
	}
	result := accessResponse{status: response.StatusCode, raw: data, header: response.Header}
	e = json.Unmarshal(data, &result.body)
	if e != nil {
		return accessResponse{err: e}
	}
	f.mu.Lock()
	f.observed = append(f.observed, accessObserved{result.status, accessSchema(q.method, q.path, result.status), data, q.method, accessTemplate(q.path)})
	f.mu.Unlock()
	return result
}
func (f *accessFixture) expect(r accessResponse, status int, code string) {
	f.t.Helper()
	if r.err != nil {
		f.t.Fatal("HTTP unavailable", r.err)
	}
	if r.status != status || code != "" && r.body["code"] != code {
		f.t.Fatalf("expected %d %s got %d %v", status, code, r.status, r.body["code"])
	}
	if status == 404 || status == 401 || status == 403 {
		if len(r.body["current_versions"].([]any)) != 0 || len(r.body["field_errors"].([]any)) != 0 {
			f.t.Fatal("unauthorized response disclosed metadata")
		}
	}
}
func (f *accessFixture) call(q accessRequest, status int, code string) accessResponse {
	f.t.Helper()
	r := f.send(q)
	f.expect(r, status, code)
	return r
}
func accessClone(m map[string]any) map[string]any {
	raw, _ := json.Marshal(m)
	var copy map[string]any
	json.Unmarshal(raw, &copy)
	return copy
}
func (f *accessFixture) basic(account, kind string) map[string]any {
	return map[string]any{"id": f.id(), "kind": kind, "account_id": account, "amount_minor": "100", "occurred_at": "2026-10-01T00:00:00Z", "occurred_timezone": "UTC", "payee": "Synthetic access", "note": "Access note <script>window.accessSentinel=1</script>", "allocations": []any{map[string]any{"id": f.id(), "category_id": nil, "amount_minor": "100"}}, "tag_ids": []string{}}
}
func (f *accessFixture) transfer(source, target string) map[string]any {
	return map[string]any{"id": f.id(), "kind": "transfer", "source_account_id": source, "target_account_id": target, "source_amount_minor": "100", "target_amount_minor": "100", "occurred_at": "2026-10-01T00:00:00Z", "occurred_timezone": "UTC", "note": "", "payee": "", "tag_ids": []string{}, "rate": nil, "fee": nil}
}
func (f *accessFixture) refund(account, parent, part string) map[string]any {
	return map[string]any{"id": f.id(), "kind": "refund", "account_id": account, "amount_minor": "1", "parent_transaction_id": parent, "expected_parent_version": "1", "expected_transfer_version": nil, "occurred_at": "2026-10-01T00:00:00Z", "occurred_timezone": "UTC", "note": "", "payee": "", "tag_ids": []string{}, "allocations": []any{map[string]any{"id": f.id(), "original_allocation_id": part, "amount_minor": "1"}}}
}
func (f *accessFixture) account(a identity.Auth) string {
	id := f.id()
	f.call(accessRequest{method: "POST", path: accessRoot(a) + "/accounts", actor: a, key: f.id(), body: map[string]any{"id": id, "name": "Synthetic access account", "type": "bank", "currency": "EUR", "opened_at": "2026-01-01T00:00:00Z", "occurred_timezone": "UTC", "opening_balance_minor": "10000"}}, 201, "")
	return id
}
func (f *accessFixture) digest() [32]byte {
	tables := []string{"accounts", "categories", "tags", "transactions", "entries", "allocations", "transaction_tags", "actions", "sync_heads", "sync_groups", "sync_changes", "audit_events", "sync_snapshots", "sync_snapshot_items"}
	parts := []string{}
	for _, table := range tables {
		parts = append(parts, "'"+table+"',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb) FROM "+table+" r)")
	}
	var raw []byte
	if e := f.db.QueryRow(f.ctx, "SELECT jsonb_build_object("+strings.Join(parts, ",")+")").Scan(&raw); e != nil {
		f.t.Fatal(e)
	}
	return sha256.Sum256(raw)
}
func (f *accessFixture) save() {
	if file := os.Getenv("ACCESS_RESPONSE_ARTIFACT"); file != "" {
		f.saveTo(file)
	}
}
func (f *accessFixture) saveTo(file string) {
	f.t.Helper()
	f.mu.Lock()
	raw, e := json.Marshal(f.observed)
	f.mu.Unlock()
	if e != nil {
		f.t.Fatal(e)
	}
	if e = os.MkdirAll(filepath.Dir(file), 0700); e != nil {
		f.t.Fatal(e)
	}
	if e = os.WriteFile(file, raw, 0600); e != nil {
		f.t.Fatal(e)
	}
}

func TestAccess(t *testing.T) {
	f := newAccessFixture(t)
	a, b := f.a, f.b
	ra, rb := accessRoot(a), accessRoot(b)
	type resources struct {
		account, target, category, tag, expense, part, refund, transfer, adjustment, action string
		basic, refundBody, transferBody                                                     map[string]any
		snapshot                                                                            commands.Snapshot
	}
	seed := func(actor identity.Auth) resources {
		r := resources{account: f.account(actor), target: f.account(actor), category: f.id(), tag: f.id()}
		root := accessRoot(actor)
		f.call(accessRequest{method: "POST", path: root + "/categories", body: map[string]any{"id": r.category, "name": "Scope category", "parent_id": nil}, actor: actor, key: f.id()}, 201, "")
		f.call(accessRequest{method: "POST", path: root + "/tags", body: map[string]any{"id": r.tag, "name": "Scope tag"}, actor: actor, key: f.id()}, 201, "")
		r.basic = f.basic(r.account, "expense")
		r.expense = r.basic["id"].(string)
		r.part = r.basic["allocations"].([]any)[0].(map[string]any)["id"].(string)
		r.action = f.id()
		f.call(accessRequest{method: "POST", path: root + "/transactions", body: r.basic, actor: actor, key: r.action}, 201, "")
		r.refundBody = f.refund(r.account, r.expense, r.part)
		r.refund = r.refundBody["id"].(string)
		f.call(accessRequest{method: "POST", path: root + "/transactions", body: r.refundBody, actor: actor, key: f.id()}, 201, "")
		r.transferBody = f.transfer(r.account, r.target)
		r.transfer = r.transferBody["id"].(string)
		f.call(accessRequest{method: "POST", path: root + "/transactions", body: r.transferBody, actor: actor, key: f.id()}, 201, "")
		account := f.call(accessRequest{method: "GET", path: root + "/accounts/" + r.account, actor: actor}, 200, "")
		r.adjustment = f.id()
		f.call(accessRequest{method: "POST", path: root + "/accounts/" + r.account + "/adjustments", body: map[string]any{"id": r.adjustment, "expected_balance_version": account.body["balance_version"], "target_balance_minor": "12000", "reason": "Access fixture", "note": "", "occurred_timezone": "UTC"}, actor: actor, key: f.id()}, 201, "")
		sid := f.id()
		snapshot := f.call(accessRequest{method: "POST", path: root + "/sync/snapshots", body: map[string]any{"id": sid}, actor: actor, key: sid}, 201, "")
		if e := json.Unmarshal(snapshot.raw, &r.snapshot); e != nil {
			t.Fatal(e)
		}
		return r
	}
	own, foreign := seed(a), seed(b)
	basicPut := func(body map[string]any, version string) map[string]any {
		out := accessClone(body)
		delete(out, "id")
		out["expected_version"] = version
		if out["kind"] == "expense" {
			out["expected_parent_version"] = nil
		}
		return out
	}
	transferPut := accessClone(foreign.transferBody)
	delete(transferPut, "id")
	transferPut["expected_version"] = "1"
	transferPut["expected_fee_version"] = nil
	refundPut := accessClone(foreign.refundBody)
	delete(refundPut, "id")
	refundPut["expected_version"] = "1"
	refundPut["expected_parent_version"] = "2"
	adjustment := map[string]any{"id": f.id(), "expected_balance_version": "1", "target_balance_minor": "10001", "reason": "Scope", "note": "", "occurred_timezone": "UTC"}
	snapshotID := f.id()
	// Every workspace route has real valid wire input. 401 verifies the route is
	// mounted (a missing handler would be 404), then 404 tests membership first.
	routes := []accessRequest{
		{method: "GET", path: rb + "/accounts"}, {method: "GET", path: rb + "/accounts/" + foreign.account},
		{method: "POST", path: rb + "/accounts", body: map[string]any{"id": f.id(), "name": "Denied", "type": "bank", "currency": "EUR", "opened_at": "2026-01-01T00:00:00Z", "occurred_timezone": "UTC", "opening_balance_minor": "0"}},
		{method: "PUT", path: rb + "/accounts/" + foreign.account, body: map[string]any{"name": "Denied", "type": "bank", "archived": false, "expected_version": "999"}},
		{method: "GET", path: rb + "/categories"}, {method: "GET", path: rb + "/categories/" + foreign.category},
		{method: "POST", path: rb + "/categories", body: map[string]any{"id": f.id(), "name": "Denied", "parent_id": nil}},
		{method: "PUT", path: rb + "/categories/" + foreign.category, body: map[string]any{"name": "Denied", "parent_id": nil, "archived": false, "expected_version": "999"}},
		{method: "GET", path: rb + "/tags"}, {method: "GET", path: rb + "/tags/" + foreign.tag},
		{method: "POST", path: rb + "/tags", body: map[string]any{"id": f.id(), "name": "Denied"}},
		{method: "PUT", path: rb + "/tags/" + foreign.tag, body: map[string]any{"name": "Denied", "archived": false, "expected_version": "999"}},
		{method: "POST", path: rb + "/accounts/" + foreign.account + "/adjustments", body: adjustment},
		{method: "GET", path: rb + "/transactions"}, {method: "GET", path: rb + "/transactions/" + foreign.expense},
		{method: "POST", path: rb + "/transactions", body: f.basic(foreign.account, "expense")},
		{method: "POST", path: rb + "/transactions", body: f.basic(foreign.account, "income")},
		{method: "POST", path: rb + "/transactions", body: f.transfer(foreign.account, foreign.target)},
		{method: "POST", path: rb + "/transactions", body: f.refund(foreign.account, foreign.expense, foreign.part)},
		{method: "PUT", path: rb + "/transactions/" + foreign.expense, body: basicPut(foreign.basic, "999")},
		{method: "PUT", path: rb + "/transactions/" + foreign.transfer, body: transferPut},
		{method: "PUT", path: rb + "/transactions/" + foreign.refund, body: refundPut},
		{method: "PUT", path: rb + "/transactions/" + foreign.adjustment, body: map[string]any{"kind": "adjustment", "expected_version": "999", "note": "Denied"}},
		{method: "DELETE", path: rb + "/transactions/" + foreign.adjustment, body: map[string]any{"expected_version": "999", "related_versions": []any{}}},
		{method: "POST", path: rb + "/transactions/classification", body: map[string]any{"items": []any{map[string]any{"id": foreign.expense, "expected_version": "999"}}, "category_id": nil, "tag_ids": []string{}}},
		{method: "GET", path: rb + "/sync/changes?cursor=" + url.QueryEscape(foreign.snapshot.Resume)},
		{method: "POST", path: rb + "/sync/snapshots", body: map[string]any{"id": snapshotID}, key: snapshotID},
		{method: "GET", path: rb + "/sync/snapshots/" + foreign.snapshot.ID},
		{method: "GET", path: rb + "/sync/snapshots/" + foreign.snapshot.ID + "/pages?page_token=" + url.QueryEscape(foreign.snapshot.First)},
		{method: "GET", path: rb + "/actions/" + foreign.action},
	}
	before := f.digest()
	for _, q := range routes {
		q.actor = identity.Auth{}
		if q.key == "" && q.method != "GET" {
			q.key = f.id()
		}
		f.call(q, 401, "unauthenticated")
		q.actor = a
		f.call(q, 404, "not_found")
	}
	if f.digest() != before {
		t.Fatal("unauthorized workspace request wrote financial/sync state")
	}
	// Membership in both workspaces still does not grant cross-workspace references.
	if _, e := f.db.Exec(f.ctx, `INSERT INTO memberships(workspace_id,user_id,role) VALUES($1,$2,'member')`, b.Workspace.ID, a.Profile.ID); e != nil {
		t.Fatal(e)
	}
	for _, q := range routes {
		if q.method == "GET" && (strings.Contains(q.path, "/accounts/"+foreign.account) || strings.Contains(q.path, "/categories/"+foreign.category) || strings.Contains(q.path, "/tags/"+foreign.tag) || strings.Contains(q.path, "/transactions/"+foreign.expense)) || q.method == "PUT" || q.method == "DELETE" || strings.Contains(q.path, "/accounts/"+foreign.account+"/adjustments") {
			q.path = strings.Replace(q.path, rb, ra, 1)
			q.actor = a
			if q.method != "GET" {
				q.key = f.id()
			}
			f.call(q, 404, "not_found")
		}
	}
	nested := []accessRequest{}
	for _, kind := range []string{"expense", "income"} {
		for _, field := range []string{"account", "category", "tag"} {
			body := f.basic(own.account, kind)
			switch field {
			case "account":
				body["account_id"] = foreign.account
			case "category":
				body["allocations"].([]any)[0].(map[string]any)["category_id"] = foreign.category
			case "tag":
				body["tag_ids"] = []string{foreign.tag}
			}
			nested = append(nested, accessRequest{method: "POST", path: ra + "/transactions", body: body})
		}
	}
	for _, field := range []string{"source_account_id", "target_account_id", "tag_ids", "fee_account", "fee_category", "fee_tag"} {
		body := f.transfer(own.account, own.target)
		switch field {
		case "source_account_id", "target_account_id":
			body[field] = foreign.account
		case "tag_ids":
			body[field] = []string{foreign.tag}
		default:
			fee := map[string]any{"id": f.id(), "account_id": own.account, "amount_minor": "1", "note": "", "tag_ids": []string{}, "allocations": []any{map[string]any{"id": f.id(), "category_id": nil, "amount_minor": "1"}}}
			switch field {
			case "fee_account":
				fee["account_id"] = foreign.account
			case "fee_category":
				fee["allocations"].([]any)[0].(map[string]any)["category_id"] = foreign.category
			case "fee_tag":
				fee["tag_ids"] = []string{foreign.tag}
			}
			body["fee"] = fee
		}
		nested = append(nested, accessRequest{method: "POST", path: ra + "/transactions", body: body})
	}
	for _, field := range []string{"account_id", "parent_transaction_id", "original_allocation_id", "tag_ids"} {
		body := f.refund(own.account, own.expense, own.part)
		body["expected_parent_version"] = "2"
		switch field {
		case "account_id":
			body[field] = foreign.account
		case "parent_transaction_id":
			body[field] = foreign.expense
		case "original_allocation_id":
			body["allocations"].([]any)[0].(map[string]any)[field] = foreign.part
		case "tag_ids":
			body[field] = []string{foreign.tag}
		}
		nested = append(nested, accessRequest{method: "POST", path: ra + "/transactions", body: body})
	}
	nested = append(nested,
		accessRequest{method: "POST", path: ra + "/categories", body: map[string]any{"id": f.id(), "name": "Foreign parent", "parent_id": foreign.category}},
		accessRequest{method: "PUT", path: ra + "/categories/" + own.category, body: map[string]any{"name": "Foreign parent", "parent_id": foreign.category, "archived": false, "expected_version": "1"}},
		accessRequest{method: "POST", path: ra + "/transactions/classification", body: map[string]any{"items": []any{map[string]any{"id": foreign.expense, "expected_version": "999"}}, "category_id": nil, "tag_ids": []string{}}},
	)
	// Use own income without refund dependencies for classification foreign refs.
	income := f.basic(own.account, "income")
	f.call(accessRequest{method: "POST", path: ra + "/transactions", body: income, actor: a, key: f.id()}, 201, "")
	for _, body := range []map[string]any{
		{"items": []any{map[string]any{"id": income["id"], "expected_version": "1"}}, "category_id": foreign.category, "tag_ids": []string{}},
		{"items": []any{map[string]any{"id": income["id"], "expected_version": "1"}}, "category_id": nil, "tag_ids": []string{foreign.tag}},
	} {
		nested = append(nested, accessRequest{method: "POST", path: ra + "/transactions/classification", body: body})
	}
	// Replacement handlers also validate newly assigned references. Exercise the
	// independent fee path as well as parent transfer replacement.
	feeID, feePart := f.id(), f.id()
	feeBody := map[string]any{"id": feeID, "account_id": own.account, "amount_minor": "1", "note": "", "tag_ids": []string{}, "allocations": []any{map[string]any{"id": feePart, "category_id": nil, "amount_minor": "1"}}}
	addFee := accessClone(own.transferBody)
	delete(addFee, "id")
	addFee["expected_version"] = "1"
	addFee["expected_fee_version"] = nil
	addFee["fee"] = feeBody
	f.call(accessRequest{method: "PUT", path: ra + "/transactions/" + own.transfer, body: addFee, actor: a, key: f.id()}, 200, "")
	for _, field := range []string{"account", "category", "tag"} {
		body := basicPut(income, "1")
		switch field {
		case "account":
			body["account_id"] = foreign.account
		case "category":
			body["allocations"].([]any)[0].(map[string]any)["category_id"] = foreign.category
		case "tag":
			body["tag_ids"] = []string{foreign.tag}
		}
		nested = append(nested, accessRequest{method: "PUT", path: ra + "/transactions/" + income["id"].(string), body: body})
		fee := f.basic(own.account, "expense")
		delete(fee, "id")
		fee["amount_minor"] = "1"
		fee["note"] = ""
		fee["expected_version"] = "1"
		fee["expected_parent_version"] = "2"
		fee["allocations"] = []any{map[string]any{"id": feePart, "category_id": nil, "amount_minor": "1"}}
		switch field {
		case "account":
			fee["account_id"] = foreign.account
		case "category":
			fee["allocations"].([]any)[0].(map[string]any)["category_id"] = foreign.category
		case "tag":
			fee["tag_ids"] = []string{foreign.tag}
		}
		nested = append(nested, accessRequest{method: "PUT", path: ra + "/transactions/" + feeID, body: fee})
	}
	for _, field := range []string{"source_account_id", "target_account_id", "tag_ids", "fee_account", "fee_category", "fee_tag"} {
		body := accessClone(addFee)
		body["expected_version"] = "2"
		body["expected_fee_version"] = "1"
		switch field {
		case "source_account_id", "target_account_id":
			body[field] = foreign.account
		case "tag_ids":
			body[field] = []string{foreign.tag}
		default:
			fee := body["fee"].(map[string]any)
			switch field {
			case "fee_account":
				fee["account_id"] = foreign.account
			case "fee_category":
				fee["allocations"].([]any)[0].(map[string]any)["category_id"] = foreign.category
			case "fee_tag":
				fee["tag_ids"] = []string{foreign.tag}
			}
		}
		nested = append(nested, accessRequest{method: "PUT", path: ra + "/transactions/" + own.transfer, body: body})
	}
	for _, field := range []string{"account_id", "original_allocation_id", "tag_ids"} {
		body := accessClone(own.refundBody)
		delete(body, "id")
		body["expected_version"] = "1"
		body["expected_parent_version"] = "2"
		switch field {
		case "account_id":
			body[field] = foreign.account
		case "original_allocation_id":
			body["allocations"].([]any)[0].(map[string]any)[field] = foreign.part
		case "tag_ids":
			body[field] = []string{foreign.tag}
		}
		nested = append(nested, accessRequest{method: "PUT", path: ra + "/transactions/" + own.refund, body: body})
	}
	for field, id := range map[string]string{"account_id": foreign.account, "category_id": foreign.category, "tag_id": foreign.tag} {
		nested = append(nested, accessRequest{method: "GET", path: ra + "/transactions?" + field + "=" + id})
	}
	// Same actor has legitimate access to the second workspace; only the saved
	// list cursor is rejected when moved across workspace scope.
	list := f.call(accessRequest{method: "GET", path: ra + "/accounts?limit=1", actor: a}, 200, "")
	cursor, ok := list.body["next_cursor"].(string)
	if !ok {
		t.Fatal("account pagination absent")
	}
	f.call(accessRequest{method: "GET", path: rb + "/accounts?limit=1&cursor=" + url.QueryEscape(cursor), actor: a}, 400, "cursor_invalid")
	f.call(accessRequest{method: "GET", path: rb + "/accounts", actor: a}, 200, "")

	before = f.digest()
	for _, q := range nested {
		q.actor = a
		q.key = f.id()
		f.call(q, 404, "not_found")
	}
	if f.digest() != before {
		t.Fatal("mixed-reference rejection changed financial/sync state")
	}
	// Snapshot and receipts are actor-scoped even within a shared workspace.
	f.call(accessRequest{method: "GET", path: rb + "/actions/" + foreign.action, actor: a}, 404, "not_found")
	f.call(accessRequest{method: "GET", path: rb + "/sync/snapshots/" + foreign.snapshot.ID, actor: a}, 404, "not_found")
	f.call(accessRequest{method: "GET", path: rb + "/sync/snapshots/" + foreign.snapshot.ID + "/pages?page_token=" + url.QueryEscape(foreign.snapshot.First), actor: a}, 404, "not_found")
	f.call(accessRequest{method: "GET", path: rb + "/sync/changes?cursor=" + url.QueryEscape(foreign.snapshot.Resume), actor: a}, 400, "cursor_invalid")
	f.call(accessRequest{method: "DELETE", path: "/api/v1/sessions/" + b.Session.ID, actor: a}, 404, "not_found")
	f.call(accessRequest{method: "GET", path: "/api/v1/me", actor: b}, 200, "")
	// Cookie/Origin guard precedes lookup of an already applied action (DR-A05).
	replay := accessRequest{method: "POST", path: ra + "/transactions", body: own.basic, key: own.action, cookie: a.Credential, actor: a, headers: map[string]string{"Authorization": ""}}
	before = f.digest()
	f.call(replay, 403, "csrf_invalid")
	replay.headers["Origin"] = "https://foreign.example.test"
	replay.headers["X-CSRF-Token"] = identity.CSRF(a.Credential)
	f.call(replay, 403, "csrf_invalid")
	replay.headers["Origin"] = "https://advanced.example.test"
	replay.headers["X-CSRF-Token"] = "wrong"
	f.call(replay, 403, "csrf_invalid")
	replay.headers["X-CSRF-Token"] = identity.CSRF(a.Credential)
	r := f.call(replay, 201, "")
	if r.header.Get("Idempotency-Replayed") != "true" {
		t.Fatal("authorized cookie replay not accepted")
	}
	if f.digest() != before {
		t.Fatal("CSRF denial or replay wrote financial state")
	}
	// Mixed valid auth is rejected on finance, sync, snapshot, and identity paths.
	for _, q := range []accessRequest{{method: "GET", path: "/api/v1/me"}, {method: "GET", path: ra + "/accounts/" + own.account}, {method: "GET", path: ra + "/sync/changes?cursor=" + own.snapshot.Resume}, {method: "GET", path: ra + "/sync/snapshots/" + own.snapshot.ID}, {method: "POST", path: ra + "/transactions", body: f.basic(own.account, "income"), key: f.id()}} {
		q.actor = a
		q.cookie = a.Credential
		f.call(q, 400, "bad_request")
	}
	private := []accessRequest{{method: "GET", path: "/api/v1/me"}, {method: "PUT", path: "/api/v1/me", body: map[string]any{"timezone": "UTC"}}, {method: "GET", path: "/api/v1/sessions"}, {method: "DELETE", path: "/api/v1/sessions/" + b.Session.ID}, {method: "GET", path: "/api/v1/workspaces"}, {method: "GET", path: "/api/v1/currencies"}, {method: "GET", path: "/api/v1/auth/session"}, {method: "POST", path: "/api/v1/auth/renew"}, {method: "POST", path: "/api/v1/auth/logout"}}
	for _, q := range private {
		f.call(q, 401, "unauthenticated")
	}
	for _, path := range []string{"/api/v1/me", "/api/v1/sessions", "/api/v1/workspaces", "/api/v1/currencies", "/api/v1/auth/session"} {
		f.call(accessRequest{method: "GET", path: path, actor: a}, 200, "")
	}
	f.call(accessRequest{method: "POST", path: "/api/v1/auth/register", body: map[string]any{"email": "disabled-access@example.test", "password": "synthetic disabled password", "transport": "bearer", "device_name": "Access", "timezone": "UTC"}}, 403, "registration_disabled")
	for _, email := range []string{"absent-access@example.test", a.Profile.Email} {
		f.call(accessRequest{method: "POST", path: "/api/v1/auth/login", body: map[string]any{"email": email, "password": "synthetic wrong password", "transport": "bearer", "device_name": "Access"}}, 401, "unauthenticated")
	}
	// Disable is checked on every actual route, not merely at login.
	if _, e := f.db.Exec(f.ctx, `UPDATE users SET disabled_at=clock_timestamp() WHERE id=$1`, a.Profile.ID); e != nil {
		t.Fatal(e)
	}
	before = f.digest()
	for _, q := range routes {
		q.path = strings.Replace(q.path, rb, ra, 1)
		q.actor = a
		if q.method != "GET" {
			q.key = f.id()
			if strings.HasSuffix(q.path, "/sync/snapshots") {
				q.body = map[string]any{"id": q.key}
			}
		}
		f.call(q, 401, "unauthenticated")
	}
	if f.digest() != before {
		t.Fatal("disabled request changed financial state")
	}
	if _, e := f.db.Exec(f.ctx, `UPDATE users SET disabled_at=NULL WHERE id=$1`, a.Profile.ID); e != nil {
		t.Fatal(e)
	}
	// Revoked membership fails even applied receipt replay and saved snapshot reads.
	if _, e := f.db.Exec(f.ctx, `UPDATE memberships SET revoked_at=clock_timestamp() WHERE user_id=$1 AND workspace_id=$2`, a.Profile.ID, a.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	f.call(accessRequest{method: "POST", path: ra + "/transactions", body: own.basic, actor: a, key: own.action}, 404, "not_found")
	f.call(accessRequest{method: "GET", path: ra + "/actions/" + own.action, actor: a}, 404, "not_found")
	f.call(accessRequest{method: "GET", path: ra + "/sync/snapshots/" + own.snapshot.ID, actor: a}, 404, "not_found")
	f.call(accessRequest{method: "GET", path: ra + "/sync/changes?cursor=" + own.snapshot.Resume, actor: a}, 404, "not_found")
	for _, secret := range []string{a.Credential, b.Credential, identity.CSRF(a.Credential), own.snapshot.Resume, own.snapshot.First, "Access note <script>", a.Profile.Email} {
		if strings.Contains(f.logs.text(), secret) {
			t.Fatal("application log disclosed credential, cursor, profile, or finance payload")
		}
	}
	f.save()
}
