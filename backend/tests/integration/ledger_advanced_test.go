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
	"reflect"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Advanced acceptance covers transfer/fee creation and per-part refunds.
// All advanced accounting commands run through the actual TLS API and PostgreSQL.
func TestLedgerAdvanced(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	cfg, e := pgxpool.ParseConfig(url)
	if e != nil || cfg.ConnConfig.Database != "accounting_test" {
		t.Fatal("requires isolated accounting_test")
	}
	admin, e := pgxpool.NewWithConfig(ctx, cfg)
	if e != nil {
		t.Fatal("database connection failed")
	}
	defer admin.Close()
	schema := fmt.Sprintf("ledger_advanced_%d", time.Now().UnixNano())
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
	user, e := auth.Register(ctx, "transfer@example.test", "synthetic transfer password", "advanced", "UTC")
	if e != nil {
		t.Fatal(e)
	}
	other, e := auth.Register(ctx, "other-transfer@example.test", "synthetic other password", "other", "UTC")
	if e != nil {
		t.Fatal(e)
	}
	handler, e := platform.NewAPIHandler(db, platform.Config{Environment: "test", PublicOrigin: "https://advanced.example.test", AuthHashConcurrency: 2}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	server := httptest.NewTLSServer(handler)
	defer server.Close()
	type response struct {
		status int
		body   map[string]any
		replay string
	}
	type observed struct {
		Schema string         `json:"schema"`
		Body   map[string]any `json:"body"`
	}
	observations := []observed{}
	var mutex sync.Mutex
	root := "/workspaces/" + user.Workspace.ID
	foreignRoot := "/workspaces/" + other.Workspace.ID
	send := func(method, path string, body any, key, token string) response {
		t.Helper()
		var data []byte
		if body != nil {
			data, _ = json.Marshal(body)
		}
		req, e := http.NewRequestWithContext(ctx, method, server.URL+"/api/v1"+path, bytes.NewReader(data))
		if e != nil {
			t.Fatal("request construction failed")
		}
		req.Header.Set("Authorization", "Bearer "+token)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if key != "" {
			req.Header.Set("Idempotency-Key", key)
			generation := user.Workspace.Generation
			if strings.HasPrefix(path, foreignRoot+"/") {
				generation = other.Workspace.Generation
			}
			req.Header.Set("X-Sync-Generation", generation)
		}
		res, e := server.Client().Do(req)
		if e != nil {
			t.Fatal("TLS request failed")
		}
		defer res.Body.Close()
		out := response{status: res.StatusCode, replay: res.Header.Get("Idempotency-Replayed")}
		if json.NewDecoder(res.Body).Decode(&out.body) != nil {
			t.Fatal("response decoding failed")
		}
		name := "Error"
		if out.status >= 200 && out.status < 300 {
			name = "MutationResult"
			if method == "GET" {
				name = "Account"
				if strings.Contains(path, "/transactions") {
					name = "Transaction"
					if strings.HasSuffix(strings.Split(path, "?")[0], "/transactions") {
						name = "TransactionList"
					}
				}
			}
		}
		mutex.Lock()
		observations = append(observations, observed{name, out.body})
		mutex.Unlock()
		return out
	}
	expect := func(res response, status int, code string) {
		t.Helper()
		if res.status != status || code != "" && res.body["code"] != code {
			t.Fatalf("expected %d %s; got %d %v", status, code, res.status, res.body["code"])
		}
	}
	var counter atomic.Int64
	id := func() string { return fmt.Sprintf("e1000000-0000-4000-8000-%012d", counter.Add(1)) }
	account := func(currency, amount string, foreign bool) string {
		t.Helper()
		aid := id()
		path, token := root, user.Credential
		if foreign {
			path, token = foreignRoot, other.Credential
		}
		expect(send("POST", path+"/accounts", map[string]any{"id": aid, "name": "Synthetic " + currency, "type": "bank", "currency": currency, "opened_at": "2026-01-01T00:00:00Z", "occurred_timezone": "UTC", "opening_balance_minor": amount}, id(), token), 201, "")
		return aid
	}
	readAccount := func(aid string) map[string]any {
		t.Helper()
		res := send("GET", root+"/accounts/"+aid, nil, "", user.Credential)
		expect(res, 200, "")
		return res.body
	}
	assertBalance := func(aid, amount string) {
		t.Helper()
		if readAccount(aid)["posted_balance_minor"] != amount {
			t.Fatal("balance mismatch")
		}
	}
	transfer := func(source, target, s, d string) map[string]any {
		return map[string]any{"id": id(), "kind": "transfer", "source_account_id": source, "target_account_id": target, "source_amount_minor": s, "target_amount_minor": d, "occurred_at": "2026-01-02T00:00:00Z", "occurred_timezone": "UTC", "note": "Synthetic transfer", "payee": "", "tag_ids": []string{}, "rate": nil, "fee": nil}
	}
	fee := func(aid, amount string) map[string]any {
		return map[string]any{"id": id(), "account_id": aid, "amount_minor": amount, "note": "Synthetic fee", "tag_ids": []string{}, "allocations": []any{map[string]any{"id": id(), "category_id": nil, "amount_minor": amount}}}
	}
	post := func(body map[string]any, key string) response {
		return send("POST", root+"/transactions", body, key, user.Credential)
	}
	findTransaction := func(res response, tid string) map[string]any {
		t.Helper()
		for _, v := range res.body["transactions"].([]any) {
			tx := v.(map[string]any)
			if tx["id"] == tid {
				return tx
			}
		}
		t.Fatal("transaction missing from mutation")
		return nil
	}
	assertRejected := func(body map[string]any, status int, code string, accounts ...string) {
		t.Helper()
		before := []map[string]any{}
		for _, aid := range accounts {
			before = append(before, readAccount(aid))
		}
		key := id()
		res := post(body, key)
		expect(res, status, code)
		for i, aid := range accounts {
			if !reflect.DeepEqual(before[i], readAccount(aid)) {
				t.Fatal("rejection changed account aggregate")
			}
		}
		var txCount, groups, applied int
		if e := db.QueryRow(ctx, `SELECT (SELECT count(*) FROM transactions WHERE workspace_id=$1 AND id=$2),(SELECT count(*) FROM sync_groups WHERE workspace_id=$1 AND action_id=$3),(SELECT count(*) FROM actions WHERE workspace_id=$1 AND action_id=$3 AND state='applied')`, user.Workspace.ID, body["id"], key).Scan(&txCount, &groups, &applied); e != nil {
			t.Fatal(e)
		}
		if txCount != 0 || groups != 0 || applied != 0 {
			t.Fatal("rejection retained transaction/group/applied receipt")
		}
		if status == 422 || status == 409 {
			var count int
			if e := db.QueryRow(ctx, `SELECT count(*) FROM actions WHERE workspace_id=$1 AND action_id=$2`, user.Workspace.ID, key).Scan(&count); e != nil {
				t.Fatal(e)
			}
			if count == 1 {
				again := post(body, key)
				expect(again, status, code)
				if again.replay != "true" {
					t.Fatal("terminal rejection not replayed")
				}
			}
		}
	}
	// Equal-currency transfer and original/concurrent replay.
	source, target := account("EUR", "100000", false), account("EUR", "0", false)
	body := transfer(source, target, "10000", "10000")
	key := id()
	first := post(body, key)
	expect(first, 201, "")
	assertBalance(source, "90000")
	assertBalance(target, "10000")
	transaction := findTransaction(first, body["id"].(string))
	if len(transaction["entries"].([]any)) != 2 || len(transaction["allocations"].([]any)) != 0 {
		t.Fatal("transfer shape mismatch")
	}
	if !reflect.DeepEqual(transaction["rate"], map[string]any{"numerator": "1", "denominator": "1"}) {
		t.Fatal("same-currency rate mismatch")
	}
	beforeSource, beforeTarget := readAccount(source), readAccount(target)
	again := post(body, key)
	expect(again, 201, "")
	if again.replay != "true" || !reflect.DeepEqual(first.body, again.body) {
		t.Fatal("original response not replayed")
	}
	if !reflect.DeepEqual(beforeSource, readAccount(source)) || !reflect.DeepEqual(beforeTarget, readAccount(target)) {
		t.Fatal("replay changed versions")
	}
	concurrent := transfer(source, target, "1000", "1000")
	concurrentKey := id()
	results := make(chan response, 2)
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); results <- post(concurrent, concurrentKey) }()
	}
	wg.Wait()
	close(results)
	replays := 0
	for res := range results {
		expect(res, 201, "")
		if res.replay == "true" {
			replays++
		}
	}
	if replays != 1 {
		t.Fatal("same-key concurrency did not execute once")
	}
	assertBalance(source, "89000")
	assertBalance(target, "11000")
	// DR-F01 and inexact ratio: complete rollback with durable rejection.
	assertRejected(transfer(source, source, "10000", "10000"), 422, "validation_error", source)
	assertRejected(transfer(source, target, "10000", "9999"), 422, "validation_error", source, target)
	usd := account("USD", "0", false)
	fx := transfer(source, usd, "300", "100")
	fx["rate"] = map[string]any{"numerator": "2", "denominator": "6"}
	fxRes := post(fx, id())
	expect(fxRes, 201, "")
	if !reflect.DeepEqual(findTransaction(fxRes, fx["id"].(string))["rate"], map[string]any{"numerator": "1", "denominator": "3"}) {
		t.Fatal("FX not reduced exactly")
	}
	wrong := transfer(source, usd, "300", "100")
	wrong["rate"] = map[string]any{"numerator": "33333333", "denominator": "100000000"}
	assertRejected(wrong, 422, "validation_error", source, usd)
	jpy, kwd := account("JPY", "100", false), account("KWD", "0", false)
	scaleRes := post(transfer(jpy, kwd, "100", "100000"), id())
	expect(scaleRes, 201, "")
	if !reflect.DeepEqual(scaleRes.body["transactions"].([]any)[0].(map[string]any)["rate"], map[string]any{"numerator": "1", "denominator": "1"}) {
		t.Fatal("JPY/KWD scale conversion mismatch")
	}
	// Real category/tag references are attached to parent and fee independently.
	categoryID, parentTag, feeTag := id(), id(), id()
	expect(send("POST", root+"/categories", map[string]any{"id": categoryID, "name": "Synthetic fee category", "parent_id": nil}, id(), user.Credential), 201, "")
	for _, tagID := range []string{parentTag, feeTag} {
		expect(send("POST", root+"/tags", map[string]any{"id": tagID, "name": "Synthetic " + tagID}, id(), user.Credential), 201, "")
	}
	// Commission on source shares its one version/balance_version bump.
	withFee := transfer(source, target, "10000", "10000")
	nested := fee(source, "123")
	withFee["fee"] = nested
	withFee["tag_ids"] = []string{parentTag}
	nested["tag_ids"] = []string{feeTag}
	nested["allocations"].([]any)[0].(map[string]any)["category_id"] = categoryID
	beforeVersion := readAccount(source)["version"]
	feeKey := id()
	feeResult := post(withFee, feeKey)
	expect(feeResult, 201, "")
	if len(feeResult.body["accounts"].([]any)) != 2 || len(feeResult.body["transactions"].([]any)) != 2 {
		t.Fatal("fee/transfer not published together")
	}
	if readAccount(source)["version"] == beforeVersion {
		t.Fatal("account not bumped")
	}
	// Check exact bump through SQL rather than only checking that it changed.
	var sourceVersion string
	if e = db.QueryRow(ctx, `SELECT version::text FROM accounts WHERE workspace_id=$1 AND id=$2`, user.Workspace.ID, source).Scan(&sourceVersion); e != nil {
		t.Fatal(e)
	}
	// Previous source version is a decimal string with a small synthetic value.
	var prior int
	if _, e = fmt.Sscan(beforeVersion.(string), &prior); e != nil {
		t.Fatal(e)
	}
	if sourceVersion != fmt.Sprint(prior+1) {
		t.Fatal("shared fee account bumped more than once")
	}
	assertBalance(source, "78577")
	assertBalance(target, "21000")
	parent := findTransaction(feeResult, withFee["id"].(string))
	child := findTransaction(feeResult, nested["id"].(string))
	if parent["fee_transaction_id"] != nested["id"] || child["parent_transaction_id"] != withFee["id"] || child["occurred_at"] != parent["occurred_at"] || child["occurred_timezone"] != parent["occurred_timezone"] {
		t.Fatal("fee identity/date linkage mismatch")
	}
	var groupChanges int
	if e = db.QueryRow(ctx, `SELECT change_count FROM sync_groups WHERE workspace_id=$1 AND sequence=$2`, user.Workspace.ID, feeResult.body["sync_group_sequence"]).Scan(&groupChanges); e != nil {
		t.Fatal(e)
	}
	if groupChanges != 4 {
		t.Fatal("fee journal incomplete")
	}
	// A third, differently denominated account may pay the fee.
	third := account("JPY", "1000", false)
	thirdBody := transfer(source, usd, "300", "100")
	thirdBody["fee"] = fee(third, "10")
	thirdRes := post(thirdBody, id())
	expect(thirdRes, 201, "")
	if len(thirdRes.body["accounts"].([]any)) != 3 {
		t.Fatal("third fee account missing")
	}
	assertBalance(third, "990")
	// Net-zero target at the limit must not fail because of temporary gross sum.
	maxTarget := account("EUR", "9000000000000000", false)
	net := transfer(source, maxTarget, "1", "1")
	net["fee"] = fee(maxTarget, "1")
	expect(post(net, id()), 201, "")
	assertBalance(maxTarget, "9000000000000000")
	assertRejected(transfer(source, maxTarget, "1", "1"), 422, "amount_out_of_range", source, maxTarget)
	minimum := account("EUR", "-9000000000000000", false)
	assertRejected(transfer(minimum, target, "1", "1"), 422, "amount_out_of_range", minimum, target)
	// Invalid fee and UUID collisions roll back the already-inserted parent.
	split := transfer(source, target, "100", "100")
	splitFee := fee(source, "10")
	splitFee["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "9"
	split["fee"] = splitFee
	assertRejected(split, 422, "validation_error", source, target)
	collision := transfer(source, target, "100", "100")
	collisionFee := fee(source, "10")
	collisionFee["id"] = nested["id"]
	collision["fee"] = collisionFee
	assertRejected(collision, 409, "validation_error", source, target)
	partCollision := transfer(source, target, "100", "100")
	pcFee := fee(source, "10")
	pcFee["allocations"].([]any)[0].(map[string]any)["id"] = nested["allocations"].([]any)[0].(map[string]any)["id"]
	partCollision["fee"] = pcFee
	assertRejected(partCollision, 422, "validation_error", source, target)
	// Foreign account references do not return any ledger payload.
	foreignAccount := account("EUR", "1000", true)
	assertRejected(transfer(source, foreignAccount, "1", "1"), 404, "not_found", source)
	foreignFee := transfer(source, target, "1", "1")
	foreignFee["fee"] = fee(foreignAccount, "1")
	assertRejected(foreignFee, 404, "not_found", source, target)
	future := transfer(source, target, "1", "1")
	future["occurred_at"] = "2100-01-01T00:00:00Z"
	assertRejected(future, 422, "validation_error", source, target)
	archived := account("EUR", "1000", false)
	a := readAccount(archived)
	expect(send("PUT", root+"/accounts/"+archived, map[string]any{"expected_version": a["version"], "name": a["name"], "type": a["type"], "archived": true}, id(), user.Credential), 200, "")
	archiveFee := transfer(source, target, "1", "1")
	archiveFee["fee"] = fee(archived, "1")
	assertRejected(archiveFee, 422, "account_archived", source, target, archived)
	// New archived/foreign classification references are rejected before financial writes.
	expect(send("PUT", root+"/categories/"+categoryID, map[string]any{"expected_version": "1", "name": "Synthetic fee category", "parent_id": nil, "archived": true}, id(), user.Credential), 200, "")
	archivedCategory := transfer(source, target, "1", "1")
	categoryFee := fee(source, "1")
	categoryFee["allocations"].([]any)[0].(map[string]any)["category_id"] = categoryID
	archivedCategory["fee"] = categoryFee
	assertRejected(archivedCategory, 422, "validation_error", source, target)
	expect(send("PUT", root+"/tags/"+parentTag, map[string]any{"expected_version": "1", "name": "Synthetic " + parentTag, "archived": true}, id(), user.Credential), 200, "")
	archivedTag := transfer(source, target, "1", "1")
	archivedTag["tag_ids"] = []string{parentTag}
	assertRejected(archivedTag, 422, "validation_error", source, target)
	otherCategory, otherTag := id(), id()
	expect(send("POST", foreignRoot+"/categories", map[string]any{"id": otherCategory, "name": "Foreign fee category", "parent_id": nil}, id(), other.Credential), 201, "")
	expect(send("POST", foreignRoot+"/tags", map[string]any{"id": otherTag, "name": "Foreign fee tag"}, id(), other.Credential), 201, "")
	foreignCategory := transfer(source, target, "1", "1")
	foreignCategoryFee := fee(source, "1")
	foreignCategoryFee["allocations"].([]any)[0].(map[string]any)["category_id"] = otherCategory
	foreignCategory["fee"] = foreignCategoryFee
	assertRejected(foreignCategory, 404, "not_found", source, target)
	foreignTag := transfer(source, target, "1", "1")
	foreignTagFee := fee(source, "1")
	foreignTagFee["tag_ids"] = []string{otherTag}
	foreignTag["fee"] = foreignTagFee
	assertRejected(foreignTag, 404, "not_found", source, target)
	// Database failure after parent and fee writes leaves no receipt; same key succeeds later.
	fault := transfer(source, target, "100", "100")
	faultFee := fee(source, "10")
	fault["fee"] = faultFee
	faultKey := id()
	priorSource, priorTarget := readAccount(source), readAccount(target)
	sql := `CREATE FUNCTION fail_transfer_fee() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.transaction_id='` + faultFee["id"].(string) + `'::uuid THEN RAISE EXCEPTION 'synthetic transfer fault' USING ERRCODE='XX000'; END IF; RETURN NEW; END $$; CREATE TRIGGER transfer_fault BEFORE INSERT ON allocations FOR EACH ROW EXECUTE FUNCTION fail_transfer_fee();`
	if _, e = db.Exec(ctx, sql); e != nil {
		t.Fatal(e)
	}
	expect(post(fault, faultKey), 503, "service_unavailable")
	if !reflect.DeepEqual(priorSource, readAccount(source)) || !reflect.DeepEqual(priorTarget, readAccount(target)) {
		t.Fatal("DB failure changed balances/versions")
	}
	var receiptCount, parentCount, feeCount int
	if e = db.QueryRow(ctx, `SELECT (SELECT count(*) FROM actions WHERE workspace_id=$1 AND action_id=$2),(SELECT count(*) FROM transactions WHERE workspace_id=$1 AND id=$3),(SELECT count(*) FROM transactions WHERE workspace_id=$1 AND id=$4)`, user.Workspace.ID, faultKey, fault["id"], faultFee["id"]).Scan(&receiptCount, &parentCount, &feeCount); e != nil {
		t.Fatal(e)
	}
	if receiptCount != 0 || parentCount != 0 || feeCount != 0 {
		t.Fatal("transient failure retained partial write")
	}
	if _, e = db.Exec(ctx, `DROP TRIGGER transfer_fault ON allocations; DROP FUNCTION fail_transfer_fee()`); e != nil {
		t.Fatal(e)
	}
	expect(post(fault, faultKey), 201, "")
	// Existing list/keyset filters naturally include both legs and separate fee.
	history := send("GET", root+"/transactions?kind=transfer&account_id="+source+"&limit=2", nil, "", user.Credential)
	expect(history, 200, "")
	if len(history.body["items"].([]any)) != 2 || history.body["next_cursor"] == nil {
		t.Fatal("transfer filter pagination missing")
	}
	for _, item := range history.body["items"].([]any) {
		if item.(map[string]any)["kind"] != "transfer" {
			t.Fatal("fee included in transfer-only list")
		}
	}
	childRead := send("GET", root+"/transactions/"+nested["id"].(string), nil, "", user.Credential)
	expect(childRead, 200, "")
	if !reflect.DeepEqual(childRead.body, child) {
		t.Fatal("fee GET not equal to published aggregate")
	}
	// Per-part refunds: category inherited even after archive, exact remaining and versions.
	readTransaction := func(tid string) map[string]any {
		t.Helper()
		res := send("GET", root+"/transactions/"+tid, nil, "", user.Credential)
		expect(res, 200, "")
		return res.body
	}
	clone := func(body map[string]any) map[string]any {
		data, _ := json.Marshal(body)
		var copy map[string]any
		_ = json.Unmarshal(data, &copy)
		return copy
	}
	food := id()
	expect(send("POST", root+"/categories", map[string]any{"id": food, "name": "Refund food", "parent_id": nil}, id(), user.Credential), 201, "")
	refundSource, refundDestination := account("EUR", "100000", false), account("EUR", "0", false)
	expenseID, foodPart, homePart := id(), id(), id()
	expenseBody := map[string]any{"id": expenseID, "kind": "expense", "account_id": refundSource, "amount_minor": "6000", "occurred_at": "2026-01-02T00:00:00Z", "occurred_timezone": "UTC", "note": "Refund original expense", "payee": "", "tag_ids": []string{}, "allocations": []any{map[string]any{"id": foodPart, "category_id": food, "amount_minor": "4000"}, map[string]any{"id": homePart, "category_id": nil, "amount_minor": "2000"}}}
	expect(post(expenseBody, id()), 201, "")
	expect(send("PUT", root+"/categories/"+food, map[string]any{"expected_version": "1", "name": "Refund food", "parent_id": nil, "archived": true}, id(), user.Credential), 200, "")
	refund := func(parent, part, amount, aid, version string) map[string]any {
		return map[string]any{"id": id(), "kind": "refund", "account_id": aid, "parent_transaction_id": parent, "amount_minor": amount, "expected_parent_version": version, "expected_transfer_version": nil, "occurred_at": "2026-01-03T00:00:00Z", "occurred_timezone": "UTC", "note": "Synthetic refund", "payee": "", "tag_ids": []string{}, "allocations": []any{map[string]any{"id": id(), "original_allocation_id": part, "amount_minor": amount}}}
	}
	remaining := func(parent, part string) string {
		t.Helper()
		for _, a := range readTransaction(parent)["allocations"].([]any) {
			p := a.(map[string]any)
			if p["id"] == part {
				return p["remaining_refundable_minor"].(string)
			}
		}
		t.Fatal("original part missing")
		return ""
	}
	firstRefund := refund(expenseID, foodPart, "1500", refundSource, "1")
	refundKey := id()
	firstRefundResult := post(firstRefund, refundKey)
	expect(firstRefundResult, 201, "")
	assertBalance(refundSource, "95500")
	if readTransaction(expenseID)["version"] != "2" || remaining(expenseID, foodPart) != "2500" {
		t.Fatal("parent version/remaining not published")
	}
	returned := findTransaction(firstRefundResult, firstRefund["id"].(string))
	returnedPart := returned["allocations"].([]any)[0].(map[string]any)
	if returnedPart["category_id"] != food || returnedPart["original_allocation_id"] != foodPart || returnedPart["remaining_refundable_minor"] != "0" || returned["entries"].([]any)[0].(map[string]any)["amount_minor"] != "1500" {
		t.Fatal("refund inheritance or positive entry mismatch")
	}
	parentAccount := readAccount(refundSource)
	secondRefund := refund(expenseID, foodPart, "2000", refundDestination, "2")
	expect(post(secondRefund, id()), 201, "")
	assertBalance(refundDestination, "2000")
	if !reflect.DeepEqual(parentAccount, readAccount(refundSource)) {
		t.Fatal("refund to another account bumped original account")
	}
	if remaining(expenseID, foodPart) != "500" || remaining(expenseID, homePart) != "2000" {
		t.Fatal("remaining per-part mismatch")
	}
	parentBefore := readTransaction(expenseID)
	assertRejected(refund(expenseID, foodPart, "1000", refundDestination, "3"), 422, "validation_error", refundSource, refundDestination)
	if !reflect.DeepEqual(parentBefore, readTransaction(expenseID)) {
		t.Fatal("DR-F04 rejection changed parent")
	}
	refundReplay := post(firstRefund, refundKey)
	expect(refundReplay, 201, "")
	if refundReplay.replay != "true" || !reflect.DeepEqual(firstRefundResult.body, refundReplay.body) {
		t.Fatal("old refund receipt not replayed after later refund")
	}
	// A stale parent version conflicts; financial/account/date/parts edits are blocked.
	assertRejected(refund(expenseID, homePart, "100", refundDestination, "1"), 409, "version_conflict", refundDestination)
	edit := clone(expenseBody)
	delete(edit, "id")
	edit["expected_version"] = "3"
	edit["expected_parent_version"] = nil
	blockedEdits := []map[string]any{}
	changedAccount := clone(edit)
	changedAccount["account_id"] = refundDestination
	blockedEdits = append(blockedEdits, changedAccount)
	changedDate := clone(edit)
	changedDate["occurred_at"] = "2026-01-03T00:00:00Z"
	blockedEdits = append(blockedEdits, changedDate)
	changedAmount := clone(edit)
	changedAmount["amount_minor"] = "6001"
	changedAmount["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "4001"
	blockedEdits = append(blockedEdits, changedAmount)
	changedCategory := clone(edit)
	changedCategory["allocations"].([]any)[0].(map[string]any)["category_id"] = nil
	blockedEdits = append(blockedEdits, changedCategory)
	changedID := clone(edit)
	changedID["allocations"].([]any)[0].(map[string]any)["id"] = id()
	blockedEdits = append(blockedEdits, changedID)
	for _, body := range blockedEdits {
		expect(send("PUT", root+"/transactions/"+expenseID, body, id(), user.Credential), 409, "dependent_transactions")
	}
	if !reflect.DeepEqual(parentBefore, readTransaction(expenseID)) {
		t.Fatal("blocked parent edit changed original")
	}
	// Reordered unchanged parts + metadata remain editable, including old archived category.
	noteEdit := clone(edit)
	parts := noteEdit["allocations"].([]any)
	noteEdit["allocations"] = []any{parts[1], parts[0]}
	noteEdit["note"] = "Allowed note after refunds"
	beforeMetadata := readAccount(refundSource)
	expect(send("PUT", root+"/transactions/"+expenseID, noteEdit, id(), user.Credential), 200, "")
	if !reflect.DeepEqual(beforeMetadata, readAccount(refundSource)) || remaining(expenseID, foodPart) != "500" {
		t.Fatal("metadata edit changed money/refund limit")
	}
	// Two independent refunds with the same observed parent: exactly one can win.
	race1, race2 := refund(expenseID, foodPart, "400", refundDestination, "4"), refund(expenseID, foodPart, "400", refundDestination, "4")
	raceKeys := []string{id(), id()}
	raceBodies := []map[string]any{race1, race2}
	raceResults := make(chan response, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) { defer wg.Done(); raceResults <- post(raceBodies[i], raceKeys[i]) }(i)
	}
	wg.Wait()
	close(raceResults)
	applied, conflicts := 0, 0
	for res := range raceResults {
		if res.status == 201 {
			applied++
		} else {
			expect(res, 409, "version_conflict")
			conflicts++
		}
	}
	if applied != 1 || conflicts != 1 || remaining(expenseID, foodPart) != "100" {
		t.Fatal("concurrent refunds oversubscribed original")
	}
	// Invalid sum, wrong currency, date, references, or overflowing destination roll back.
	wrongSum := refund(expenseID, homePart, "100", refundDestination, "5")
	wrongSum["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "99"
	assertRejected(wrongSum, 422, "validation_error", refundDestination)
	assertRejected(refund(expenseID, homePart, "1", usd, "5"), 422, "validation_error", usd)
	early := refund(expenseID, homePart, "1", refundDestination, "5")
	early["occurred_at"] = "2026-01-01T00:00:00Z"
	assertRejected(early, 422, "validation_error", refundDestination)
	futureRefund := refund(expenseID, homePart, "1", refundDestination, "5")
	futureRefund["occurred_at"] = "2100-01-01T00:00:00Z"
	assertRejected(futureRefund, 422, "validation_error", refundDestination)
	assertRejected(refund(expenseID, homePart, "1", foreignAccount, "5"), 404, "not_found", refundDestination)
	assertRejected(refund(expenseID, id(), "1", refundDestination, "5"), 404, "not_found", refundDestination)
	assertRejected(refund(expenseID, homePart, "1", maxTarget, "5"), 422, "amount_out_of_range", refundDestination, maxTarget)
	unwantedTransfer := refund(expenseID, homePart, "1", refundDestination, "5")
	unwantedTransfer["expected_transfer_version"] = "1"
	assertRejected(unwantedTransfer, 422, "validation_error", refundDestination)
	// A fee refund checks both versions and republishes fee + transfer atomically.
	feePartID := nested["allocations"].([]any)[0].(map[string]any)["id"].(string)
	missingTransfer := refund(nested["id"].(string), feePartID, "70", refundDestination, "1")
	assertRejected(missingTransfer, 422, "validation_error", refundDestination)
	staleTransfer := clone(missingTransfer)
	staleTransfer["id"] = id()
	staleTransfer["allocations"].([]any)[0].(map[string]any)["id"] = id()
	staleTransfer["expected_transfer_version"] = "999"
	assertRejected(staleTransfer, 409, "version_conflict", refundDestination)
	feeRefund := refund(nested["id"].(string), feePartID, "70", refundDestination, "1")
	feeRefund["expected_transfer_version"] = "1"
	feeRefundResult := post(feeRefund, id())
	expect(feeRefundResult, 201, "")
	if len(feeRefundResult.body["transactions"].([]any)) != 3 || remaining(nested["id"].(string), feePartID) != "53" || readTransaction(withFee["id"].(string))["version"] != "2" {
		t.Fatal("fee refund chain incomplete")
	}
	if findTransaction(feeRefundResult, nested["id"].(string))["version"] != "2" {
		t.Fatal("fee parent not versioned")
	}
	feeReplay := post(withFee, feeKey)
	expect(feeReplay, 201, "")
	if feeReplay.replay != "true" || !reflect.DeepEqual(feeResult.body, feeReplay.body) {
		t.Fatal("transfer receipt altered after fee refund")
	}
	// Pending is a real DB fixture (manual API creates posted only) and reserves limits.
	pendingSource, pendingTarget := account("EUR", "100000", false), account("EUR", "0", false)
	pendingExpense, pendingPart := id(), id()
	pendingBody := clone(expenseBody)
	pendingBody["id"] = pendingExpense
	pendingBody["account_id"] = pendingSource
	pendingBody["amount_minor"] = "1000"
	pendingBody["allocations"] = []any{map[string]any{"id": pendingPart, "category_id": nil, "amount_minor": "1000"}}
	expect(post(pendingBody, id()), 201, "")
	pendingID, pendingAllocation, pendingEntry := id(), id(), id()
	if _, e = db.Exec(ctx, `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,parent_transaction_id) VALUES($1,$2,'refund','pending','2026-01-03T00:00:00Z','UTC',$3)`, pendingID, user.Workspace.ID, pendingExpense); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(ctx, `INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) VALUES($1,$2,$3,$4,700)`, pendingEntry, user.Workspace.ID, pendingID, pendingTarget); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(ctx, `INSERT INTO allocations(id,workspace_id,transaction_id,amount_minor,original_allocation_id) VALUES($1,$2,$3,700,$4)`, pendingAllocation, user.Workspace.ID, pendingID, pendingPart); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(ctx, `UPDATE transactions SET version=version+1 WHERE workspace_id=$1 AND id=$2;`, user.Workspace.ID, pendingExpense); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(ctx, `UPDATE accounts SET version=version+1,balance_version=balance_version+1 WHERE workspace_id=$1 AND id=$2`, user.Workspace.ID, pendingTarget); e != nil {
		t.Fatal(e)
	}
	assertRejected(refund(pendingExpense, pendingPart, "400", pendingTarget, "2"), 422, "validation_error", pendingTarget)
	expect(post(refund(pendingExpense, pendingPart, "300", pendingTarget, "2"), id()), 201, "")
	pendingAccount := readAccount(pendingTarget)
	if pendingAccount["posted_balance_minor"] != "300" || pendingAccount["pending_delta_minor"] != "700" || pendingAccount["projected_balance_minor"] != "1000" || remaining(pendingExpense, pendingPart) != "0" {
		t.Fatal("pending reservation not accounted separately")
	}
	// An exception during refund allocation insertion rolls back account and parent versions.
	refundFault := refund(expenseID, homePart, "100", refundDestination, "5")
	refundFaultKey := id()
	beforeRefundFault := readTransaction(expenseID)
	beforeRefundAccount := readAccount(refundDestination)
	faultSQL := `CREATE FUNCTION fail_refund() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.transaction_id='` + refundFault["id"].(string) + `'::uuid THEN RAISE EXCEPTION 'synthetic refund fault' USING ERRCODE='XX000'; END IF; RETURN NEW; END $$; CREATE TRIGGER refund_fault BEFORE INSERT ON allocations FOR EACH ROW EXECUTE FUNCTION fail_refund();`
	if _, e = db.Exec(ctx, faultSQL); e != nil {
		t.Fatal(e)
	}
	expect(post(refundFault, refundFaultKey), 503, "service_unavailable")
	if !reflect.DeepEqual(beforeRefundFault, readTransaction(expenseID)) || !reflect.DeepEqual(beforeRefundAccount, readAccount(refundDestination)) {
		t.Fatal("refund fault changed balances/parent")
	}
	var refundFaultCount int
	if e = db.QueryRow(ctx, `SELECT count(*) FROM actions WHERE workspace_id=$1 AND action_id=$2`, user.Workspace.ID, refundFaultKey).Scan(&refundFaultCount); e != nil {
		t.Fatal(e)
	}
	if refundFaultCount != 0 {
		t.Fatal("refund fault retained receipt")
	}
	if _, e = db.Exec(ctx, `DROP TRIGGER refund_fault ON allocations; DROP FUNCTION fail_refund()`); e != nil {
		t.Fatal(e)
	}
	expect(post(refundFault, refundFaultKey), 201, "")
	refundHistory := send("GET", root+"/transactions?kind=refund&category_id="+food+"&limit=2", nil, "", user.Credential)
	expect(refundHistory, 200, "")
	if len(refundHistory.body["items"].([]any)) != 2 || refundHistory.body["next_cursor"] == nil {
		t.Fatal("refund/category cursor missing")
	}
	// Replacement reuses its own reservation, preserves part identity and atomically
	// refreshes the parent and both accounts when money moves to another account.
	replaceBody := func(create map[string]any, refundVersion, parentVersion string) map[string]any {
		v := clone(create)
		delete(v, "id")
		v["expected_version"], v["expected_parent_version"] = refundVersion, parentVersion
		return v
	}
	putRefund := func(refundID string, body map[string]any, key string) response {
		return send("PUT", root+"/transactions/"+refundID, body, key, user.Credential)
	}
	editID := firstRefund["id"].(string)
	parentVersion := readTransaction(expenseID)["version"].(string)
	edit = replaceBody(firstRefund, "1", parentVersion)
	edit["amount_minor"] = "1600"
	edit["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "1600"
	edit["account_id"] = refundDestination
	editKey := id()
	oldSource, oldTarget := readAccount(refundSource), readAccount(refundDestination)
	edited := putRefund(editID, edit, editKey)
	expect(edited, 200, "")
	assertBalance(refundSource, "94000")
	if remaining(expenseID, foodPart) != "0" || readTransaction(editID)["version"] != "2" || len(edited.body["transactions"].([]any)) != 2 {
		t.Fatal("refund replacement reservation/versions wrong")
	}
	for accountID, before := range map[string]map[string]any{refundSource: oldSource, refundDestination: oldTarget} {
		after := readAccount(accountID)
		if after["balance_version"] == before["balance_version"] || after["version"] == before["version"] {
			t.Fatal("refund account move omitted account version")
		}
	}
	editReplay := putRefund(editID, edit, editKey)
	expect(editReplay, 200, "")
	if editReplay.replay != "true" || !reflect.DeepEqual(editReplay.body, edited.body) {
		t.Fatal("refund PUT replay changed result")
	}
	parentVersion = readTransaction(expenseID)["version"].(string)
	rejectEdit := func(body map[string]any, status int, code string) {
		t.Helper()
		beforeParent, beforeRefund := readTransaction(expenseID), readTransaction(editID)
		beforeSource, beforeTarget := readAccount(refundSource), readAccount(refundDestination)
		expect(putRefund(editID, body, id()), status, code)
		if !reflect.DeepEqual(beforeParent, readTransaction(expenseID)) || !reflect.DeepEqual(beforeRefund, readTransaction(editID)) || !reflect.DeepEqual(beforeSource, readAccount(refundSource)) || !reflect.DeepEqual(beforeTarget, readAccount(refundDestination)) {
			t.Fatal("rejected refund edit mutated ledger")
		}
	}
	rejectEdit(edit, 409, "version_conflict")
	currentEdit := clone(edit)
	currentEdit["expected_version"], currentEdit["expected_parent_version"] = "2", parentVersion
	over := clone(currentEdit)
	over["amount_minor"] = "1601"
	over["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "1601"
	rejectEdit(over, 422, "validation_error")
	alternativeParent := clone(expenseBody)
	alternativeParent["id"] = id()
	alternativeParent["allocations"] = []any{map[string]any{"id": id(), "category_id": nil, "amount_minor": "6000"}}
	expect(post(alternativeParent, id()), 201, "")
	alternativeID := alternativeParent["id"].(string)
	alternativeBefore := readTransaction(alternativeID)
	expect(putRefund(expenseID, currentEdit, id()), 422, "validation_error")
	wrongParent := clone(currentEdit)
	wrongParent["parent_transaction_id"] = alternativeID
	rejectEdit(wrongParent, 422, "validation_error")
	if !reflect.DeepEqual(alternativeBefore, readTransaction(alternativeID)) {
		t.Fatal("parent reassignment altered alternative expense")
	}
	wrongOriginal := clone(currentEdit)
	wrongOriginal["allocations"].([]any)[0].(map[string]any)["original_allocation_id"] = homePart
	rejectEdit(wrongOriginal, 422, "validation_error")
	wrongPartID := clone(currentEdit)
	wrongPartID["allocations"].([]any)[0].(map[string]any)["id"] = id()
	rejectEdit(wrongPartID, 422, "validation_error")
	wrongCurrency := clone(currentEdit)
	wrongCurrency["account_id"] = usd
	rejectEdit(wrongCurrency, 422, "validation_error")
	wrongOwner := clone(currentEdit)
	wrongOwner["account_id"] = foreignAccount
	rejectEdit(wrongOwner, 404, "not_found")
	overflowEdit := clone(currentEdit)
	overflowEdit["account_id"] = maxTarget
	rejectEdit(overflowEdit, 422, "amount_out_of_range")
	// Metadata alone changes only the refund version, including historical tags.
	metadata := clone(currentEdit)
	metadata["note"] = "Refund metadata only"
	beforeParent, beforeTarget := readTransaction(expenseID), readAccount(refundDestination)
	expect(putRefund(editID, metadata, id()), 200, "")
	if !reflect.DeepEqual(beforeParent, readTransaction(expenseID)) || !reflect.DeepEqual(beforeTarget, readAccount(refundDestination)) {
		t.Fatal("refund metadata altered parent or money")
	}
	// A smaller amount releases exactly the difference for future refunds.
	smaller := clone(currentEdit)
	smaller["expected_version"] = "3"
	smaller["amount_minor"] = "1500"
	smaller["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "1500"
	expect(putRefund(editID, smaller, id()), 200, "")
	if remaining(expenseID, foodPart) != "100" {
		t.Fatal("refund reduction failed to release reservation")
	}
	// Refund edit on a fee checks and republishes both ancestor versions.
	feeEdit := replaceBody(feeRefund, "1", "2")
	feeEdit["expected_transfer_version"] = "2"
	feeEdit["amount_minor"] = "71"
	feeEdit["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "71"
	feeEdited := putRefund(feeRefund["id"].(string), feeEdit, id())
	expect(feeEdited, 200, "")
	if len(feeEdited.body["transactions"].([]any)) != 3 || readTransaction(withFee["id"].(string))["version"] != "3" || remaining(nested["id"].(string), feePartID) != "52" {
		t.Fatal("fee refund edit ancestor versions/limit wrong")
	}
	// Fault after changing the entry rolls back the refund, parent and accounts.
	faultEdit := clone(smaller)
	faultEdit["expected_version"] = "4"
	faultEdit["expected_parent_version"] = readTransaction(expenseID)["version"]
	faultEdit["amount_minor"] = "1499"
	faultEdit["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "1499"
	faultKey = id()
	beforeParent, beforeTarget = readTransaction(expenseID), readAccount(refundDestination)
	beforeRefund := readTransaction(editID)
	if _, e = db.Exec(ctx, `CREATE FUNCTION fail_refund_edit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic refund edit fault' USING ERRCODE='XX000'; END $$; CREATE TRIGGER refund_edit_fault BEFORE UPDATE ON allocations FOR EACH ROW EXECUTE FUNCTION fail_refund_edit()`); e != nil {
		t.Fatal(e)
	}
	expect(putRefund(editID, faultEdit, faultKey), 503, "service_unavailable")
	if !reflect.DeepEqual(beforeParent, readTransaction(expenseID)) || !reflect.DeepEqual(beforeTarget, readAccount(refundDestination)) || !reflect.DeepEqual(beforeRefund, readTransaction(editID)) {
		t.Fatal("refund PUT fault left partial writes")
	}
	if _, e = db.Exec(ctx, `DROP TRIGGER refund_edit_fault ON allocations; DROP FUNCTION fail_refund_edit()`); e != nil {
		t.Fatal(e)
	}
	expect(putRefund(editID, faultEdit, faultKey), 200, "")
	deleteBody := func(transactionID string, relatedIDs ...string) map[string]any {
		related := []any{}
		for _, relatedID := range relatedIDs {
			related = append(related, map[string]any{"id": relatedID, "version": readTransaction(relatedID)["version"]})
		}
		return map[string]any{"expected_version": readTransaction(transactionID)["version"], "related_versions": related}
	}
	remove := func(transactionID string, body map[string]any, key string) response {
		return send("DELETE", root+"/transactions/"+transactionID, body, key, user.Credential)
	}
	// Active refunds prevent deletion of original expense and transfer fee.
	expect(remove(expenseID, deleteBody(expenseID), id()), 409, "dependent_transactions")
	expect(remove(withFee["id"].(string), deleteBody(withFee["id"].(string), nested["id"].(string)), id()), 409, "dependent_transactions")
	deleteFeeRefund := deleteBody(feeRefund["id"].(string), nested["id"].(string), withFee["id"].(string))
	missingRelated := clone(deleteFeeRefund)
	missingRelated["related_versions"] = []any{}
	expect(remove(feeRefund["id"].(string), missingRelated, id()), 422, "validation_error")
	staleRelated := clone(deleteFeeRefund)
	staleRelated["related_versions"].([]any)[1].(map[string]any)["version"] = "1"
	expect(remove(feeRefund["id"].(string), staleRelated, id()), 409, "version_conflict")
	deleteRefundKey := id()
	removedFeeRefund := remove(feeRefund["id"].(string), deleteFeeRefund, deleteRefundKey)
	expect(removedFeeRefund, 200, "")
	if len(removedFeeRefund.body["deleted"].([]any)) != 1 || len(removedFeeRefund.body["transactions"].([]any)) != 2 || remaining(nested["id"].(string), feePartID) != "123" {
		t.Fatal("fee refund deletion did not release limit and update ancestors")
	}
	expect(send("GET", root+"/transactions/"+feeRefund["id"].(string), nil, "", user.Credential), 404, "not_found")
	expect(remove(feeRefund["id"].(string), deleteFeeRefund, id()), 409, "validation_error")
	deleteRefundReplay := remove(feeRefund["id"].(string), deleteFeeRefund, deleteRefundKey)
	expect(deleteRefundReplay, 200, "")
	if deleteRefundReplay.replay != "true" || !reflect.DeepEqual(deleteRefundReplay.body, removedFeeRefund.body) {
		t.Fatal("deleted refund receipt failed replay")
	}
	// Deleting the fee separately republishes its transfer and keeps both sides intact.
	transferBefore := readTransaction(withFee["id"].(string))
	feeBefore := readAccount(source)
	removedFee := remove(nested["id"].(string), deleteBody(nested["id"].(string), withFee["id"].(string)), id())
	expect(removedFee, 200, "")
	if len(removedFee.body["deleted"].([]any)) != 1 || readTransaction(withFee["id"].(string))["fee_transaction_id"] != nil || readTransaction(withFee["id"].(string))["version"] == transferBefore["version"] || readAccount(source)["balance_version"] == feeBefore["balance_version"] {
		t.Fatal("standalone fee deletion did not update transfer/account")
	}
	// Dedicated full refund: removal restores original limit, then expense can go.
	deleteExpense, deletePart := id(), id()
	deletableExpense := clone(expenseBody)
	deletableExpense["id"] = deleteExpense
	deletableExpense["amount_minor"] = "100"
	deletableExpense["allocations"] = []any{map[string]any{"id": deletePart, "category_id": nil, "amount_minor": "100"}}
	expect(post(deletableExpense, id()), 201, "")
	deletableRefund := refund(deleteExpense, deletePart, "100", refundDestination, "1")
	expect(post(deletableRefund, id()), 201, "")
	deleteRefundBody := deleteBody(deletableRefund["id"].(string), deleteExpense)
	beforeDeletedRefund := readTransaction(deletableRefund["id"].(string))
	beforeDeletedParent := readTransaction(deleteExpense)
	beforeDeleteTarget := readAccount(refundDestination)
	// A DB failure after soft-deletion must leave no tombstone, version or receipt.
	deleteFaultSQL := `CREATE FUNCTION fail_refund_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='` + deleteExpense + `'::uuid THEN RAISE EXCEPTION 'synthetic delete fault' USING ERRCODE='XX000'; END IF; RETURN NEW; END $$; CREATE TRIGGER refund_delete_fault BEFORE UPDATE ON transactions FOR EACH ROW EXECUTE FUNCTION fail_refund_delete();`
	if _, e = db.Exec(ctx, deleteFaultSQL); e != nil {
		t.Fatal(e)
	}
	deleteFaultKey := id()
	expect(remove(deletableRefund["id"].(string), deleteRefundBody, deleteFaultKey), 503, "service_unavailable")
	if !reflect.DeepEqual(beforeDeletedRefund, readTransaction(deletableRefund["id"].(string))) || !reflect.DeepEqual(beforeDeletedParent, readTransaction(deleteExpense)) || !reflect.DeepEqual(beforeDeleteTarget, readAccount(refundDestination)) {
		t.Fatal("failed delete retained partial financial changes")
	}
	if _, e = db.Exec(ctx, `DROP TRIGGER refund_delete_fault ON transactions; DROP FUNCTION fail_refund_delete()`); e != nil {
		t.Fatal(e)
	}
	expect(remove(deletableRefund["id"].(string), deleteRefundBody, deleteFaultKey), 200, "")
	if remaining(deleteExpense, deletePart) != "100" {
		t.Fatal("delete failed to release full reservation")
	}
	expect(remove(deleteExpense, deleteBody(deleteExpense), id()), 200, "")
	// Transfer + fee delete is atomic and bumps each shared account exactly once.
	deleteTransfer := transfer(source, target, "100", "100")
	deleteFeeID, deleteFeePart := id(), id()
	deleteTransfer["fee"] = map[string]any{"id": deleteFeeID, "account_id": source, "amount_minor": "10", "allocations": []any{map[string]any{"id": deleteFeePart, "category_id": nil, "amount_minor": "10"}}, "tag_ids": []string{}, "note": "Delete linked fee"}
	expect(post(deleteTransfer, id()), 201, "")
	deleteTransferID := deleteTransfer["id"].(string)
	beforeCascadeSource, beforeCascadeTarget := readAccount(source), readAccount(target)
	cascadeBody := deleteBody(deleteTransferID, deleteFeeID)
	expect(remove(deleteTransferID, map[string]any{"expected_version": "1", "related_versions": []any{}}, id()), 422, "validation_error")
	cascade := remove(deleteTransferID, cascadeBody, id())
	expect(cascade, 200, "")
	if len(cascade.body["deleted"].([]any)) != 2 || len(cascade.body["accounts"].([]any)) != 2 {
		t.Fatal("transfer deletion did not include both tombstones/accounts")
	}
	for accountID, before := range map[string]map[string]any{source: beforeCascadeSource, target: beforeCascadeTarget} {
		v, _ := strconv.ParseInt(before["balance_version"].(string), 10, 64)
		if readAccount(accountID)["balance_version"] != strconv.FormatInt(v+1, 10) {
			t.Fatal("cascade bumped shared account twice")
		}
	}
	expect(send("GET", root+"/transactions/"+deleteFeeID, nil, "", user.Credential), 404, "not_found")
	// Opening is immutable and foreign operation IDs remain scoped to workspace.
	var openingID string
	if e = db.QueryRow(ctx, `SELECT t.id::text FROM transactions t JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id) WHERE t.workspace_id=$1 AND e.account_id=$2 AND t.kind='opening'`, user.Workspace.ID, source).Scan(&openingID); e != nil {
		t.Fatal(e)
	}
	expect(remove(openingID, deleteBody(openingID), id()), 422, "validation_error")
	expect(send("DELETE", foreignRoot+"/transactions/"+expenseID, map[string]any{"expected_version": "1", "related_versions": []any{}}, id(), other.Credential), 404, "not_found")
	// Redistribution between original parts changes parent limits, not account movements.
	reallocate := clone(faultEdit)
	reallocate["expected_version"] = readTransaction(editID)["version"]
	reallocate["expected_parent_version"] = readTransaction(expenseID)["version"]
	reallocate["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "1498"
	reallocate["allocations"] = append(reallocate["allocations"].([]any), map[string]any{"id": id(), "original_allocation_id": homePart, "amount_minor": "1"})
	beforeRedistribution := readAccount(refundDestination)
	beforeRedistributionParent := readTransaction(expenseID)
	expect(putRefund(editID, reallocate, id()), 200, "")
	if !reflect.DeepEqual(beforeRedistribution, readAccount(refundDestination)) || readTransaction(expenseID)["version"] == beforeRedistributionParent["version"] || remaining(expenseID, foodPart) != "102" {
		t.Fatal("part redistribution failed to separate parent limits from account versions")
	}
	// Editing/deleting pending reservations leaves posted money independent.
	pendingEdit := replaceBody(refund(pendingExpense, pendingPart, "600", pendingTarget, readTransaction(pendingExpense)["version"].(string)), "1", readTransaction(pendingExpense)["version"].(string))
	pendingEdit["allocations"].([]any)[0].(map[string]any)["id"] = pendingAllocation
	expect(putRefund(pendingID, pendingEdit, id()), 200, "")
	if readTransaction(pendingID)["status"] != "pending" || readAccount(pendingTarget)["pending_delta_minor"] != "600" || readAccount(pendingTarget)["posted_balance_minor"] != "300" || remaining(pendingExpense, pendingPart) != "100" {
		t.Fatal("pending refund edit mixed posted and reserved money")
	}
	expect(remove(pendingID, deleteBody(pendingID, pendingExpense), id()), 200, "")
	if readAccount(pendingTarget)["pending_delta_minor"] != "0" || readAccount(pendingTarget)["posted_balance_minor"] != "300" || remaining(pendingExpense, pendingPart) != "700" {
		t.Fatal("pending refund deletion failed to release reservation")
	}
	// A competing edit is rejected by refund/parent versions; one action wins.
	raceExpense, racePart := id(), id()
	raceExpenseBody := clone(deletableExpense)
	raceExpenseBody["id"] = raceExpense
	raceExpenseBody["allocations"].([]any)[0].(map[string]any)["id"] = racePart
	expect(post(raceExpenseBody, id()), 201, "")
	raceRefund := refund(raceExpense, racePart, "100", refundDestination, "1")
	expect(post(raceRefund, id()), 201, "")
	editRace1 := replaceBody(raceRefund, "1", "2")
	editRace1["amount_minor"] = "99"
	editRace1["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "99"
	editRace2 := clone(editRace1)
	editRace2["amount_minor"] = "98"
	editRace2["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "98"
	raceResults = make(chan response, 2)
	var editWait sync.WaitGroup
	for _, candidate := range []map[string]any{editRace1, editRace2} {
		editWait.Add(1)
		go func(v map[string]any) {
			defer editWait.Done()
			raceResults <- putRefund(raceRefund["id"].(string), v, id())
		}(candidate)
	}
	editWait.Wait()
	close(raceResults)
	wins, conflicts := 0, 0
	for result := range raceResults {
		if result.status == 200 {
			wins++
		} else {
			expect(result, 409, "version_conflict")
			conflicts++
		}
	}
	if wins != 1 || conflicts != 1 || readTransaction(raceExpense)["version"] != "3" || readTransaction(raceRefund["id"].(string))["version"] != "2" {
		t.Fatal("concurrent refund edits did not serialize")
	}
	// An archived destination rejects both edit and deletion without releasing limits.
	setArchive := func(aid string, archived bool) {
		current := readAccount(aid)
		expect(send("PUT", root+"/accounts/"+aid, map[string]any{"expected_version": current["version"], "name": current["name"], "type": current["type"], "archived": archived}, id(), user.Credential), 200, "")
	}
	raceRefundID := raceRefund["id"].(string)
	raceDelete := deleteBody(raceRefundID, raceExpense)
	setArchive(refundDestination, true)
	beforeArchivedParent := readTransaction(raceExpense)
	expect(remove(raceRefundID, raceDelete, id()), 422, "account_archived")
	raceCurrent := readTransaction(raceRefundID)
	archiveEdit := clone(editRace1)
	archiveEdit["expected_version"] = raceCurrent["version"]
	archiveEdit["expected_parent_version"] = beforeArchivedParent["version"]
	expect(putRefund(raceRefundID, archiveEdit, id()), 422, "account_archived")
	if !reflect.DeepEqual(beforeArchivedParent, readTransaction(raceExpense)) {
		t.Fatal("archive rejection released refund limits")
	}
	setArchive(refundDestination, false)
	// Deleting a positive offset can overflow a negative balance; the whole command rolls back.
	deleteLimitAccount := account("EUR", "-9000000000000000", false)
	offset := clone(deletableExpense)
	offset["id"] = id()
	offset["kind"] = "income"
	offset["account_id"] = deleteLimitAccount
	offset["amount_minor"] = "1"
	offset["allocations"] = []any{map[string]any{"id": id(), "category_id": nil, "amount_minor": "1"}}
	expect(post(offset, id()), 201, "")
	cancelOffset := clone(offset)
	cancelOffset["id"] = id()
	cancelOffset["kind"] = "expense"
	cancelOffset["allocations"].([]any)[0].(map[string]any)["id"] = id()
	expect(post(cancelOffset, id()), 201, "")
	beforeLimitAccount := readAccount(deleteLimitAccount)
	beforeLimitTransaction := readTransaction(offset["id"].(string))
	expect(remove(offset["id"].(string), deleteBody(offset["id"].(string)), id()), 422, "amount_out_of_range")
	if !reflect.DeepEqual(beforeLimitAccount, readAccount(deleteLimitAccount)) || !reflect.DeepEqual(beforeLimitTransaction, readTransaction(offset["id"].(string))) {
		t.Fatal("overflowing delete left tombstone or account version")
	}
	{
		// Dedicated transfer/fee edits with independently calculated balances.
		a, b, c, d := account("EUR", "100000", false), account("EUR", "1000", false), account("EUR", "5000", false), account("EUR", "2000", false)
		usd := account("USD", "1000", false)
		category, rootTag, feeTag := id(), id(), id()
		expect(send("POST", root+"/categories", map[string]any{"id": category, "name": "Transfer edit category", "parent_id": nil}, id(), user.Credential), 201, "")
		for _, tag := range []string{rootTag, feeTag} {
			expect(send("POST", root+"/tags", map[string]any{"id": tag, "name": "Transfer edit " + tag}, id(), user.Credential), 201, "")
		}
		created := transfer(a, b, "10000", "10000")
		tid := created["id"].(string)
		created["tag_ids"] = []string{rootTag}
		commission := fee(a, "100")
		feeID := commission["id"].(string)
		part1, part2 := id(), id()
		commission["tag_ids"] = []string{feeTag}
		commission["allocations"] = []any{map[string]any{"id": part1, "category_id": category, "amount_minor": "60"}, map[string]any{"id": part2, "category_id": nil, "amount_minor": "40"}}
		created["fee"] = commission
		createKey := id()
		createdResult := post(created, createKey)
		expect(createdResult, 201, "")
		assertBalance(a, "89900")
		assertBalance(b, "11000")
		entryIDs := func(transaction map[string]any) map[string]string {
			ids := map[string]string{}
			for _, raw := range transaction["entries"].([]any) {
				entry := raw.(map[string]any)
				direction := "positive"
				if strings.HasPrefix(entry["amount_minor"].(string), "-") {
					direction = "negative"
				}
				ids[direction] = entry["id"].(string)
			}
			return ids
		}
		originalEntries := entryIDs(readTransaction(tid))
		feeEntryID := entryIDs(readTransaction(feeID))["negative"]
		fresh := func(body map[string]any) map[string]any {
			desired := clone(body)
			delete(desired, "id")
			current := readTransaction(tid)
			desired["expected_version"] = current["version"]
			desired["expected_fee_version"] = nil
			if current["fee_transaction_id"] != nil {
				desired["expected_fee_version"] = readTransaction(current["fee_transaction_id"].(string))["version"]
			}
			return desired
		}
		put := func(body map[string]any, key string) response {
			return send("PUT", root+"/transactions/"+tid, body, key, user.Credential)
		}
		beforeAccounts := map[string]map[string]any{}
		for _, aid := range []string{a, b, c, d} {
			beforeAccounts[aid] = readAccount(aid)
		}
		moved := fresh(created)
		moved["source_account_id"], moved["target_account_id"] = c, d
		movedFee := moved["fee"].(map[string]any)
		movedFee["account_id"], movedFee["amount_minor"] = c, "200"
		movedFee["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "120"
		movedFee["allocations"].([]any)[1].(map[string]any)["amount_minor"] = "80"
		moveKey := id()
		moveResult := put(moved, moveKey)
		expect(moveResult, 200, "")
		assertBalance(a, "100000")
		assertBalance(b, "1000")
		assertBalance(c, "-5200")
		assertBalance(d, "12000")
		if len(moveResult.body["accounts"].([]any)) != 4 || len(moveResult.body["transactions"].([]any)) != 2 || readTransaction(tid)["version"] != "2" || readTransaction(feeID)["version"] != "2" {
			t.Fatal("transfer move did not publish both transactions and all old/new accounts")
		}
		for aid, before := range beforeAccounts {
			v, _ := strconv.ParseInt(before["balance_version"].(string), 10, 64)
			if readAccount(aid)["balance_version"] != strconv.FormatInt(v+1, 10) {
				t.Fatal("transfer move did not bump each account once")
			}
		}
		if !reflect.DeepEqual(originalEntries, entryIDs(readTransaction(tid))) || feeEntryID != entryIDs(readTransaction(feeID))["negative"] {
			t.Fatal("entry identities changed on transfer/fee edit")
		}
		moveReplay := put(moved, moveKey)
		expect(moveReplay, 200, "")
		if moveReplay.replay != "true" || !reflect.DeepEqual(moveReplay.body, moveResult.body) {
			t.Fatal("transfer PUT receipt changed on replay")
		}
		createReplay := post(created, createKey)
		expect(createReplay, 201, "")
		if !reflect.DeepEqual(createReplay.body, createdResult.body) {
			t.Fatal("transfer creation receipt changed after PUT")
		}
		// Archived historical category and tags can survive full replacement.
		expect(send("PUT", root+"/categories/"+category, map[string]any{"expected_version": "1", "name": "Transfer edit category", "parent_id": nil, "archived": true}, id(), user.Credential), 200, "")
		for _, tag := range []string{rootTag, feeTag} {
			expect(send("PUT", root+"/tags/"+tag, map[string]any{"expected_version": "1", "name": "Transfer edit " + tag, "archived": true}, id(), user.Credential), 200, "")
		}
		metadata := fresh(moved)
		metadata["note"] = "Parent metadata"
		metadata["fee"].(map[string]any)["note"] = "Fee metadata"
		beforeC, beforeD := readAccount(c), readAccount(d)
		expect(put(metadata, id()), 200, "")
		if !reflect.DeepEqual(beforeC, readAccount(c)) || !reflect.DeepEqual(beforeD, readAccount(d)) {
			t.Fatal("transfer metadata bumped balances")
		}
		// Direct fee edit requires the parent version and republishes its parent.
		feeReplace := map[string]any{"kind": "expense", "account_id": c, "amount_minor": "200", "occurred_at": metadata["occurred_at"], "occurred_timezone": "UTC", "note": "Direct fee metadata", "payee": "", "tag_ids": []string{feeTag}, "allocations": clone(metadata)["fee"].(map[string]any)["allocations"], "expected_version": "3", "expected_parent_version": "3"}
		directFee := send("PUT", root+"/transactions/"+feeID, feeReplace, id(), user.Credential)
		expect(directFee, 200, "")
		if readTransaction(tid)["version"] != "4" || readTransaction(feeID)["version"] != "4" || len(directFee.body["transactions"].([]any)) != 2 || !reflect.DeepEqual(beforeC, readAccount(c)) {
			t.Fatal("direct fee edit did not guard/bump parent independently from balances")
		}
		expect(send("PUT", root+"/transactions/"+feeID, feeReplace, id(), user.Credential), 409, "version_conflict")
		feeNoParent := clone(feeReplace)
		feeNoParent["expected_version"] = "4"
		feeNoParent["expected_parent_version"] = nil
		expect(send("PUT", root+"/transactions/"+feeID, feeNoParent, id(), user.Credential), 422, "validation_error")
		feeWrongDate := clone(feeReplace)
		feeWrongDate["expected_version"], feeWrongDate["expected_parent_version"] = "4", "4"
		feeWrongDate["occurred_at"] = "2026-01-03T00:00:00Z"
		expect(send("PUT", root+"/transactions/"+feeID, feeWrongDate, id(), user.Credential), 422, "validation_error")
		assertRejectedEdit := func(body map[string]any, status int, code string) {
			t.Helper()
			beforeParent, beforeFee := readTransaction(tid), readTransaction(feeID)
			before := map[string]map[string]any{}
			for _, aid := range []string{a, b, c, d, usd} {
				before[aid] = readAccount(aid)
			}
			expect(put(body, id()), status, code)
			if !reflect.DeepEqual(beforeParent, readTransaction(tid)) || !reflect.DeepEqual(beforeFee, readTransaction(feeID)) {
				t.Fatal("rejected parent PUT changed transaction or fee")
			}
			for aid, value := range before {
				if !reflect.DeepEqual(value, readAccount(aid)) {
					t.Fatal("rejected parent PUT changed account/version")
				}
			}
		}
		current := fresh(metadata)
		stale := clone(current)
		stale["expected_version"] = "1"
		assertRejectedEdit(stale, 409, "version_conflict")
		staleFee := clone(current)
		staleFee["expected_fee_version"] = "1"
		assertRejectedEdit(staleFee, 409, "version_conflict")
		missingFeeVersion := clone(current)
		missingFeeVersion["expected_fee_version"] = nil
		assertRejectedEdit(missingFeeVersion, 422, "validation_error")
		changedFeeID := clone(current)
		changedFeeID["fee"].(map[string]any)["id"] = id()
		assertRejectedEdit(changedFeeID, 422, "validation_error")
		for _, field := range []string{"source_account_id", "target_account_id"} {
			wrong := clone(current)
			wrong[field] = usd
			assertRejectedEdit(wrong, 422, "validation_error")
		}
		wrongFeeCurrency := clone(current)
		wrongFeeCurrency["fee"].(map[string]any)["account_id"] = usd
		assertRejectedEdit(wrongFeeCurrency, 422, "validation_error")
		self := clone(current)
		self["target_account_id"] = c
		assertRejectedEdit(self, 422, "validation_error")
		unequal := clone(current)
		unequal["target_amount_minor"] = "9999"
		assertRejectedEdit(unequal, 422, "validation_error")
		wrongRate := clone(current)
		wrongRate["rate"] = map[string]any{"numerator": "99999999", "denominator": "100000000"}
		assertRejectedEdit(wrongRate, 422, "validation_error")
		foreign := clone(current)
		foreign["source_account_id"] = foreignAccount
		assertRejectedEdit(foreign, 404, "not_found")
		future := clone(current)
		future["occurred_at"] = "2100-01-01T00:00:00Z"
		assertRejectedEdit(future, 422, "validation_error")
		badSum := clone(current)
		badSum["fee"].(map[string]any)["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "119"
		assertRejectedEdit(badSum, 422, "validation_error")
		newArchivedPart := clone(current)
		newArchivedPart["fee"].(map[string]any)["allocations"].([]any)[0].(map[string]any)["id"] = id()
		assertRejectedEdit(newArchivedPart, 422, "validation_error")
		setArchive(c, true)
		assertRejectedEdit(current, 422, "account_archived")
		setArchive(c, false)
		expect(send("PUT", foreignRoot+"/transactions/"+tid, current, id(), other.Credential), 404, "not_found")
		expect(send("PUT", foreignRoot+"/transactions/"+feeID, feeReplace, id(), other.Credential), 404, "not_found")
		// Same-currency source/target swap preserves debit/credit UUIDs.
		swapped := fresh(current)
		swapped["source_account_id"], swapped["target_account_id"] = d, c
		swapped["source_amount_minor"], swapped["target_amount_minor"] = "9000", "9000"
		expect(put(swapped, id()), 200, "")
		assertBalance(c, "13800")
		assertBalance(d, "-7000")
		if !reflect.DeepEqual(originalEntries, entryIDs(readTransaction(tid))) {
			t.Fatal("swap lost movement IDs")
		}
		// Returned fee blocks financial changes through parent PUT (DR-F08).
		returned := refund(feeID, part1, "50", a, readTransaction(feeID)["version"].(string))
		returned["expected_transfer_version"] = readTransaction(tid)["version"]
		expect(post(returned, id()), 201, "")
		guarded := fresh(swapped)
		changedDate := clone(guarded)
		changedDate["occurred_at"] = "2026-01-03T00:00:00Z"
		assertRejectedEdit(changedDate, 409, "dependent_transactions")
		changedAmount := clone(guarded)
		changedAmount["fee"].(map[string]any)["amount_minor"] = "201"
		changedAmount["fee"].(map[string]any)["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "121"
		assertRejectedEdit(changedAmount, 409, "dependent_transactions")
		changedFeeAccount := clone(guarded)
		changedFeeAccount["fee"].(map[string]any)["account_id"] = a
		assertRejectedEdit(changedFeeAccount, 409, "dependent_transactions")
		removedReturnedFee := clone(guarded)
		removedReturnedFee["fee"] = nil
		assertRejectedEdit(removedReturnedFee, 409, "dependent_transactions")
		returnedDirect := clone(feeReplace)
		returnedDirect["expected_version"] = readTransaction(feeID)["version"]
		returnedDirect["expected_parent_version"] = readTransaction(tid)["version"]
		returnedDirect["account_id"] = a
		expect(send("PUT", root+"/transactions/"+feeID, returnedDirect, id(), user.Credential), 409, "dependent_transactions")
		// Array order is irrelevant to returned allocations; metadata is allowed.
		allowed := clone(guarded)
		allowed["note"] = "Returned fee parent metadata"
		parts := allowed["fee"].(map[string]any)["allocations"].([]any)
		parts[0], parts[1] = parts[1], parts[0]
		allowed["fee"].(map[string]any)["note"] = "Returned fee metadata"
		beforeC, beforeD = readAccount(c), readAccount(d)
		expect(put(allowed, id()), 200, "")
		if !reflect.DeepEqual(beforeC, readAccount(c)) || !reflect.DeepEqual(beforeD, readAccount(d)) || remaining(feeID, part1) != "70" {
			t.Fatal("returned fee metadata changed reservations or account")
		}
		// Removing a fee emits a tombstone, then a new fee requires a new UUID.
		expect(remove(returned["id"].(string), deleteBody(returned["id"].(string), feeID, tid), id()), 200, "")
		withoutFee := fresh(allowed)
		withoutFee["fee"] = nil
		removedFeeResult := put(withoutFee, id())
		expect(removedFeeResult, 200, "")
		if len(removedFeeResult.body["deleted"].([]any)) != 1 || readTransaction(tid)["fee_transaction_id"] != nil {
			t.Fatal("PUT fee=null did not publish fee tombstone")
		}
		beforeRemovedState := readTransaction(tid)
		oldFeeReuse := fresh(withoutFee)
		oldFeeReuse["fee"] = fee(c, "20")
		oldFeeReuse["fee"].(map[string]any)["id"] = feeID
		expect(put(oldFeeReuse, id()), 409, "validation_error")
		if !reflect.DeepEqual(beforeRemovedState, readTransaction(tid)) {
			t.Fatal("old deleted fee UUID reuse mutated parent")
		}
		withNewFee := fresh(withoutFee)
		withNewFee["fee"] = fee(c, "20")
		expect(put(withNewFee, id()), 200, "")
		newFeeID := withNewFee["fee"].(map[string]any)["id"].(string)
		if readTransaction(tid)["fee_transaction_id"] != newFeeID || readTransaction(newFeeID)["version"] != "1" {
			t.Fatal("new fee missing after explicit removal")
		}
		// Parent PUT and independent fee PUT with the same versions cannot both win.
		parentRace := fresh(withNewFee)
		parentRace["note"] = "Concurrent parent"
		feeRace := map[string]any{"kind": "expense", "account_id": c, "amount_minor": "20", "occurred_at": parentRace["occurred_at"], "occurred_timezone": "UTC", "note": "Concurrent fee", "payee": "", "tag_ids": []string{}, "allocations": clone(withNewFee)["fee"].(map[string]any)["allocations"], "expected_version": "1", "expected_parent_version": parentRace["expected_version"]}
		racing := make(chan response, 2)
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); racing <- put(parentRace, id()) }()
		go func() {
			defer wg.Done()
			racing <- send("PUT", root+"/transactions/"+newFeeID, feeRace, id(), user.Credential)
		}()
		wg.Wait()
		close(racing)
		winners := 0
		for result := range racing {
			if result.status == 200 {
				winners++
			} else {
				expect(result, 409, "version_conflict")
			}
		}
		parentV, _ := strconv.ParseInt(parentRace["expected_version"].(string), 10, 64)
		if winners != 1 || readTransaction(tid)["version"] != strconv.FormatInt(parentV+1, 10) || readTransaction(newFeeID)["version"] != "2" {
			t.Fatal("parent/fee edit race failed atomic versions")
		}
		// DB fault after root entries/fee mutation must restore every aggregate.
		fault := fresh(withNewFee)
		fault["fee"].(map[string]any)["amount_minor"] = "21"
		fault["fee"].(map[string]any)["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "21"
		beforeFaultRoot, beforeFaultFee := readTransaction(tid), readTransaction(newFeeID)
		beforeFaultAccount := readAccount(c)
		faultSQL := `CREATE FUNCTION fail_transfer_edit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.transaction_id='` + newFeeID + `'::uuid THEN RAISE EXCEPTION 'synthetic transfer edit fault' USING ERRCODE='XX000'; END IF; RETURN NEW; END $$; CREATE TRIGGER transfer_edit_fault BEFORE UPDATE ON allocations FOR EACH ROW EXECUTE FUNCTION fail_transfer_edit();`
		if _, e = db.Exec(ctx, faultSQL); e != nil {
			t.Fatal(e)
		}
		faultKey := id()
		expect(put(fault, faultKey), 503, "service_unavailable")
		if !reflect.DeepEqual(beforeFaultRoot, readTransaction(tid)) || !reflect.DeepEqual(beforeFaultFee, readTransaction(newFeeID)) || !reflect.DeepEqual(beforeFaultAccount, readAccount(c)) {
			t.Fatal("transfer PUT fault left partial writes")
		}
		var faultReceipts int
		if e = db.QueryRow(ctx, `SELECT count(*) FROM actions WHERE workspace_id=$1 AND action_id=$2`, user.Workspace.ID, faultKey).Scan(&faultReceipts); e != nil {
			t.Fatal(e)
		}
		if faultReceipts != 0 {
			t.Fatal("transient transfer fault retained receipt")
		}
		if _, e = db.Exec(ctx, `DROP TRIGGER transfer_edit_fault ON allocations; DROP FUNCTION fail_transfer_edit()`); e != nil {
			t.Fatal(e)
		}
		expect(put(fault, faultKey), 200, "")
		// FX edits retain their currency pair and recompute an exact fraction.
		fx := transfer(a, usd, "300", "100")
		fxID := fx["id"].(string)
		expect(post(fx, id()), 201, "")
		fxEdit := clone(fx)
		delete(fxEdit, "id")
		fxEdit["expected_version"], fxEdit["expected_fee_version"] = "1", nil
		fxEdit["source_amount_minor"], fxEdit["target_amount_minor"] = "600", "200"
		fxEdit["rate"] = map[string]any{"numerator": "2", "denominator": "6"}
		expect(send("PUT", root+"/transactions/"+fxID, fxEdit, id(), user.Credential), 200, "")
		if !reflect.DeepEqual(readTransaction(fxID)["rate"], map[string]any{"numerator": "1", "denominator": "3"}) {
			t.Fatal("FX PUT did not reduce exact rate")
		}
		fxEdit["expected_version"] = "2"
		fxEdit["rate"] = map[string]any{"numerator": "33333333", "denominator": "100000000"}
		expect(send("PUT", root+"/transactions/"+fxID, fxEdit, id(), user.Credential), 422, "validation_error")
		fxEdit["rate"] = nil
		fxEdit["target_account_id"] = b
		expect(send("PUT", root+"/transactions/"+fxID, fxEdit, id(), user.Credential), 422, "validation_error")
		// Final net balance is authoritative: transfer and its fee can offset at max.
		limitSource, limitTarget := account("EUR", "100", false), account("EUR", "9000000000000000", false)
		boundary := transfer(limitSource, limitTarget, "1", "1")
		boundary["fee"] = fee(limitTarget, "1")
		boundaryID := boundary["id"].(string)
		expect(post(boundary, id()), 201, "")
		boundaryEdit := clone(boundary)
		delete(boundaryEdit, "id")
		boundaryEdit["expected_version"], boundaryEdit["expected_fee_version"] = "1", "1"
		boundaryEdit["source_amount_minor"], boundaryEdit["target_amount_minor"] = "2", "2"
		boundaryEdit["fee"].(map[string]any)["amount_minor"] = "2"
		boundaryEdit["fee"].(map[string]any)["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "2"
		boundaryBefore := readAccount(limitTarget)
		expect(send("PUT", root+"/transactions/"+boundaryID, boundaryEdit, id(), user.Credential), 200, "")
		assertBalance(limitTarget, "9000000000000000")
		assertBalance(limitSource, "98")
		bv, _ := strconv.ParseInt(boundaryBefore["balance_version"].(string), 10, 64)
		if readAccount(limitTarget)["balance_version"] != strconv.FormatInt(bv+1, 10) {
			t.Fatal("net-zero boundary edit double bumped account")
		}
		boundaryEdit["expected_version"], boundaryEdit["expected_fee_version"] = "2", "2"
		boundaryEdit["fee"] = nil
		beforeBoundary, beforeBoundaryTarget := readTransaction(boundaryID), readAccount(limitTarget)
		expect(send("PUT", root+"/transactions/"+boundaryID, boundaryEdit, id(), user.Credential), 422, "amount_out_of_range")
		if !reflect.DeepEqual(beforeBoundary, readTransaction(boundaryID)) || !reflect.DeepEqual(beforeBoundaryTarget, readAccount(limitTarget)) {
			t.Fatal("overflow fee removal left partial tombstone/balances")
		}
	}
	{
		// Adjustment delta comes from locked posted balance; target is never stored as an entry.
		a := account("EUR", "10000", false)
		adjustBody := func(aid, target string) map[string]any {
			return map[string]any{"id": id(), "expected_balance_version": readAccount(aid)["balance_version"], "target_balance_minor": target, "reason": "Synthetic reconciliation", "note": "Adjustment note", "occurred_timezone": "UTC"}
		}
		adjust := func(aid string, body map[string]any, key string) response {
			return send("POST", root+"/accounts/"+aid+"/adjustments", body, key, user.Credential)
		}
		body := adjustBody(a, "9500")
		aid := body["id"].(string)
		key := id()
		first := adjust(a, body, key)
		expect(first, 201, "")
		assertBalance(a, "9500")
		adjustment := readTransaction(aid)
		if adjustment["kind"] != "adjustment" || adjustment["reason"] != body["reason"] || adjustment["entries"].([]any)[0].(map[string]any)["amount_minor"] != "-500" || len(adjustment["allocations"].([]any)) != 0 || len(adjustment["tag_ids"].([]any)) != 0 {
			t.Fatal("adjustment is not one signed delta without classification")
		}
		createdAt, e := time.Parse(time.RFC3339Nano, adjustment["occurred_at"].(string))
		if e != nil || time.Since(createdAt) > time.Minute || createdAt.After(time.Now().Add(time.Second)) {
			t.Fatal("adjustment did not use server time")
		}
		beforeNote := readAccount(a)
		note := map[string]any{"kind": "adjustment", "expected_version": "1", "note": "Changed note only"}
		expect(send("PUT", root+"/transactions/"+aid, note, id(), user.Credential), 200, "")
		if !reflect.DeepEqual(beforeNote, readAccount(a)) || readTransaction(aid)["entries"].([]any)[0].(map[string]any)["amount_minor"] != "-500" || readTransaction(aid)["reason"] != body["reason"] {
			t.Fatal("note-only adjustment edit changed money/reason")
		}
		expect(send("PUT", root+"/transactions/"+aid, note, id(), user.Credential), 409, "version_conflict")
		illegal := clone(note)
		illegal["expected_version"] = "2"
		illegal["target_balance_minor"] = "0"
		expect(send("PUT", root+"/transactions/"+aid, illegal, id(), user.Credential), 422, "validation_error")
		positive := adjustBody(a, "10000")
		expect(adjust(a, positive, id()), 201, "")
		if readTransaction(positive["id"].(string))["entries"].([]any)[0].(map[string]any)["amount_minor"] != "500" {
			t.Fatal("positive adjustment delta wrong")
		}
		replay := adjust(a, body, key)
		expect(replay, 201, "")
		if replay.replay != "true" || !reflect.DeepEqual(first.body, replay.body) {
			t.Fatal("adjustment replay recalculated target")
		}
		assertBalance(a, "10000")
		noChange := adjustBody(a, "10000")
		noChangeKey := id()
		expect(adjust(a, noChange, noChangeKey), 422, "no_change")
		expense := map[string]any{"id": id(), "kind": "expense", "account_id": a, "amount_minor": "1", "occurred_at": "2026-01-02T00:00:00Z", "occurred_timezone": "UTC", "note": "Adjustment conflict expense", "payee": "", "tag_ids": []string{}, "allocations": []any{map[string]any{"id": id(), "category_id": nil, "amount_minor": "1"}}}
		expect(post(expense, id()), 201, "")
		noChangeReplay := adjust(a, noChange, noChangeKey)
		expect(noChangeReplay, 422, "no_change")
		if noChangeReplay.replay != "true" {
			t.Fatal("terminal no-change receipt not retained")
		}
		stale := clone(noChange)
		stale["id"] = id()
		stale["target_balance_minor"] = "12000"
		staleResult := adjust(a, stale, id())
		expect(staleResult, 409, "version_conflict")
		if staleResult.body["current_versions"].([]any)[0].(map[string]any)["balance_version"] != readAccount(a)["balance_version"] {
			t.Fatal("adjustment conflict omitted current balance version")
		}
		assertBalance(a, "9999")
		expect(remove(aid, deleteBody(aid), id()), 200, "")
		assertBalance(a, "10499")
		setArchive(a, true)
		expect(adjust(a, adjustBody(a, "0"), id()), 422, "account_archived")
		expect(send("PUT", root+"/transactions/"+positive["id"].(string), map[string]any{"kind": "adjustment", "expected_version": "1", "note": "Archived"}, id(), user.Credential), 422, "account_archived")
		setArchive(a, false)
		expect(adjust(foreignAccount, body, id()), 404, "not_found")
		expect(send("PUT", foreignRoot+"/transactions/"+positive["id"].(string), map[string]any{"kind": "adjustment", "expected_version": "1", "note": "Foreign"}, id(), other.Credential), 404, "not_found")
		// Two independently valid adjustments with the same observed balance: one wins.
		raceAccount := account("EUR", "100", false)
		race1, race2 := adjustBody(raceAccount, "150"), adjustBody(raceAccount, "200")
		raceResults := make(chan response, 2)
		var wg sync.WaitGroup
		for _, candidate := range []map[string]any{race1, race2} {
			wg.Add(1)
			go func(v map[string]any) { defer wg.Done(); raceResults <- adjust(raceAccount, v, id()) }(candidate)
		}
		wg.Wait()
		close(raceResults)
		successes := 0
		for result := range raceResults {
			if result.status == 201 {
				successes++
			} else {
				expect(result, 409, "version_conflict")
			}
		}
		raced := readAccount(raceAccount)
		if successes != 1 || raced["balance_version"] != "2" || (raced["posted_balance_minor"] != "150" && raced["posted_balance_minor"] != "200") {
			t.Fatal("concurrent adjustment silently recalculated stale target")
		}
		// DR-F12 exact 7 -> 8 stale version following a committed expense.
		conflictAccount := account("EUR", "100", false)
		for i := 0; i < 6; i++ {
			next := clone(expense)
			next["id"], next["account_id"] = id(), conflictAccount
			next["allocations"].([]any)[0].(map[string]any)["id"] = id()
			expect(post(next, id()), 201, "")
		}
		observed := adjustBody(conflictAccount, "100")
		if observed["expected_balance_version"] != "7" {
			t.Fatal("wrong DR-F12 fixture version")
		}
		next := clone(expense)
		next["id"], next["account_id"] = id(), conflictAccount
		next["allocations"].([]any)[0].(map[string]any)["id"] = id()
		expect(post(next, id()), 201, "")
		expect(adjust(conflictAccount, observed, id()), 409, "version_conflict")
		assertBalance(conflictAccount, "93")
		// Posted target ignores a pending income, while projected money is still checked.
		pendingAccount := account("EUR", "100", false)
		pendingIncome := clone(expense)
		pendingIncome["id"], pendingIncome["kind"], pendingIncome["account_id"], pendingIncome["amount_minor"] = id(), "income", pendingAccount, "50"
		pendingIncome["allocations"].([]any)[0].(map[string]any)["id"] = id()
		pendingIncome["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "50"
		expect(post(pendingIncome, id()), 201, "")
		if _, e = db.Exec(ctx, `UPDATE transactions SET status='pending' WHERE workspace_id=$1 AND id=$2`, user.Workspace.ID, pendingIncome["id"]); e != nil {
			t.Fatal(e)
		}
		if _, e = db.Exec(ctx, `UPDATE accounts SET version=version+1,balance_version=balance_version+1 WHERE workspace_id=$1 AND id=$2`, user.Workspace.ID, pendingAccount); e != nil {
			t.Fatal(e)
		}
		pendingAdjustment := adjustBody(pendingAccount, "175")
		expect(adjust(pendingAccount, pendingAdjustment, id()), 201, "")
		pendingBalance := readAccount(pendingAccount)
		if pendingBalance["posted_balance_minor"] != "175" || pendingBalance["pending_delta_minor"] != "50" || pendingBalance["projected_balance_minor"] != "225" || readTransaction(pendingAdjustment["id"].(string))["entries"].([]any)[0].(map[string]any)["amount_minor"] != "75" {
			t.Fatal("adjustment used projected balance as target base")
		}
		beforePending := readAccount(pendingAccount)
		expect(adjust(pendingAccount, adjustBody(pendingAccount, "9000000000000000"), id()), 422, "amount_out_of_range")
		if !reflect.DeepEqual(beforePending, readAccount(pendingAccount)) {
			t.Fatal("projected adjustment overflow mutated account")
		}
		// Valid targets can still produce an invalid individual movement delta.
		low, high := account("EUR", "-9000000000000000", false), account("EUR", "9000000000000000", false)
		expect(adjust(low, adjustBody(low, "9000000000000000"), id()), 422, "amount_out_of_range")
		expect(adjust(high, adjustBody(high, "-9000000000000000"), id()), 422, "amount_out_of_range")
		expect(adjust(low, adjustBody(low, "0"), id()), 201, "")
		assertBalance(low, "0")
		// Fault between transaction and entry writes leaves no receipt or account change.
		fault := adjustBody(a, "11000")
		faultKey := id()
		beforeFault := readAccount(a)
		faultSQL := `CREATE FUNCTION fail_adjustment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.transaction_id='` + fault["id"].(string) + `'::uuid THEN RAISE EXCEPTION 'synthetic adjustment fault' USING ERRCODE='XX000'; END IF; RETURN NEW; END $$; CREATE TRIGGER adjustment_fault BEFORE INSERT ON entries FOR EACH ROW EXECUTE FUNCTION fail_adjustment();`
		if _, e = db.Exec(ctx, faultSQL); e != nil {
			t.Fatal(e)
		}
		expect(adjust(a, fault, faultKey), 503, "service_unavailable")
		var count int
		if e = db.QueryRow(ctx, `SELECT count(*) FROM transactions WHERE workspace_id=$1 AND id=$2`, user.Workspace.ID, fault["id"]).Scan(&count); e != nil {
			t.Fatal(e)
		}
		if count != 0 || !reflect.DeepEqual(beforeFault, readAccount(a)) {
			t.Fatal("adjustment fault left partial transaction/account")
		}
		if _, e = db.Exec(ctx, `DROP TRIGGER adjustment_fault ON entries; DROP FUNCTION fail_adjustment()`); e != nil {
			t.Fatal(e)
		}
		expect(adjust(a, fault, faultKey), 201, "")
		history := send("GET", root+"/transactions?kind=adjustment&account_id="+a, nil, "", user.Credential)
		expect(history, 200, "")
		if len(history.body["items"].([]any)) != 2 {
			t.Fatal("adjustment history includes deleted or lacks live operations")
		}
	}
	{
		// Bulk classification is metadata-only across currencies and accounts.
		a, b := account("EUR", "10000", false), account("JPY", "100", false)
		simple := func(kind, accountID, amount string) map[string]any {
			return map[string]any{"id": id(), "kind": kind, "account_id": accountID, "amount_minor": amount, "occurred_at": "2026-01-02T00:00:00Z", "occurred_timezone": "UTC", "note": "Bulk original note", "payee": "Bulk original payee", "tag_ids": []string{}, "allocations": []any{map[string]any{"id": id(), "category_id": nil, "amount_minor": amount}}}
		}
		expense, income := simple("expense", a, "1234"), simple("income", b, "50")
		expect(post(expense, id()), 201, "")
		expect(post(income, id()), 201, "")
		expenseID, incomeID := expense["id"].(string), income["id"].(string)
		categoryA, categoryB, tagA, tagB := id(), id(), id(), id()
		for _, category := range []string{categoryA, categoryB} {
			expect(send("POST", root+"/categories", map[string]any{"id": category, "name": "Bulk category " + category, "parent_id": nil}, id(), user.Credential), 201, "")
		}
		for _, tag := range []string{tagA, tagB} {
			expect(send("POST", root+"/tags", map[string]any{"id": tag, "name": "Bulk tag " + tag}, id(), user.Credential), 201, "")
		}
		bulkBody := func(category any, tags []string, transactionIDs ...string) map[string]any {
			items := []any{}
			for _, transactionID := range transactionIDs {
				items = append(items, map[string]any{"id": transactionID, "expected_version": readTransaction(transactionID)["version"]})
			}
			return map[string]any{"items": items, "category_id": category, "tag_ids": tags}
		}
		classify := func(body map[string]any, key string) response {
			return send("POST", root+"/transactions/classification", body, key, user.Credential)
		}
		assertUnchangedMoney := func(before, after map[string]any) {
			t.Helper()
			for _, field := range []string{"entries", "kind", "status", "occurred_at", "occurred_timezone", "note", "payee", "parent_transaction_id", "rate", "reason"} {
				if !reflect.DeepEqual(before[field], after[field]) {
					t.Fatalf("bulk changed %s", field)
				}
			}
			oldParts, newParts := before["allocations"].([]any), after["allocations"].([]any)
			if len(oldParts) != 1 || len(newParts) != 1 || oldParts[0].(map[string]any)["id"] != newParts[0].(map[string]any)["id"] || oldParts[0].(map[string]any)["amount_minor"] != newParts[0].(map[string]any)["amount_minor"] {
				t.Fatal("bulk changed allocation identity/amount")
			}
		}
		beforeExpense, beforeIncome := readTransaction(expenseID), readTransaction(incomeID)
		beforeA, beforeB := readAccount(a), readAccount(b)
		body := bulkBody(categoryA, []string{tagA}, incomeID, expenseID)
		key := id()
		first := classify(body, key)
		expect(first, 200, "")
		if len(first.body["transactions"].([]any)) != 2 || len(first.body["accounts"].([]any)) != 0 {
			t.Fatal("bulk published balances or omitted an item")
		}
		for _, tid := range []string{expenseID, incomeID} {
			current := readTransaction(tid)
			if current["version"] != "2" || current["allocations"].([]any)[0].(map[string]any)["category_id"] != categoryA || !reflect.DeepEqual(current["tag_ids"], []any{tagA}) {
				t.Fatal("bulk did not replace classification/version")
			}
		}
		assertUnchangedMoney(beforeExpense, readTransaction(expenseID))
		assertUnchangedMoney(beforeIncome, readTransaction(incomeID))
		if !reflect.DeepEqual(beforeA, readAccount(a)) || !reflect.DeepEqual(beforeB, readAccount(b)) {
			t.Fatal("classification changed account versions or balances")
		}
		var groups, changes int
		if e = db.QueryRow(ctx, `SELECT count(*) FROM sync_groups WHERE workspace_id=$1 AND action_id=$2`, user.Workspace.ID, key).Scan(&groups); e != nil {
			t.Fatal(e)
		}
		if e = db.QueryRow(ctx, `SELECT count(*) FROM sync_changes c JOIN sync_groups g USING(workspace_id,generation_id,sequence) WHERE g.workspace_id=$1 AND g.action_id=$2`, user.Workspace.ID, key).Scan(&changes); e != nil {
			t.Fatal(e)
		}
		if groups != 1 || changes != 2 {
			t.Fatal("bulk not published as one complete group")
		}
		replay := classify(body, key)
		expect(replay, 200, "")
		if replay.replay != "true" || !reflect.DeepEqual(first.body, replay.body) {
			t.Fatal("bulk replay changed outcome")
		}
		assertRejectedBulk := func(request map[string]any, status int, code string) {
			t.Helper()
			oldExpense, oldIncome := readTransaction(expenseID), readTransaction(incomeID)
			oldA, oldB := readAccount(a), readAccount(b)
			expect(classify(request, id()), status, code)
			if !reflect.DeepEqual(oldExpense, readTransaction(expenseID)) || !reflect.DeepEqual(oldIncome, readTransaction(incomeID)) || !reflect.DeepEqual(oldA, readAccount(a)) || !reflect.DeepEqual(oldB, readAccount(b)) {
				t.Fatal("rejected bulk left partial classification or accounts")
			}
		}
		stale := bulkBody(categoryB, []string{tagB}, expenseID, incomeID)
		stale["items"].([]any)[1].(map[string]any)["expected_version"] = "1"
		assertRejectedBulk(stale, 409, "version_conflict")
		collision := clone(body)
		collision["category_id"] = categoryB
		expect(classify(collision, key), 409, "idempotency_conflict")
		// Unchanged archive assignments survive, but any new archived assignment rejects all items.
		expect(send("PUT", root+"/categories/"+categoryA, map[string]any{"expected_version": "1", "name": "Bulk category " + categoryA, "parent_id": nil, "archived": true}, id(), user.Credential), 200, "")
		expect(send("PUT", root+"/tags/"+tagA, map[string]any{"expected_version": "1", "name": "Bulk tag " + tagA, "archived": true}, id(), user.Credential), 200, "")
		expect(classify(bulkBody(categoryA, []string{tagA}, expenseID, incomeID), id()), 200, "")
		third := simple("expense", a, "1")
		thirdID := third["id"].(string)
		expect(post(third, id()), 201, "")
		assertRejectedBulk(bulkBody(categoryA, []string{tagB}, expenseID, thirdID), 422, "validation_error")
		assertRejectedBulk(bulkBody(nil, []string{tagA}, expenseID, thirdID), 422, "validation_error")
		setArchive(a, true)
		assertRejectedBulk(bulkBody(categoryB, []string{tagB}, expenseID, incomeID), 422, "account_archived")
		setArchive(a, false)
		foreignCategory, foreignTag := id(), id()
		expect(send("POST", foreignRoot+"/categories", map[string]any{"id": foreignCategory, "name": "Foreign bulk category", "parent_id": nil}, id(), other.Credential), 201, "")
		expect(send("POST", foreignRoot+"/tags", map[string]any{"id": foreignTag, "name": "Foreign bulk tag"}, id(), other.Credential), 201, "")
		assertRejectedBulk(bulkBody(foreignCategory, []string{}, expenseID, incomeID), 404, "not_found")
		assertRejectedBulk(bulkBody(nil, []string{foreignTag}, expenseID, incomeID), 404, "not_found")
		foreignExpense := simple("expense", foreignAccount, "1")
		foreignID := foreignExpense["id"].(string)
		expect(send("POST", foreignRoot+"/transactions", foreignExpense, id(), other.Credential), 201, "")
		mixed := bulkBody(categoryB, []string{}, expenseID)
		mixed["items"] = append(mixed["items"].([]any), map[string]any{"id": foreignID, "expected_version": "1"})
		assertRejectedBulk(mixed, 404, "not_found")
		expect(send("POST", foreignRoot+"/transactions/classification", bulkBody(nil, []string{}, expenseID), id(), other.Credential), 404, "not_found")
		// Returned expense (posted and pending) and unsupported forms cannot enter bulk.
		original := simple("expense", a, "100")
		originalID := original["id"].(string)
		originalPart := original["allocations"].([]any)[0].(map[string]any)["id"].(string)
		expect(post(original, id()), 201, "")
		returned := refund(originalID, originalPart, "1", a, "1")
		returnedID := returned["id"].(string)
		expect(post(returned, id()), 201, "")
		assertRejectedBulk(bulkBody(categoryB, []string{}, expenseID, originalID), 409, "dependent_transactions")
		if _, e = db.Exec(ctx, `UPDATE transactions SET status='pending' WHERE workspace_id=$1 AND id=$2`, user.Workspace.ID, returnedID); e != nil {
			t.Fatal(e)
		}
		if _, e = db.Exec(ctx, `UPDATE accounts SET version=version+1,balance_version=balance_version+1 WHERE workspace_id=$1 AND id=$2`, user.Workspace.ID, a); e != nil {
			t.Fatal(e)
		}
		assertRejectedBulk(bulkBody(categoryB, []string{}, expenseID, originalID), 409, "dependent_transactions")
		split := simple("expense", a, "2")
		split["allocations"] = []any{map[string]any{"id": id(), "category_id": nil, "amount_minor": "1"}, map[string]any{"id": id(), "category_id": nil, "amount_minor": "1"}}
		expect(post(split, id()), 201, "")
		transferred := transfer(a, b, "1", "1")
		expect(post(transferred, id()), 201, "")
		feeTransfer := transfer(a, account("EUR", "0", false), "1", "1")
		feeTransfer["fee"] = fee(a, "1")
		expect(post(feeTransfer, id()), 201, "")
		adjusted := map[string]any{"id": id(), "expected_balance_version": readAccount(a)["balance_version"], "target_balance_minor": "9000", "reason": "Bulk fixture adjustment", "note": "", "occurred_timezone": "UTC"}
		expect(send("POST", root+"/accounts/"+a+"/adjustments", adjusted, id(), user.Credential), 201, "")
		var openingID string
		if e = db.QueryRow(ctx, `SELECT id::text FROM transactions WHERE workspace_id=$1 AND opening_account_id=$2`, user.Workspace.ID, a).Scan(&openingID); e != nil {
			t.Fatal(e)
		}
		for _, unsupported := range []string{split["id"].(string), transferred["id"].(string), feeTransfer["fee"].(map[string]any)["id"].(string), adjusted["id"].(string), openingID, returnedID} {
			assertRejectedBulk(bulkBody(categoryB, []string{}, expenseID, unsupported), 422, "validation_error")
		}
		expect(remove(returnedID, deleteBody(returnedID, originalID), id()), 200, "")
		expect(classify(bulkBody(categoryB, []string{}, originalID), id()), 200, "")
		deletedRequest := bulkBody(categoryB, []string{}, expenseID, thirdID)
		expect(remove(thirdID, deleteBody(thirdID), id()), 200, "")
		assertRejectedBulk(deletedRequest, 404, "not_found")
		// Fault on the second item after writes to the first must roll back the whole package.
		faultBody := bulkBody(categoryB, []string{tagB}, expenseID, incomeID)
		faultKey := id()
		beforeFaultExpense, beforeFaultIncome := readTransaction(expenseID), readTransaction(incomeID)
		beforeFaultA, beforeFaultB := readAccount(a), readAccount(b)
		faultSQL := `CREATE FUNCTION fail_bulk_classification() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.transaction_id='` + incomeID + `'::uuid THEN RAISE EXCEPTION 'synthetic bulk fault' USING ERRCODE='XX000'; END IF; RETURN NEW; END $$; CREATE TRIGGER bulk_fault BEFORE UPDATE ON allocations FOR EACH ROW EXECUTE FUNCTION fail_bulk_classification();`
		if _, e = db.Exec(ctx, faultSQL); e != nil {
			t.Fatal(e)
		}
		expect(classify(faultBody, faultKey), 503, "service_unavailable")
		if !reflect.DeepEqual(beforeFaultExpense, readTransaction(expenseID)) || !reflect.DeepEqual(beforeFaultIncome, readTransaction(incomeID)) || !reflect.DeepEqual(beforeFaultA, readAccount(a)) || !reflect.DeepEqual(beforeFaultB, readAccount(b)) {
			t.Fatal("bulk fault left first item written")
		}
		if _, e = db.Exec(ctx, `DROP TRIGGER bulk_fault ON allocations; DROP FUNCTION fail_bulk_classification()`); e != nil {
			t.Fatal(e)
		}
		expect(classify(faultBody, faultKey), 200, "")
		clear := bulkBody(nil, []string{}, expenseID, incomeID)
		expect(classify(clear, id()), 200, "")
		if readTransaction(expenseID)["allocations"].([]any)[0].(map[string]any)["category_id"] != nil || len(readTransaction(incomeID)["tag_ids"].([]any)) != 0 {
			t.Fatal("bulk null/empty did not replace classification")
		}
		oldReplay := classify(body, key)
		expect(oldReplay, 200, "")
		if !reflect.DeepEqual(oldReplay.body, first.body) {
			t.Fatal("bulk receipt altered after later classification")
		}
		// Concurrent bulk and note PUT: one group commits, the other conflicts.
		raceBulk := bulkBody(categoryB, []string{tagB}, expenseID, incomeID)
		beforeRaceExpense, beforeRaceIncome := readTransaction(expenseID), readTransaction(incomeID)
		beforeRaceA, beforeRaceB := readAccount(a), readAccount(b)
		raceNote := clone(expense)
		delete(raceNote, "id")
		raceNote["expected_version"] = beforeRaceExpense["version"]
		raceNote["expected_parent_version"] = nil
		raceNote["note"] = "Concurrent bulk note"
		type raceResult struct {
			Bulk     bool
			Response response
		}
		raced := make(chan raceResult, 2)
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); raced <- raceResult{true, classify(raceBulk, id())} }()
		go func() {
			defer wg.Done()
			raced <- raceResult{false, send("PUT", root+"/transactions/"+expenseID, raceNote, id(), user.Credential)}
		}()
		wg.Wait()
		close(raced)
		winners := 0
		bulkWon := false
		for result := range raced {
			if result.Response.status == 200 {
				winners++
				bulkWon = result.Bulk
			} else {
				expect(result.Response, 409, "version_conflict")
			}
		}
		oldV, _ := strconv.ParseInt(beforeRaceExpense["version"].(string), 10, 64)
		oldIncomeV, _ := strconv.ParseInt(beforeRaceIncome["version"].(string), 10, 64)
		if bulkWon {
			oldIncomeV++
		}
		if winners != 1 || readTransaction(expenseID)["version"] != strconv.FormatInt(oldV+1, 10) || readTransaction(incomeID)["version"] != strconv.FormatInt(oldIncomeV, 10) || !reflect.DeepEqual(beforeRaceA, readAccount(a)) || !reflect.DeepEqual(beforeRaceB, readAccount(b)) {
			t.Fatal("bulk race committed stale or partial package")
		}
		// Full 100-item success, followed by group-size rejection with complete rollback.
		largeAccount := account("EUR", "1000", false)
		largeIDs := []string{}
		for i := 0; i < 100; i++ {
			operation := simple("expense", largeAccount, "1")
			operation["note"] = strings.Repeat("🙂", 2000)
			expect(post(operation, id()), 201, "")
			largeIDs = append(largeIDs, operation["id"].(string))
		}
		largeBefore := readAccount(largeAccount)
		complete := bulkBody(nil, []string{}, largeIDs...)
		fullKey := id()
		full := classify(complete, fullKey)
		expect(full, 200, "")
		if len(full.body["transactions"].([]any)) != 100 || !reflect.DeepEqual(largeBefore, readAccount(largeAccount)) {
			t.Fatal("100-item bulk incomplete or changed money")
		}
		largeTags := []string{}
		for i := 0; i < 50; i++ {
			tag := id()
			expect(send("POST", root+"/tags", map[string]any{"id": tag, "name": "Large tag " + tag}, id(), user.Credential), 201, "")
			largeTags = append(largeTags, tag)
		}
		oversized := bulkBody(nil, largeTags, largeIDs...)
		oversizedKey := id()
		expect(classify(oversized, oversizedKey), 422, "sync_group_too_large")
		for _, transactionID := range largeIDs {
			value := readTransaction(transactionID)
			if value["version"] != "2" || len(value["tag_ids"].([]any)) != 0 {
				t.Fatal("oversized group retained classification/version")
			}
		}
		if !reflect.DeepEqual(largeBefore, readAccount(largeAccount)) {
			t.Fatal("oversized group changed financial account")
		}
	}
	if path := os.Getenv("ADVANCED_RESPONSE_FILE"); path != "" {
		data, e := json.MarshalIndent(observations, "", "  ")
		if e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(path, data, 0600); e != nil {
			t.Fatal(e)
		}
	}
}
