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
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	neturl "net/url"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestLedgerBasic(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	cfg, e := pgxpool.ParseConfig(url)
	if e != nil || cfg.ConnConfig.Database != "accounting_test" {
		t.Fatal("requires isolated accounting_test")
	}
	admin, e := pgxpool.NewWithConfig(ctx, cfg)
	if e != nil {
		t.Fatal(e)
	}
	defer admin.Close()
	schema := fmt.Sprintf("ledger_basic_%d", time.Now().UnixNano())
	ident := pgx.Identifier{schema}.Sanitize()
	if _, e = admin.Exec(ctx, "CREATE SCHEMA "+ident); e != nil {
		t.Fatal(e)
	}
	defer func() {
		clean, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		if _, e := admin.Exec(clean, "DROP SCHEMA "+ident+" CASCADE"); e != nil {
			t.Error("schema cleanup failed")
		}
	}()
	scoped := cfg.Copy()
	scoped.ConnConfig.RuntimeParams["search_path"] = schema
	db, e := pgxpool.NewWithConfig(ctx, scoped)
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	if _, e = migrate.Apply(ctx, db, migrations.Files); e != nil {
		t.Fatal(e)
	}
	auth, e := identity.New(db, 2)
	if e != nil {
		t.Fatal(e)
	}
	a, e := auth.Register(ctx, "ledger@example.test", "literal ledger password", "ledger", "UTC")
	if e != nil {
		t.Fatal(e)
	}
	foreign, e := auth.Register(ctx, "foreign-ledger@example.test", "literal foreign password", "foreign", "UTC")
	if e != nil {
		t.Fatal(e)
	}
	handler, e := platform.NewAPIHandler(db, platform.Config{Environment: "test", PublicOrigin: "https://ledger.example.test", AuthHashConcurrency: 2}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	server := httptest.NewTLSServer(handler)
	defer server.Close()
	type response struct {
		status   int
		body     map[string]any
		replayed string
	}
	type observed struct {
		Schema string         `json:"schema"`
		Body   map[string]any `json:"body"`
	}
	observations := []observed{}
	var observationsMutex sync.Mutex
	record := func(method, path string, res response) {
		schema := "Error"
		if res.status >= 200 && res.status < 300 {
			if method != "GET" {
				schema = "MutationResult"
			} else {
				path = strings.Split(path, "?")[0]
				if path == "/currencies" {
					schema = "CurrencyList"
				} else {
					for _, resource := range []struct{ path, schema string }{{"accounts", "Account"}, {"categories", "Category"}, {"tags", "Tag"}, {"transactions", "Transaction"}} {
						if strings.Contains(path, "/"+resource.path) {
							schema = resource.schema
							if strings.HasSuffix(path, "/"+resource.path) {
								schema += "List"
							}
							break
						}
					}
				}
			}
		}
		observationsMutex.Lock()
		observations = append(observations, observed{schema, res.body})
		observationsMutex.Unlock()
	}
	send := func(method, path string, body any, action, token string) response {
		var data []byte
		if body != nil {
			data, _ = json.Marshal(body)
		}
		r, e := http.NewRequestWithContext(ctx, method, server.URL+"/api/v1"+path, bytes.NewReader(data))
		if e != nil {
			return response{status: 0}
		}
		if body != nil {
			r.Header.Set("Content-Type", "application/json")
		}
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		if action != "" {
			r.Header.Set("Idempotency-Key", action)
			generation := a.Workspace.Generation
			if strings.HasPrefix(path, "/workspaces/"+foreign.Workspace.ID+"/") {
				generation = foreign.Workspace.Generation
			}
			r.Header.Set("X-Sync-Generation", generation)
		}
		res, e := server.Client().Do(r)
		if e != nil {
			return response{status: 0}
		}
		defer res.Body.Close()
		out := response{status: res.StatusCode, replayed: res.Header.Get("Idempotency-Replayed")}
		if json.NewDecoder(res.Body).Decode(&out.body) != nil {
			out.status = 0
		}
		record(method, path, out)
		return out
	}
	expect := func(res response, status int, code string) {
		t.Helper()
		if res.status != status || code != "" && res.body["code"] != code {
			t.Fatalf("HTTP expected %d %s; got %d %v", status, code, res.status, res.body["code"])
		}
	}
	next := 0
	action := func() string { next++; return fmt.Sprintf("b0000000-0000-4000-8000-%012d", next) }
	root := "/workspaces/" + a.Workspace.ID + "/accounts"
	accountID := "c0000000-0000-4000-8000-000000000001"
	input := func(id, amount string) map[string]any {
		return map[string]any{"id": id, "name": " Wallet ", "type": "cash", "currency": "EUR", "opened_at": "2026-01-01T00:00:00Z", "occurred_timezone": "Europe/Nicosia", "opening_balance_minor": amount}
	}
	firstAction := action()
	body := input(accountID, "100000")
	first := send("POST", root, body, firstAction, a.Credential)
	expect(first, 201, "")
	if first.replayed != "false" {
		t.Fatal("first completion marked replay")
	}
	account := first.body["accounts"].([]any)[0].(map[string]any)
	if account["posted_balance_minor"] != "100000" || account["pending_delta_minor"] != "0" || account["projected_balance_minor"] != "100000" || account["version"] != "1" || account["balance_version"] != "1" || account["name"] != "Wallet" {
		t.Fatal("incorrect initial account")
	}
	opening := first.body["transactions"].([]any)[0].(map[string]any)
	if opening["kind"] != "opening" || opening["status"] != "posted" || len(opening["entries"].([]any)) != 1 || len(opening["allocations"].([]any)) != 0 {
		t.Fatal("incorrect opening aggregate")
	}
	entry := opening["entries"].([]any)[0].(map[string]any)
	if entry["amount_minor"] != "100000" || entry["account_id"] != accountID || entry["currency"] != "EUR" {
		t.Fatal("incorrect opening entry")
	}
	replay := send("POST", root, body, firstAction, a.Credential)
	expect(replay, 201, "")
	if replay.replayed != "true" || !reflect.DeepEqual(first.body, replay.body) {
		t.Fatal("replay differs")
	}
	get := send("GET", root+"/"+accountID, nil, "", a.Credential)
	expect(get, 200, "")
	if !reflect.DeepEqual(account, get.body) {
		t.Fatal("GET differs from journal account")
	}
	// Count physical movement and complete group envelopes, not only response values.
	var openings, entries, changes int
	var head int64
	if e = db.QueryRow(ctx, `SELECT (SELECT count(*) FROM transactions WHERE kind='opening'),(SELECT count(*) FROM entries),(SELECT count(*) FROM sync_changes),(SELECT last_sequence FROM sync_heads WHERE workspace_id=$1)`, a.Workspace.ID).Scan(&openings, &entries, &changes, &head); e != nil {
		t.Fatal(e)
	}
	if openings != 1 || entries != 1 || changes != 2 || head != 1 {
		t.Fatalf("unexpected persisted counts: %d %d %d %d", openings, entries, changes, head)
	}
	zeroID := "c0000000-0000-4000-8000-000000000002"
	zero := send("POST", root, input(zeroID, "0"), action(), a.Credential)
	expect(zero, 201, "")
	if len(zero.body["transactions"].([]any)[0].(map[string]any)["entries"].([]any)) != 0 {
		t.Fatal("zero opening has entry")
	}
	negativeID := "c0000000-0000-4000-8000-000000000003"
	negative := send("POST", root, input(negativeID, "-9000000000000000"), action(), a.Credential)
	expect(negative, 201, "")
	if negative.body["accounts"].([]any)[0].(map[string]any)["posted_balance_minor"] != "-9000000000000000" {
		t.Fatal("negative limit rounded")
	}
	duplicate := send("POST", root, body, action(), a.Credential)
	expect(duplicate, 409, "validation_error")
	invalidBody := input("c0000000-0000-4000-8000-000000000004", "9000000000000001")
	malformedKey := action()
	expect(send("POST", root, invalidBody, malformedKey, a.Credential), 422, "validation_error")
	var receipts int
	if e = db.QueryRow(ctx, `SELECT count(*) FROM actions WHERE workspace_id=$1 AND action_id=$2`, a.Workspace.ID, malformedKey).Scan(&receipts); e != nil || receipts != 0 {
		t.Fatal("structural error registered receipt", e)
	}
	invalidBody["opening_balance_minor"] = "1"
	invalidBody["opened_at"] = "2999-01-01T00:00:00Z"
	rejectedKey := action()
	expect(send("POST", root, invalidBody, rejectedKey, a.Credential), 422, "validation_error")
	rejectedReplay := send("POST", root, invalidBody, rejectedKey, a.Credential)
	expect(rejectedReplay, 422, "validation_error")
	if rejectedReplay.replayed != "true" {
		t.Fatal("terminal rejection not replayed")
	}
	invalidBody["opened_at"] = "2026-01-01T00:00:00Z"
	invalidBody["currency"] = "ZZZ"
	expect(send("POST", root, invalidBody, action(), a.Credential), 422, "validation_error")

	// Concrete account route: concurrent duplicate delivery creates one opening.
	concurrentID := "c0000000-0000-4000-8000-000000000006"
	concurrentKey := action()
	concurrentBody := input(concurrentID, "9000000000000000")
	concurrent := make([]response, 2)
	var duplicateWG sync.WaitGroup
	for i := range 2 {
		duplicateWG.Add(1)
		go func(i int) {
			defer duplicateWG.Done()
			concurrent[i] = send("POST", root, concurrentBody, concurrentKey, a.Credential)
		}(i)
	}
	duplicateWG.Wait()
	for _, res := range concurrent {
		expect(res, 201, "")
	}
	if concurrent[0].replayed == concurrent[1].replayed || !reflect.DeepEqual(concurrent[0].body, concurrent[1].body) {
		t.Fatal("concurrent delivery did not reuse one result")
	}
	var concurrentOpenings int
	if e = db.QueryRow(ctx, `SELECT count(*) FROM transactions WHERE workspace_id=$1 AND opening_account_id=$2`, a.Workspace.ID, concurrentID).Scan(&concurrentOpenings); e != nil || concurrentOpenings != 1 {
		t.Fatal("duplicate opening after race", e)
	}
	// Browser transport must pass CSRF/Origin even when replaying an old success.
	cookieReplay := func(origin, csrf string) response {
		data, _ := json.Marshal(body)
		req, _ := http.NewRequestWithContext(ctx, "POST", server.URL+"/api/v1"+root, bytes.NewReader(data))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Idempotency-Key", firstAction)
		req.Header.Set("X-Sync-Generation", a.Workspace.Generation)
		req.Header.Set("Origin", origin)
		req.Header.Set("X-CSRF-Token", csrf)
		req.AddCookie(&http.Cookie{Name: "__Host-accounting_session", Value: a.Credential})
		res, e := server.Client().Do(req)
		if e != nil {
			return response{}
		}
		defer res.Body.Close()
		out := response{status: res.StatusCode, replayed: res.Header.Get("Idempotency-Replayed")}
		if json.NewDecoder(res.Body).Decode(&out.body) != nil {
			out.status = 0
		}
		return out
	}
	expect(cookieReplay("https://foreign.example", identity.CSRF(a.Credential)), 403, "csrf_invalid")
	expect(cookieReplay("https://ledger.example.test", "wrong"), 403, "csrf_invalid")
	cookieSuccess := cookieReplay("https://ledger.example.test", identity.CSRF(a.Credential))
	expect(cookieSuccess, 201, "")
	if cookieSuccess.replayed != "true" {
		t.Fatal("cookie replay not marked")
	}
	// Metadata/archive never change opening or balance_version.
	update := map[string]any{"expected_version": "1", "name": "Renamed", "type": "bank", "archived": true}
	updated := send("PUT", root+"/"+accountID, update, action(), a.Credential)
	expect(updated, 200, "")
	account = updated.body["accounts"].([]any)[0].(map[string]any)
	if account["version"] != "2" || account["balance_version"] != "1" || account["archived_at"] == nil || account["posted_balance_minor"] != "100000" || account["currency"] != "EUR" || len(updated.body["transactions"].([]any)) != 0 {
		t.Fatal("metadata update changed finances")
	}
	conflictKey := action()
	conflict := send("PUT", root+"/"+accountID, update, conflictKey, a.Credential)
	expect(conflict, 409, "version_conflict")
	if conflict.body["current_versions"].([]any)[0].(map[string]any)["version"] != "2" {
		t.Fatal("conflict missing current version")
	}
	update["expected_version"] = "2"
	update["archived"] = false
	expect(send("PUT", root+"/"+accountID, update, action(), a.Credential), 200, "")
	// Concurrent expected versions allow exactly one metadata update.
	update["expected_version"] = "3"
	keys := []string{action(), action()}
	results := make([]response, 2)
	var wg sync.WaitGroup
	for i := range 2 {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			results[i] = send("PUT", root+"/"+accountID, update, keys[i], a.Credential)
		}(i)
	}
	wg.Wait()
	if !((results[0].status == 200 && results[1].status == 409) || (results[1].status == 200 && results[0].status == 409)) {
		t.Fatalf("concurrent versions: %d %d", results[0].status, results[1].status)
	}
	// Archive filtering and signed pagination binding.
	page := send("GET", root+"?limit=1", nil, "", a.Credential)
	expect(page, 200, "")
	cursor, ok := page.body["next_cursor"].(string)
	if !ok {
		t.Fatal("missing cursor")
	}
	page2 := send("GET", root+"?limit=1&cursor="+cursor, nil, "", a.Credential)
	expect(page2, 200, "")
	if page.body["items"].([]any)[0].(map[string]any)["id"] == page2.body["items"].([]any)[0].(map[string]any)["id"] {
		t.Fatal("duplicate page item")
	}
	expect(send("GET", root+"?archived=include&cursor="+cursor, nil, "", a.Credential), 400, "cursor_invalid")
	expect(send("GET", root+"?limit=1&limit=2", nil, "", a.Credential), 400, "bad_request")
	expect(send("GET", root+"?limit=0", nil, "", a.Credential), 400, "bad_request")
	expect(send("GET", root+"?unknown=1", nil, "", a.Credential), 400, "bad_request")
	only := send("GET", root+"?archived=only", nil, "", a.Credential)
	expect(only, 200, "")
	if len(only.body["items"].([]any)) != 0 {
		t.Fatal("restored account still archived")
	}
	currencies := send("GET", "/currencies", nil, "", a.Credential)
	expect(currencies, 200, "")
	if len(currencies.body["items"].([]any)) != 6 {
		t.Fatal("missing currencies")
	}
	expect(send("GET", "/currencies", nil, "", ""), 401, "unauthenticated")
	expect(send("GET", root+"/"+accountID, nil, "", foreign.Credential), 404, "not_found")
	expect(send("PUT", root+"/"+accountID, update, action(), foreign.Credential), 404, "not_found")
	foreignPath := "/workspaces/" + foreign.Workspace.ID + "/accounts/" + accountID
	expect(send("GET", foreignPath, nil, "", foreign.Credential), 404, "not_found")

	// Cursors from another credential fail even if that actor has access.
	if _, e = db.Exec(ctx, `INSERT INTO memberships(workspace_id,user_id,role) VALUES($1,$2,'member')`, a.Workspace.ID, foreign.Profile.ID); e != nil {
		t.Fatal(e)
	}
	expect(send("GET", root+"?limit=1&cursor="+cursor, nil, "", foreign.Credential), 400, "cursor_invalid")
	expect(send("GET", root+"/"+accountID, nil, "", foreign.Credential), 200, "")
	if _, e = db.Exec(ctx, `DELETE FROM memberships WHERE workspace_id=$1 AND user_id=$2`, a.Workspace.ID, foreign.Profile.ID); e != nil {
		t.Fatal(e)
	}
	// A failure after account INSERT rolls back the account, movement, receipt and head.
	failureID := "c0000000-0000-4000-8000-000000000005"
	failureKey := action()
	if _, e = db.Exec(ctx, `CREATE FUNCTION fail_opening() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected opening failure'; END $$; CREATE TRIGGER injected_opening BEFORE INSERT ON transactions FOR EACH ROW EXECUTE FUNCTION fail_opening()`); e != nil {
		t.Fatal(e)
	}
	expect(send("POST", root, input(failureID, "1"), failureKey, a.Credential), 503, "service_unavailable")
	var remains int
	if e = db.QueryRow(ctx, `SELECT (SELECT count(*) FROM accounts WHERE id=$1)+(SELECT count(*) FROM actions WHERE workspace_id=$2 AND action_id=$3)`, failureID, a.Workspace.ID, failureKey).Scan(&remains); e != nil || remains != 0 {
		t.Fatal("failed command left partial data", e)
	}
	if _, e = db.Exec(ctx, `DROP TRIGGER injected_opening ON transactions; DROP FUNCTION fail_opening()`); e != nil {
		t.Fatal(e)
	}
	expect(send("POST", root, input(failureID, "1"), failureKey, a.Credential), 201, "")
	// Exercise the complete basic accounting path before revoking its actor.
	basicRoot := "/workspaces/" + a.Workspace.ID
	categoryID := "d0000000-0000-4000-8000-000000000001"
	childID := "d0000000-0000-4000-8000-000000000002"
	tagID := "e0000000-0000-4000-8000-000000000001"
	expect(send("POST", basicRoot+"/categories", map[string]any{"id": categoryID, "name": "Food", "parent_id": nil}, action(), a.Credential), 201, "")
	expect(send("POST", basicRoot+"/categories", map[string]any{"id": childID, "name": "Groceries", "parent_id": categoryID}, action(), a.Credential), 201, "")
	expect(send("POST", basicRoot+"/tags", map[string]any{"id": tagID, "name": " Trip "}, action(), a.Credential), 201, "")
	expect(send("POST", basicRoot+"/tags", map[string]any{"id": "e0000000-0000-4000-8000-000000000002", "name": "trip"}, action(), a.Credential), 422, "validation_error")
	txCounter := 0
	transaction := func(kind, account, amount string, category any, tags []string) map[string]any {
		txCounter++
		tid := fmt.Sprintf("f1000000-0000-4000-8000-%012d", txCounter)
		pid := fmt.Sprintf("f2000000-0000-4000-8000-%012d", txCounter)
		return map[string]any{"id": tid, "kind": kind, "account_id": account, "amount_minor": amount, "occurred_at": "2026-02-01T12:00:00Z", "occurred_timezone": "Europe/Nicosia", "note": "Original", "payee": "Store", "tag_ids": tags, "allocations": []any{map[string]any{"id": pid, "category_id": category, "amount_minor": amount}}}
	}
	txRoot := basicRoot + "/transactions"
	expenseBody := transaction("expense", accountID, "1234", categoryID, []string{tagID})
	expenseOriginal := map[string]any{}
	originalBytes, _ := json.Marshal(expenseBody)
	if e = json.Unmarshal(originalBytes, &expenseOriginal); e != nil {
		t.Fatal(e)
	}
	expenseKey := action()
	expense := send("POST", txRoot, expenseBody, expenseKey, a.Credential)
	expect(expense, 201, "")
	expenseAccount := expense.body["accounts"].([]any)[0].(map[string]any)
	if expenseAccount["posted_balance_minor"] != "98766" || expenseAccount["version"] != "5" || expenseAccount["balance_version"] != "2" {
		t.Fatal("baseline expense did not update exact balance/versions")
	}
	expenseTx := expense.body["transactions"].([]any)[0].(map[string]any)
	if expenseTx["entries"].([]any)[0].(map[string]any)["amount_minor"] != "-1234" || expenseTx["allocations"].([]any)[0].(map[string]any)["remaining_refundable_minor"] != "1234" {
		t.Fatal("incorrect expense entries/parts")
	}
	expenseID := expenseBody["id"].(string)
	readExpense := send("GET", txRoot+"/"+expenseID, nil, "", a.Credential)
	expect(readExpense, 200, "")
	if !reflect.DeepEqual(readExpense.body, expenseTx) {
		t.Fatal("transaction GET differs from committed aggregate")
	}
	expenseReplay := send("POST", txRoot, expenseBody, expenseKey, a.Credential)
	expect(expenseReplay, 201, "")
	if expenseReplay.replayed != "true" || !reflect.DeepEqual(expense.body, expenseReplay.body) {
		t.Fatal("expense replay differs")
	}
	openingGet := send("GET", txRoot+"/"+opening["id"].(string), nil, "", a.Credential)
	expect(openingGet, 200, "")
	if !reflect.DeepEqual(openingGet.body, opening) {
		t.Fatal("opening GET differs")
	}
	// Independent simultaneous expenses both succeed; they do not expect a balance version.
	one := transaction("expense", accountID, "11", nil, []string{})
	two := transaction("expense", accountID, "13", nil, []string{})
	expenseKeys := []string{action(), action()}
	expenseBodies := []map[string]any{one, two}
	expenseResults := make([]response, 2)
	for i := range 2 {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			expenseResults[i] = send("POST", txRoot, expenseBodies[i], expenseKeys[i], a.Credential)
		}(i)
	}
	wg.Wait()
	for _, res := range expenseResults {
		expect(res, 201, "")
	}
	expectBalance := func(id, balance string) {
		t.Helper()
		res := send("GET", root+"/"+id, nil, "", a.Credential)
		expect(res, 200, "")
		if res.body["posted_balance_minor"] != balance {
			t.Fatalf("expected balance %s got %v", balance, res.body["posted_balance_minor"])
		}
	}
	expectBalance(accountID, "98742")
	incomeBody := transaction("income", accountID, "20000", nil, []string{})
	income := send("POST", txRoot, incomeBody, action(), a.Credential)
	expect(income, 201, "")
	incomeTx := income.body["transactions"].([]any)[0].(map[string]any)
	if incomeTx["entries"].([]any)[0].(map[string]any)["amount_minor"] != "20000" || incomeTx["allocations"].([]any)[0].(map[string]any)["remaining_refundable_minor"] != "0" {
		t.Fatal("incorrect income")
	}
	expectBalance(accountID, "118742")
	incomeReplace := map[string]any{}
	for k, v := range incomeBody {
		if k != "id" {
			incomeReplace[k] = v
		}
	}
	incomeReplace["expected_version"] = "1"
	incomeReplace["note"] = "Income note edited"
	incomeNote := send("PUT", txRoot+"/"+incomeBody["id"].(string), incomeReplace, action(), a.Credential)
	expect(incomeNote, 200, "")
	if len(incomeNote.body["accounts"].([]any)) != 0 {
		t.Fatal("income note edit altered account")
	}
	split := transaction("expense", accountID, "6000", nil, []string{})
	parts := []any{map[string]any{"id": "f3000000-0000-4000-8000-000000000001", "category_id": categoryID, "amount_minor": "4000"}, map[string]any{"id": "f3000000-0000-4000-8000-000000000002", "category_id": nil, "amount_minor": "1999"}}
	split["allocations"] = parts
	rejectedSplitKey := action()
	expect(send("POST", txRoot, split, rejectedSplitKey, a.Credential), 422, "validation_error")
	expectBalance(accountID, "118742")
	splitReplay := send("POST", txRoot, split, rejectedSplitKey, a.Credential)
	expect(splitReplay, 422, "validation_error")
	if splitReplay.replayed != "true" {
		t.Fatal("split rejection not replayed")
	}
	parts[1].(map[string]any)["amount_minor"] = "2000"
	parts[1].(map[string]any)["id"] = parts[0].(map[string]any)["id"]
	expect(send("POST", txRoot, split, action(), a.Credential), 422, "validation_error")
	expectBalance(accountID, "118742")
	parts[1].(map[string]any)["id"] = "f3000000-0000-4000-8000-000000000002"
	expect(send("POST", txRoot, split, action(), a.Credential), 201, "")
	expectBalance(accountID, "112742")
	// UUID collision with a different aggregate rolls back entry and all versions.
	collision := transaction("expense", accountID, "1", nil, []string{})
	collision["allocations"].([]any)[0].(map[string]any)["id"] = expenseBody["allocations"].([]any)[0].(map[string]any)["id"]
	beforeCollision := send("GET", root+"/"+accountID, nil, "", a.Credential)
	expect(send("POST", txRoot, collision, action(), a.Credential), 422, "validation_error")
	afterCollision := send("GET", root+"/"+accountID, nil, "", a.Credential)
	if !reflect.DeepEqual(beforeCollision.body, afterCollision.body) {
		t.Fatal("allocation collision altered account")
	}
	expect(send("GET", txRoot+"/"+collision["id"].(string), nil, "", a.Credential), 404, "not_found")
	// DR-F02: posted overflow at either signed limit must not alter versions.
	upperBefore := send("GET", root+"/"+concurrentID, nil, "", a.Credential)
	expect(send("POST", txRoot, transaction("income", concurrentID, "1", nil, []string{}), action(), a.Credential), 422, "amount_out_of_range")
	upperAfter := send("GET", root+"/"+concurrentID, nil, "", a.Credential)
	if !reflect.DeepEqual(upperBefore.body, upperAfter.body) {
		t.Fatal("overflow altered upper-limit account")
	}
	expect(send("POST", txRoot, transaction("expense", negativeID, "1", nil, []string{}), action(), a.Credential), 422, "amount_out_of_range")
	expectBalance(negativeID, "-9000000000000000")
	// Date checks and stale archived-account editor (DR-F11).
	early := transaction("expense", accountID, "1", nil, []string{})
	early["occurred_at"] = "2025-12-31T23:59:59Z"
	expect(send("POST", txRoot, early, action(), a.Credential), 422, "validation_error")
	early["occurred_at"] = "2999-01-01T00:00:00Z"
	expect(send("POST", txRoot, early, action(), a.Credential), 422, "validation_error")
	freshAccount := send("GET", root+"/"+accountID, nil, "", a.Credential)
	expect(send("PUT", root+"/"+accountID, map[string]any{"expected_version": freshAccount.body["version"], "name": "Renamed", "type": "bank", "archived": true}, action(), a.Credential), 200, "")
	expect(send("POST", txRoot, transaction("expense", accountID, "1", nil, []string{}), action(), a.Credential), 422, "account_archived")
	archivedAccount := send("GET", root+"/"+accountID, nil, "", a.Credential)
	expectBalance(accountID, "112742")
	expect(send("PUT", root+"/"+accountID, map[string]any{"expected_version": archivedAccount.body["version"], "name": "Renamed", "type": "bank", "archived": false}, action(), a.Credential), 200, "")
	// Archive existing classification, retaining the expense's historical references (DR-F13).
	expect(send("PUT", basicRoot+"/categories/"+categoryID, map[string]any{"expected_version": "1", "name": "Food", "parent_id": nil, "archived": true}, action(), a.Credential), 200, "")
	expect(send("PUT", basicRoot+"/tags/"+tagID, map[string]any{"expected_version": "1", "name": "Trip", "archived": true}, action(), a.Credential), 200, "")
	replacement := map[string]any{}
	for k, v := range expenseBody {
		if k != "id" {
			replacement[k] = v
		}
	}
	replacement["expected_version"] = "1"
	replacement["expected_parent_version"] = nil
	replacement["note"] = "Edited note"
	beforeNote := send("GET", root+"/"+accountID, nil, "", a.Credential)
	noteResult := send("PUT", txRoot+"/"+expenseID, replacement, action(), a.Credential)
	expect(noteResult, 200, "")
	if len(noteResult.body["accounts"].([]any)) != 0 {
		t.Fatal("note-only edit published account")
	}
	afterNote := send("GET", root+"/"+accountID, nil, "", a.Credential)
	if !reflect.DeepEqual(beforeNote.body, afterNote.body) {
		t.Fatal("note-only edit changed balances/versions")
	}
	expect(send("PUT", txRoot+"/"+expenseID, replacement, action(), a.Credential), 409, "version_conflict")
	expect(send("POST", txRoot, transaction("expense", accountID, "1", categoryID, []string{}), action(), a.Credential), 422, "validation_error")
	expect(send("POST", txRoot, transaction("expense", accountID, "1", nil, []string{tagID}), action(), a.Credential), 422, "validation_error")
	// Archive-inclusive cycles and unchanged archived parent on a category name edit.
	expect(send("PUT", basicRoot+"/categories/"+categoryID, map[string]any{"expected_version": "2", "name": "Food", "parent_id": childID, "archived": true}, action(), a.Credential), 422, "validation_error")
	expect(send("PUT", basicRoot+"/categories/"+childID, map[string]any{"expected_version": "1", "name": "Groceries renamed", "parent_id": categoryID, "archived": false}, action(), a.Credential), 200, "")
	expect(send("POST", basicRoot+"/categories", map[string]any{"id": "d0000000-0000-4000-8000-000000000003", "name": "New child", "parent_id": categoryID}, action(), a.Credential), 422, "validation_error")
	categoriesOnly := send("GET", basicRoot+"/categories?archived=only", nil, "", a.Credential)
	expect(categoriesOnly, 200, "")
	if len(categoriesOnly.body["items"].([]any)) != 1 {
		t.Fatal("archived category filter")
	}
	tagsOnly := send("GET", basicRoot+"/tags?archived=only", nil, "", a.Credential)
	expect(tagsOnly, 200, "")
	if len(tagsOnly.body["items"].([]any)) != 1 {
		t.Fatal("archived tag filter")
	}
	// Foreign resource references stay 404 instead of exposing financial payloads.
	foreignRoot := "/workspaces/" + foreign.Workspace.ID
	foreignCategory := "d0000000-0000-4000-8000-000000000099"
	expect(send("POST", foreignRoot+"/categories", map[string]any{"id": foreignCategory, "name": "Private", "parent_id": nil}, action(), foreign.Credential), 201, "")
	expect(send("POST", txRoot, transaction("expense", accountID, "1", foreignCategory, []string{}), action(), a.Credential), 404, "not_found")
	expect(send("POST", txRoot, transaction("expense", "c0000000-0000-4000-8000-000000000099", "1", nil, []string{}), action(), a.Credential), 404, "not_found")
	expect(send("GET", txRoot+"/"+expenseID, nil, "", foreign.Credential), 404, "not_found")
	// Oversized manual bodies use the documented 256 KiB limit before schema validation.
	oversized := transaction("expense", accountID, "1", nil, []string{})
	oversized["note"] = strings.Repeat("x", 262145)
	expect(send("POST", txRoot, oversized, action(), a.Credential), 413, "bad_request")

	// Financial replacement keeps part/entry UUIDs and bumps each affected account once.
	replacement["expected_version"] = "2"
	replacement["amount_minor"] = "2234"
	expensePart := replacement["allocations"].([]any)[0].(map[string]any)
	expensePart["amount_minor"] = "2234"
	beforeAmount := send("GET", root+"/"+accountID, nil, "", a.Credential)
	amountResult := send("PUT", txRoot+"/"+expenseID, replacement, action(), a.Credential)
	expect(amountResult, 200, "")
	if len(amountResult.body["accounts"].([]any)) != 1 {
		t.Fatal("amount edit did not publish account")
	}
	afterAmount := send("GET", root+"/"+accountID, nil, "", a.Credential)
	beforeBV, _ := strconv.ParseInt(beforeAmount.body["balance_version"].(string), 10, 64)
	afterBV, _ := strconv.ParseInt(afterAmount.body["balance_version"].(string), 10, 64)
	if afterBV != beforeBV+1 {
		t.Fatal("amount edit did not bump balance version once")
	}
	expectBalance(accountID, "111742")
	replacement["expected_version"] = "3"
	replacement["account_id"] = zeroID
	moved := send("PUT", txRoot+"/"+expenseID, replacement, action(), a.Credential)
	expect(moved, 200, "")
	if len(moved.body["accounts"].([]any)) != 2 {
		t.Fatal("account move did not publish both accounts")
	}
	expectBalance(accountID, "113976")
	expectBalance(zeroID, "-2234")
	movedTx := moved.body["transactions"].([]any)[0].(map[string]any)
	if movedTx["entries"].([]any)[0].(map[string]any)["id"] != expenseTx["entries"].([]any)[0].(map[string]any)["id"] || movedTx["allocations"].([]any)[0].(map[string]any)["id"] != expenseTx["allocations"].([]any)[0].(map[string]any)["id"] {
		t.Fatal("edit replaced stable child UUIDs")
	}
	jpyID := "c0000000-0000-4000-8000-000000000008"
	jpy := input(jpyID, "0")
	jpy["currency"] = "JPY"
	expect(send("POST", root, jpy, action(), a.Credential), 201, "")
	replacement["expected_version"] = "4"
	replacement["account_id"] = jpyID
	expect(send("PUT", txRoot+"/"+expenseID, replacement, action(), a.Credential), 422, "validation_error")
	expectBalance(jpyID, "0")
	expectBalance(zeroID, "-2234")
	replacement["account_id"] = zeroID
	// A new part cannot inherit an archived category by reusing only its category UUID.
	expensePart["id"] = "f2000000-0000-4000-8000-000000000099"
	expect(send("PUT", txRoot+"/"+expenseID, replacement, action(), a.Credential), 422, "validation_error")
	expensePart["id"] = expenseTx["allocations"].([]any)[0].(map[string]any)["id"]
	// Concurrent category moves cannot commit a cycle.
	cycleIDs := []string{"d0000000-0000-4000-8000-000000000004", "d0000000-0000-4000-8000-000000000005"}
	for _, id := range cycleIDs {
		expect(send("POST", basicRoot+"/categories", map[string]any{"id": id, "name": "Cycle candidate", "parent_id": nil}, action(), a.Credential), 201, "")
	}
	cycleKeys := []string{action(), action()}
	cycleResults := make([]response, 2)
	for i := range 2 {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			cycleResults[i] = send("PUT", basicRoot+"/categories/"+cycleIDs[i], map[string]any{"expected_version": "1", "name": "Cycle candidate", "parent_id": cycleIDs[1-i], "archived": false}, cycleKeys[i], a.Credential)
		}(i)
	}
	wg.Wait()
	if !((cycleResults[0].status == 200 && cycleResults[1].status == 422) || (cycleResults[1].status == 200 && cycleResults[0].status == 422)) {
		t.Fatalf("cycle race: %d %d", cycleResults[0].status, cycleResults[1].status)
	}
	selfID := "d0000000-0000-4000-8000-000000000006"
	expect(send("POST", basicRoot+"/categories", map[string]any{"id": selfID, "name": "Self", "parent_id": selfID}, action(), a.Credential), 422, "validation_error")
	// Archived tags free their normalized names, but restoring a collision is rejected.
	expect(send("POST", basicRoot+"/tags", map[string]any{"id": "e0000000-0000-4000-8000-000000000002", "name": "trip"}, action(), a.Credential), 201, "")
	expect(send("PUT", basicRoot+"/tags/"+tagID, map[string]any{"expected_version": "2", "name": "Trip", "archived": false}, action(), a.Credential), 422, "validation_error")
	foreignTag := "e0000000-0000-4000-8000-000000000099"
	expect(send("POST", foreignRoot+"/tags", map[string]any{"id": foreignTag, "name": "Private"}, action(), foreign.Credential), 201, "")
	expect(send("POST", txRoot, transaction("income", accountID, "1", nil, []string{foreignTag}), action(), a.Credential), 404, "not_found")
	// Pending is seeded as a valid DB fixture; manual HTTP commands remain posted only.
	pendingID := "c0000000-0000-4000-8000-000000000007"
	expect(send("POST", root, input(pendingID, "0"), action(), a.Credential), 201, "")
	seed, e := db.Begin(ctx)
	if e != nil {
		t.Fatal(e)
	}
	for _, step := range []struct {
		sql  string
		args []any
	}{
		{`INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone) VALUES('f4000000-0000-4000-8000-000000000001',$1,'income','pending','2026-02-01T12:00:00Z','UTC')`, []any{a.Workspace.ID}},
		{`INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) VALUES('f4000000-0000-4000-8000-000000000002',$1,'f4000000-0000-4000-8000-000000000001',$2,9000000000000000)`, []any{a.Workspace.ID, pendingID}},
		{`INSERT INTO allocations(id,workspace_id,transaction_id,category_id,amount_minor) VALUES('f4000000-0000-4000-8000-000000000003',$1,'f4000000-0000-4000-8000-000000000001',NULL,9000000000000000)`, []any{a.Workspace.ID}},
	} {
		if _, e = seed.Exec(ctx, step.sql, step.args...); e != nil {
			_ = seed.Rollback(ctx)
			t.Fatal(e)
		}
	}
	if e = seed.Commit(ctx); e != nil {
		t.Fatal(e)
	}
	pendingBefore := send("GET", root+"/"+pendingID, nil, "", a.Credential)
	expect(pendingBefore, 200, "")
	if pendingBefore.body["pending_delta_minor"] != "9000000000000000" || pendingBefore.body["posted_balance_minor"] != "0" {
		t.Fatal("pending fixture not materialized")
	}
	expect(send("POST", txRoot, transaction("income", pendingID, "1", nil, []string{}), action(), a.Credential), 422, "amount_out_of_range")
	pendingAfter := send("GET", root+"/"+pendingID, nil, "", a.Credential)
	if !reflect.DeepEqual(pendingBefore.body, pendingAfter.body) {
		t.Fatal("projected overflow changed account")
	}
	// Read versions/entry/parts as one snapshot while the writer changes the amount.
	snapshotBody := transaction("expense", failureID, "100", nil, []string{})
	expect(send("POST", txRoot, snapshotBody, action(), a.Credential), 201, "")
	snapshotID := snapshotBody["id"].(string)
	snapshotReplace := map[string]any{}
	for k, v := range snapshotBody {
		if k != "id" {
			snapshotReplace[k] = v
		}
	}
	snapshotReplace["expected_parent_version"] = nil
	writerKeys := make([]string, 20)
	for i := range writerKeys {
		writerKeys[i] = action()
	}
	writerErrors := make(chan int, 1)
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i, key := range writerKeys {
			snapshotReplace["expected_version"] = strconv.Itoa(i + 1)
			value := strconv.Itoa(101 + i)
			snapshotReplace["amount_minor"] = value
			snapshotReplace["allocations"].([]any)[0].(map[string]any)["amount_minor"] = value
			res := send("PUT", txRoot+"/"+snapshotID, snapshotReplace, key, a.Credential)
			if res.status != 200 {
				writerErrors <- res.status
				return
			}
		}
	}()
	for range 25 {
		res := send("GET", txRoot+"/"+snapshotID, nil, "", a.Credential)
		expect(res, 200, "")
		version, _ := strconv.ParseInt(res.body["version"].(string), 10, 64)
		amount, _ := strconv.ParseInt(res.body["entries"].([]any)[0].(map[string]any)["amount_minor"].(string), 10, 64)
		part, _ := strconv.ParseInt(res.body["allocations"].([]any)[0].(map[string]any)["amount_minor"].(string), 10, 64)
		if -amount != part || part != 99+version {
			t.Fatal("GET mixed versions, entries and allocations")
		}
	}
	wg.Wait()
	select {
	case status := <-writerErrors:
		t.Fatalf("snapshot writer HTTP %d", status)
	default:
	}
	// Transaction history: AND filters, exact category, half-open dates, literal search and stable ties.
	filtered := send("GET", txRoot+"?account_id="+zeroID+"&category_id="+categoryID+"&tag_id="+tagID+"&kind=expense&status=posted&from=2026-02-01T12:00:00Z&to=2026-02-01T12:00:01Z&q=EDITED", nil, "", a.Credential)
	expect(filtered, 200, "")
	if len(filtered.body["items"].([]any)) != 1 || filtered.body["items"].([]any)[0].(map[string]any)["id"] != expenseID {
		t.Fatal("combined transaction filters differ")
	}
	boundary := send("GET", txRoot+"?account_id="+zeroID+"&to=2026-02-01T12:00:00Z", nil, "", a.Credential)
	expect(boundary, 200, "")
	if len(boundary.body["items"].([]any)) != 1 || boundary.body["items"].([]any)[0].(map[string]any)["kind"] != "opening" {
		t.Fatal("to boundary is not exclusive")
	}
	wildcard := send("GET", txRoot+"?q=%25", nil, "", a.Credential)
	expect(wildcard, 200, "")
	if len(wildcard.body["items"].([]any)) != 0 {
		t.Fatal("search treated percent as wildcard")
	}
	txPage := send("GET", txRoot+"?limit=1&kind=expense", nil, "", a.Credential)
	expect(txPage, 200, "")
	txCursor := txPage.body["next_cursor"].(string)
	txPage2 := send("GET", txRoot+"?limit=2&kind=expense&cursor="+neturl.QueryEscape(txCursor), nil, "", a.Credential)
	expect(txPage2, 200, "")
	if txPage.body["items"].([]any)[0].(map[string]any)["id"] == txPage2.body["items"].([]any)[0].(map[string]any)["id"] {
		t.Fatal("transaction keyset repeated row")
	}
	expect(send("GET", txRoot+"?kind=income&cursor="+neturl.QueryEscape(txCursor), nil, "", a.Credential), 400, "cursor_invalid")
	expect(send("GET", txRoot+"?category_id="+foreignCategory, nil, "", a.Credential), 404, "not_found")
	expect(send("GET", txRoot+"?from=2026-02-01T00:00:00Z&to=2026-02-01T00:00:00Z", nil, "", a.Credential), 400, "bad_request")
	expect(send("GET", txRoot+"?kind=expense&kind=income", nil, "", a.Credential), 400, "bad_request")

	// Long Unicode filters still produce cursors within the API's 2048-byte bound.
	unicodeSearch := strings.Repeat("🤖", 200)
	for range 2 {
		b := transaction("expense", failureID, "1", nil, []string{})
		b["note"] = unicodeSearch
		expect(send("POST", txRoot, b, action(), a.Credential), 201, "")
	}
	unicodePage := send("GET", txRoot+"?limit=1&q="+neturl.QueryEscape(unicodeSearch), nil, "", a.Credential)
	expect(unicodePage, 200, "")
	unicodeCursor := unicodePage.body["next_cursor"].(string)
	if len(unicodeCursor) > 2048 {
		t.Fatal("Unicode filter made oversized cursor")
	}
	expect(send("GET", txRoot+"?limit=1&q="+neturl.QueryEscape(unicodeSearch)+"&cursor="+neturl.QueryEscape(unicodeCursor), nil, "", a.Credential), 200, "")
	expect(send("GET", basicRoot+"/categories/"+categoryID, nil, "", a.Credential), 200, "")
	expect(send("GET", basicRoot+"/tags/"+tagID, nil, "", a.Credential), 200, "")
	var orphanChanges, incompleteGroups, appliedWithoutGroup int
	if e = db.QueryRow(ctx, `SELECT (SELECT count(*) FROM sync_changes c LEFT JOIN sync_groups g ON g.workspace_id=c.workspace_id AND g.generation_id=c.generation_id AND g.sequence=c.sequence WHERE g.sequence IS NULL),(SELECT count(*) FROM sync_groups g WHERE g.change_count<>(SELECT count(*) FROM sync_changes c WHERE c.workspace_id=g.workspace_id AND c.generation_id=g.generation_id AND c.sequence=g.sequence)),(SELECT count(*) FROM actions a WHERE a.state='applied' AND NOT EXISTS(SELECT 1 FROM sync_groups g WHERE g.workspace_id=a.workspace_id AND g.action_id=a.action_id AND g.actor_user_id=a.actor_user_id))`).Scan(&orphanChanges, &incompleteGroups, &appliedWithoutGroup); e != nil {
		t.Fatal(e)
	}
	if orphanChanges != 0 || incompleteGroups != 0 || appliedWithoutGroup != 0 {
		t.Fatal("financial mutations left incomplete journal/receipt")
	}
	retainedExpense := send("POST", txRoot, expenseOriginal, expenseKey, a.Credential)
	expect(retainedExpense, 201, "")
	if retainedExpense.replayed != "true" || !reflect.DeepEqual(expense.body, retainedExpense.body) {
		t.Fatal("retained financial replay changed after editing/archive")
	}
	zeroOpeningID := zero.body["transactions"].([]any)[0].(map[string]any)["id"].(string)
	expect(send("PUT", txRoot+"/"+zeroOpeningID, replacement, action(), a.Credential), 422, "validation_error")
	// Replaying an existing financial success still requires active access.
	if _, e = db.Exec(ctx, `UPDATE memberships SET revoked_at=clock_timestamp() WHERE workspace_id=$1 AND user_id=$2`, a.Workspace.ID, a.Profile.ID); e != nil {
		t.Fatal(e)
	}
	expect(send("POST", root, body, firstAction, a.Credential), 404, "not_found")
	expect(send("GET", root+"/"+accountID, nil, "", a.Credential), 404, "not_found")
	// Basic route responses use UTC instants, string versions and canonical money.
	for _, field := range []string{"opened_at", "created_at", "updated_at"} {
		s, ok := account[field].(string)
		if !ok || !strings.HasSuffix(s, "Z") {
			t.Fatalf("non-UTC %s", field)
		}
	}
	if path := os.Getenv("LEDGER_RESPONSE_FILE"); path != "" {
		if e = os.MkdirAll(filepath.Dir(path), 0755); e != nil {
			t.Fatal(e)
		}
		data, e := json.Marshal(observations)
		if e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(path, data, 0644); e != nil {
			t.Fatal(e)
		}
	}
	t.Log("PASS accounts and manual expense/income/parts/classification: exact balances, replay/races, archives, history, cycles, scope, limits and rollback on PostgreSQL")
}
