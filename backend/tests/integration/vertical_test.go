package integration

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/platform"
	"accounting/backend/migrate"
	"accounting/backend/migrations"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// TestVertical reopens the pool and constructs a fresh API with no process-local
// auth/receipt state. Actual OS/container restarts are covered by browser E2E.
func TestVertical(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil || cfg.ConnConfig.Database != "accounting_test" {
		t.Fatal("requires isolated accounting_test")
	}
	admin, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal("database connection failed")
	}
	defer admin.Close()
	schema := fmt.Sprintf("vertical_%d", time.Now().UnixNano())
	ident := pgx.Identifier{schema}.Sanitize()
	if _, err = admin.Exec(ctx, "CREATE SCHEMA "+ident); err != nil {
		t.Fatal(err)
	}
	defer func() {
		clean, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		if _, err := admin.Exec(clean, "DROP SCHEMA "+ident+" CASCADE"); err != nil {
			t.Error("schema cleanup failed")
		}
	}()
	scoped := cfg.Copy()
	scoped.ConnConfig.RuntimeParams["search_path"] = schema
	open := func() *pgxpool.Pool {
		t.Helper()
		pool, err := pgxpool.NewWithConfig(ctx, scoped.Copy())
		if err != nil {
			t.Fatal("scoped connection failed")
		}
		return pool
	}
	db := open()
	defer func() { db.Close() }()
	if _, err = migrate.Apply(ctx, db, migrations.Files); err != nil {
		t.Fatal(err)
	}
	auth, err := identity.New(db, 2)
	if err != nil {
		t.Fatal(err)
	}
	user, err := auth.Register(ctx, "vertical@example.test", "synthetic vertical password", "vertical", "UTC")
	if err != nil {
		t.Fatal(err)
	}
	other, err := auth.Register(ctx, "other-vertical@example.test", "synthetic other password", "other", "UTC")
	if err != nil {
		t.Fatal(err)
	}
	var logs bytes.Buffer
	newServer := func() *httptest.Server {
		t.Helper()
		handler, err := platform.NewAPIHandler(db, platform.Config{Environment: "test", PublicOrigin: "https://vertical.example.test", AuthHashConcurrency: 2}, slog.New(slog.NewJSONHandler(&logs, nil)))
		if err != nil {
			t.Fatal(err)
		}
		return httptest.NewTLSServer(handler)
	}
	server := newServer()
	defer func() { server.Close() }()
	type response struct {
		status int
		body   []byte
		replay string
	}
	send := func(method, path string, body any, key, credential, generation string) response {
		t.Helper()
		var payload []byte
		if body != nil {
			payload, err = json.Marshal(body)
			if err != nil {
				t.Fatal(err)
			}
		}
		req, err := http.NewRequestWithContext(ctx, method, server.URL+"/api/v1"+path, bytes.NewReader(payload))
		if err != nil {
			t.Fatal("request construction failed")
		}
		req.Header.Set("Authorization", "Bearer "+credential)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if key != "" {
			req.Header.Set("Idempotency-Key", key)
			req.Header.Set("X-Sync-Generation", generation)
		}
		res, err := server.Client().Do(req)
		if err != nil {
			t.Fatal("TLS request failed")
		}
		defer res.Body.Close()
		raw, err := io.ReadAll(res.Body)
		if err != nil {
			t.Fatal("response read failed")
		}
		return response{res.StatusCode, raw, res.Header.Get("Idempotency-Replayed")}
	}
	expect := func(res response, status int) {
		t.Helper()
		if res.status != status {
			t.Fatalf("expected HTTP %d, got %d", status, res.status)
		}
	}
	const accountID = "d0000000-0000-4000-8000-000000000001"
	const expenseID = "d0000000-0000-4000-8000-000000000002"
	const accountKey = "d0000000-0000-4000-8000-000000000003"
	const expenseKey = "d0000000-0000-4000-8000-000000000004"
	root := "/workspaces/" + user.Workspace.ID
	account := map[string]any{"id": accountID, "name": "Vertical EUR", "type": "bank", "currency": "EUR", "opened_at": "2026-01-01T00:00:00Z", "occurred_timezone": "UTC", "opening_balance_minor": "100000"}
	expect(send("POST", root+"/accounts", account, accountKey, user.Credential, user.Workspace.Generation), 201)
	expense := map[string]any{"id": expenseID, "kind": "expense", "account_id": accountID, "amount_minor": "1234", "occurred_at": "2026-01-02T00:00:00Z", "occurred_timezone": "UTC", "note": "Synthetic restart acceptance", "payee": "", "tag_ids": []string{}, "allocations": []any{map[string]any{"id": "d0000000-0000-4000-8000-000000000005", "category_id": nil, "amount_minor": "1234"}}}
	original := send("POST", root+"/transactions", expense, expenseKey, user.Credential, user.Workspace.Generation)
	expect(original, 201)
	snapshot := func() map[string]string {
		t.Helper()
		var balance, version, balanceVersion, openings, expenses, entries, allocations, receipts, groups, changes, sequence, generation string
		err := db.QueryRow(ctx, `SELECT
   (SELECT sum(e.amount_minor)::text FROM entries e JOIN transactions t ON (t.workspace_id,t.id)=(e.workspace_id,e.transaction_id) WHERE e.workspace_id=$1 AND e.account_id=$2 AND t.status='posted' AND t.deleted_at IS NULL),
   (SELECT version::text FROM accounts WHERE workspace_id=$1 AND id=$2),
   (SELECT balance_version::text FROM accounts WHERE workspace_id=$1 AND id=$2),
   (SELECT count(*)::text FROM transactions WHERE workspace_id=$1 AND kind='opening'),
   (SELECT count(*)::text FROM transactions WHERE workspace_id=$1 AND kind='expense'),
   (SELECT count(*)::text FROM entries WHERE workspace_id=$1),
   (SELECT count(*)::text FROM allocations WHERE workspace_id=$1),
   (SELECT count(*)::text FROM actions WHERE workspace_id=$1),
   (SELECT count(*)::text FROM sync_groups WHERE workspace_id=$1),
   (SELECT count(*)::text FROM sync_changes WHERE workspace_id=$1),
   last_sequence::text,generation_id::text FROM sync_heads WHERE workspace_id=$1`, user.Workspace.ID, accountID).Scan(&balance, &version, &balanceVersion, &openings, &expenses, &entries, &allocations, &receipts, &groups, &changes, &sequence, &generation)
		if err != nil {
			t.Fatal("snapshot query failed")
		}
		return map[string]string{"posted_balance_minor": balance, "account_version": version, "balance_version": balanceVersion, "opening_count": openings, "expense_count": expenses, "entry_count": entries, "allocation_count": allocations, "receipt_count": receipts, "group_count": groups, "change_count": changes, "head_sequence": sequence, "generation_id": generation}
	}
	before := snapshot()
	expected := map[string]string{"posted_balance_minor": "98766", "account_version": "2", "balance_version": "2", "opening_count": "1", "expense_count": "1", "entry_count": "2", "allocation_count": "1", "receipt_count": "2", "group_count": "2", "change_count": "4", "head_sequence": "2", "generation_id": user.Workspace.Generation}
	if !reflect.DeepEqual(before, expected) {
		t.Fatal("baseline financial snapshot mismatch")
	}
	// Discard handler/service/pool and reconnect to the same persisted database.
	server.Close()
	db.Close()
	db = open()
	server = newServer()
	expect(send("GET", "/auth/session", nil, "", user.Credential, ""), 200)
	read := send("GET", root+"/accounts/"+accountID, nil, "", user.Credential, "")
	expect(read, 200)
	var readBody map[string]any
	if json.Unmarshal(read.body, &readBody) != nil || readBody["posted_balance_minor"] != "98766" {
		t.Fatal("balance not recovered")
	}
	replay := send("POST", root+"/transactions", expense, expenseKey, user.Credential, user.Workspace.Generation)
	expect(replay, 201)
	var originalJSON, replayJSON any
	if json.Unmarshal(original.body, &originalJSON) != nil || json.Unmarshal(replay.body, &replayJSON) != nil {
		t.Fatal("invalid mutation response JSON")
	}
	responseEqual := reflect.DeepEqual(originalJSON, replayJSON)
	// PostgreSQL jsonb may reorder object keys/whitespace; compare the entire
	// JSON value, preserving monetary/version strings, rather than its encoding.
	if replay.replay != "true" || !responseEqual {
		t.Fatal("durable receipt did not replay original JSON response")
	}
	outcome := send("GET", root+"/actions/"+expenseKey, nil, "", user.Credential, "")
	expect(outcome, 200)
	var receipt map[string]any
	if json.Unmarshal(outcome.body, &receipt) != nil || receipt["state"] != "applied" {
		t.Fatal("outcome not recovered")
	}
	expect(send("GET", root+"/accounts/"+accountID, nil, "", other.Credential, ""), 404)
	foreignRoot := "/workspaces/" + other.Workspace.ID
	expect(send("POST", foreignRoot+"/transactions", expense, "d0000000-0000-4000-8000-000000000006", other.Credential, other.Workspace.Generation), 404)
	after := snapshot()
	if !reflect.DeepEqual(before, after) {
		t.Fatal("restart/replay/foreign request changed owner ledger")
	}
	for _, secret := range []string{user.Credential, other.Credential, "synthetic vertical password", "synthetic other password"} {
		if bytes.Contains(logs.Bytes(), []byte(secret)) {
			t.Fatal("secret leaked into request logs")
		}
	}
	if path := os.Getenv("VERTICAL_RESPONSE_FILE"); path != "" {
		// Export only selected synthetic ledger measurements; never dump users/sessions.
		artifact := map[string]any{"scenario": "vertical_backend_reopen", "before": before, "after": after, "original_http_status": original.status, "replayed": replay.replay, "response_json_equal": responseEqual}
		data, err := json.MarshalIndent(artifact, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err = os.MkdirAll(filepath.Dir(path), 0755); err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(path, data, 0600); err != nil {
			t.Fatal(err)
		}
	}
}
