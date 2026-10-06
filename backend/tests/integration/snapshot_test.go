package integration

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	"accounting/backend/internal/platform"
	commands "accounting/backend/internal/sync"
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
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestSnapshot(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 180*time.Second)
	defer cancel()
	cfg, e := pgxpool.ParseConfig(databaseURL)
	if e != nil || cfg.ConnConfig.Database != "accounting_test" {
		t.Fatal("requires isolated accounting_test")
	}
	admin, e := pgxpool.NewWithConfig(ctx, cfg)
	if e != nil {
		t.Fatal("database connection failed")
	}
	defer admin.Close()
	schema := fmt.Sprintf("snapshot_%d", time.Now().UnixNano())
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
	user, e := auth.Register(ctx, "snapshot@example.test", "synthetic transfer password", "advanced", "UTC")
	if e != nil {
		t.Fatal(e)
	}
	other, e := auth.Register(ctx, "other-snapshot@example.test", "synthetic other password", "other", "UTC")
	if e != nil {
		t.Fatal(e)
	}
	keys, e := commands.NewCursorKeys("v1", map[string][]byte{"v1": bytes.Repeat([]byte{71}, 32)})
	if e != nil {
		t.Fatal(e)
	}
	handler, e := platform.NewAPIHandler(db, platform.Config{Environment: "test", CursorKeys: keys, PublicOrigin: "https://advanced.example.test", AuthHashConcurrency: 2}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	server := httptest.NewTLSServer(handler)
	defer server.Close()

	var counter atomic.Int64
	id := func() string { return fmt.Sprintf("c1000000-0000-4000-8000-%012d", counter.Add(1)) }
	root := "/api/v1/workspaces/" + user.Workspace.ID
	commandGeneration := user.Workspace.Generation
	type observed struct {
		Schema string          `json:"schema"`
		Body   json.RawMessage `json:"body"`
	}
	captured := []observed{}
	var capturedMu sync.Mutex
	call := func(method, path string, body any, action, token string, want int, code string) json.RawMessage {
		var buf bytes.Buffer
		if body != nil {
			if e := json.NewEncoder(&buf).Encode(body); e != nil {
				t.Fatal(e)
			}
		}
		req, e := http.NewRequest(method, server.URL+path, &buf)
		if e != nil {
			t.Fatal(e)
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		if method != "GET" {
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("Idempotency-Key", action)
			if !strings.Contains(path, "/sync/snapshots") {
				req.Header.Set("X-Sync-Generation", commandGeneration)
			}
		}
		response, e := server.Client().Do(req)
		if e != nil {
			t.Fatal(e)
		}
		defer response.Body.Close()
		data, e := io.ReadAll(response.Body)
		if e != nil {
			t.Fatal(e)
		}
		if response.StatusCode != want {
			t.Fatalf("%s %s: status %d expected %d: %s", method, path, response.StatusCode, want, data)
		}
		schema := "Snapshot"
		if strings.Contains(path, "/pages") {
			schema = "SnapshotPage"
		} else if strings.Contains(path, "/sync/changes") {
			schema = "SyncChanges"
		} else if !strings.Contains(path, "/sync/snapshots") {
			schema = "MutationResult"
		}
		if want >= 400 {
			schema = "Error"
			var v struct{ Code string }
			json.Unmarshal(data, &v)
			if v.Code != code {
				t.Fatalf("code %s expected %s", v.Code, code)
			}
		}
		capturedMu.Lock()
		captured = append(captured, observed{schema, data})
		capturedMu.Unlock()
		return data
	}
	create := func(snapshotID string, want int, code string) commands.Snapshot {
		raw := call("POST", root+"/sync/snapshots", map[string]any{"id": snapshotID}, snapshotID, user.Credential, want, code)
		var result commands.Snapshot
		if want == 201 {
			if e := json.Unmarshal(raw, &result); e != nil {
				t.Fatal(e)
			}
		}
		return result
	}
	page := func(snapshot commands.Snapshot, token string, want int, code string) commands.SnapshotPage {
		raw := call("GET", root+"/sync/snapshots/"+snapshot.ID+"/pages?page_token="+url.QueryEscape(token), nil, "", user.Credential, want, code)
		var result commands.SnapshotPage
		if want == 200 {
			if e := json.Unmarshal(raw, &result); e != nil {
				t.Fatal(e)
			}
		}
		return result
	}
	get := func(snapshot commands.Snapshot, want int, code string) commands.Snapshot {
		raw := call("GET", root+"/sync/snapshots/"+snapshot.ID, nil, "", user.Credential, want, code)
		var result commands.Snapshot
		if want == 200 {
			json.Unmarshal(raw, &result)
		}
		return result
	}
	expire := func(snapshotID string) {
		if _, e := db.Exec(ctx, `UPDATE sync_snapshots SET created_at=statement_timestamp()-interval '16 minutes',expires_at=statement_timestamp()-interval '1 minute' WHERE id=$1`, snapshotID); e != nil {
			t.Fatal(e)
		}
	}
	empty := create(id(), 201, "")
	if empty.Count != 0 || empty.Base != "0" || empty.Expires.Sub(empty.Created) != 15*time.Minute {
		t.Fatal("empty snapshot metadata")
	}
	emptyPage := page(empty, empty.First, 200, "")
	if len(emptyPage.Items) != 0 || emptyPage.Next != nil {
		t.Fatal("empty final page")
	}
	if !reflect.DeepEqual(create(empty.ID, 201, ""), empty) || !reflect.DeepEqual(get(empty, 200, ""), empty) {
		t.Fatal("metadata replay changed")
	}
	call("POST", root+"/sync/snapshots", map[string]any{"id": id()}, id(), user.Credential, 400, "bad_request")
	call("POST", root+"/sync/snapshots", map[string]any{"id": id(), "extra": true}, id(), user.Credential, 422, "validation_error")
	call("POST", root+"/sync/snapshots", map[string]any{"ID": id()}, id(), user.Credential, 422, "validation_error")
	call("GET", root+"/sync/snapshots/"+empty.ID, nil, "", other.Credential, 404, "not_found")
	call("GET", root+"/sync/snapshots/"+empty.ID, nil, "", "", 401, "unauthenticated")
	accountID := id()
	openingRaw := call("POST", root+"/accounts", map[string]any{"id": accountID, "name": "Snapshot account", "type": "bank", "currency": "EUR", "opened_at": "2026-01-01T00:00:00Z", "occurred_timezone": "UTC", "opening_balance_minor": "1000"}, id(), user.Credential, 201, "")
	var opening struct{ Transactions []json.RawMessage }
	json.Unmarshal(openingRaw, &opening)
	categoryID, tagID := id(), id()
	call("POST", root+"/categories", map[string]any{"id": categoryID, "name": "Archive category", "parent_id": nil}, id(), user.Credential, 201, "")
	call("PUT", root+"/categories/"+categoryID, map[string]any{"name": "Archive category", "parent_id": nil, "archived": true, "expected_version": "1"}, id(), user.Credential, 200, "")
	call("POST", root+"/tags", map[string]any{"id": tagID, "name": "Archive tag"}, id(), user.Credential, 201, "")
	call("PUT", root+"/tags/"+tagID, map[string]any{"name": "Archive tag", "archived": true, "expected_version": "1"}, id(), user.Credential, 200, "")
	// Valid posted/pending aggregates and archived/deleted fixtures exercise the
	// read model; SQL fixtures do not purport to be new financial commands.
	incomeID := id()
	expenseID := id()
	deletedID := id()
	for i, tid := range []string{incomeID, expenseID, deletedID} {
		kind := "income"
		amount := "25"
		if i > 0 {
			kind = "expense"
			amount = "10"
		}
		body := map[string]any{"id": tid, "kind": kind, "account_id": accountID, "amount_minor": amount, "occurred_at": "2026-10-01T00:00:00Z", "occurred_timezone": "UTC", "payee": "Synthetic", "note": "", "allocations": []any{map[string]any{"id": id(), "category_id": nil, "amount_minor": amount}}, "tag_ids": []string{}}
		call("POST", root+"/transactions", body, id(), user.Credential, 201, "")
	}
	if _, e = db.Exec(ctx, `UPDATE transactions SET status='pending' WHERE id=$1`, incomeID); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(ctx, `UPDATE transactions SET deleted_at=clock_timestamp() WHERE id=$1`, deletedID); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(ctx, `UPDATE accounts SET archived_at=clock_timestamp(),version=version+1,balance_version=balance_version+1 WHERE id=$1`, accountID); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(ctx, `INSERT INTO tags(id,workspace_id,name,name_normalized) SELECT gen_random_uuid(),$1,'Page '||n,'page '||n FROM generate_series(1,103)n`, user.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	var receiptsBefore, groupsBefore int
	db.QueryRow(ctx, `SELECT count(*) FROM actions WHERE workspace_id=$1`, user.Workspace.ID).Scan(&receiptsBefore)
	db.QueryRow(ctx, `SELECT count(*) FROM sync_groups WHERE workspace_id=$1`, user.Workspace.ID).Scan(&groupsBefore)
	snapshotID := id()
	start := make(chan struct{})
	results := make(chan commands.Snapshot, 2)
	for i := 0; i < 2; i++ {
		go func() { <-start; results <- create(snapshotID, 201, "") }()
	}
	close(start)
	snapshot := <-results
	second := <-results
	if !reflect.DeepEqual(snapshot, second) || snapshot.Count != 109 {
		t.Fatal("concurrent equal ID or item count")
	}
	var stored int
	db.QueryRow(ctx, `SELECT count(*) FROM sync_snapshots WHERE id=$1`, snapshot.ID).Scan(&stored)
	if stored != 1 {
		t.Fatal("duplicate snapshot")
	}
	create(id(), 429, "rate_limited")
	var receiptsAfter, groupsAfter int
	db.QueryRow(ctx, `SELECT count(*) FROM actions WHERE workspace_id=$1`, user.Workspace.ID).Scan(&receiptsAfter)
	db.QueryRow(ctx, `SELECT count(*) FROM sync_groups WHERE workspace_id=$1`, user.Workspace.ID).Scan(&groupsAfter)
	if receiptsAfter != receiptsBefore || groupsAfter != groupsBefore {
		t.Fatal("snapshot wrote financial receipt/group")
	}
	first := page(snapshot, snapshot.First, 200, "")
	if len(first.Items) != 100 || first.Next == nil {
		t.Fatal("fixed first page")
	}
	last := page(snapshot, *first.Next, 200, "")
	if len(last.Items) != 9 || last.Next != nil {
		t.Fatal("fixed final page")
	}
	all := append(append([]commands.SnapshotItem{}, first.Items...), last.Items...)
	seen := map[string]bool{}
	rank := map[string]int{"category": 1, "tag": 2, "account": 3, "transaction": 4}
	previousRank := 0
	previousID := ""
	for _, item := range all {
		var data map[string]any
		json.Unmarshal(item.Data, &data)
		tid := data["id"].(string)
		if seen[item.Kind+tid] || rank[item.Kind] < previousRank || rank[item.Kind] == previousRank && tid <= previousID {
			t.Fatal("snapshot order/duplicates")
		}
		seen[item.Kind+tid] = true
		previousRank = rank[item.Kind]
		previousID = tid
		if item.Kind == "account" {
			if data["posted_balance_minor"] != "990" || data["pending_delta_minor"] != "25" || data["projected_balance_minor"] != "1015" || data["archived_at"] == nil {
				t.Fatal("snapshot account components")
			}
		}
		if tid == categoryID || tid == tagID {
			if data["archived_at"] == nil {
				t.Fatal("archived reference omitted")
			}
		}
		if tid == incomeID && data["status"] != "pending" {
			t.Fatal("pending omitted")
		}
	}
	if !seen["transaction"+incomeID] || seen["transaction"+deletedID] {
		t.Fatal("active snapshot selection")
	}
	// Later commands cannot change the materialized page. Pull resumes at its head.
	call("PUT", root+"/tags/"+tagID, map[string]any{"name": "Changed after snapshot", "archived": true, "expected_version": "2"}, id(), user.Credential, 200, "")
	if !reflect.DeepEqual(page(snapshot, snapshot.First, 200, ""), first) || !reflect.DeepEqual(page(snapshot, *first.Next, 200, ""), last) {
		t.Fatal("snapshot page mutated")
	}
	var pulled commands.PullPage
	json.Unmarshal(call("GET", root+"/sync/changes?cursor="+url.QueryEscape(snapshot.Resume), nil, "", user.Credential, 200, ""), &pulled)
	if len(pulled.Groups) != 1 || pulled.Groups[0].Changes[0].EntityType != "tag" {
		t.Fatal("bootstrap missed subsequent commit")
	}
	page(snapshot, snapshot.Resume, 400, "cursor_invalid")
	page(snapshot, snapshot.First[:len(snapshot.First)-5]+"AAAAA", 400, "cursor_invalid")
	page(snapshot, empty.First, 400, "cursor_invalid")
	call("GET", root+"/sync/snapshots/"+snapshot.ID+"/pages?page_token="+snapshot.First+"&limit=100", nil, "", user.Credential, 400, "bad_request")
	// Key rotation preserves immutable metadata and pages through persisted key ID.
	rotated, _ := commands.NewCursorKeys("v2", map[string][]byte{"v1": bytes.Repeat([]byte{71}, 32), "v2": bytes.Repeat([]byte{72}, 32)})
	rotatedHandler, e := platform.NewAPIHandler(db, platform.Config{Environment: "test", CursorKeys: rotated, PublicOrigin: "https://advanced.example.test", AuthHashConcurrency: 2}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	rotatedServer := httptest.NewTLSServer(rotatedHandler)
	defer rotatedServer.Close()
	req, _ := http.NewRequest("GET", rotatedServer.URL+root+"/sync/snapshots/"+snapshot.ID, nil)
	req.Header.Set("Authorization", "Bearer "+user.Credential)
	response, e := rotatedServer.Client().Do(req)
	if e != nil {
		t.Fatal(e)
	}
	var rotatedSnapshot commands.Snapshot
	e = json.NewDecoder(response.Body).Decode(&rotatedSnapshot)
	response.Body.Close()
	if e != nil || response.StatusCode != 200 || !reflect.DeepEqual(snapshot, rotatedSnapshot) {
		t.Fatal("rotation changed metadata")
	}
	expire(empty.ID)
	create(empty.ID, 410, "snapshot_expired")
	get(empty, 410, "snapshot_expired")
	page(empty, empty.First, 410, "snapshot_expired")
	// Expired payload cleanup retains an ID marker and never permits reuse.
	cleaned, e := commands.CleanupSync(ctx, db, 100)
	if e != nil || cleaned.Snapshots != 1 {
		t.Fatal("expired cleanup", cleaned, e)
	}
	var markers, items int
	db.QueryRow(ctx, `SELECT count(*) FROM sync_snapshots WHERE id=$1 AND payload_cleared_at IS NOT NULL`, empty.ID).Scan(&markers)
	db.QueryRow(ctx, `SELECT count(*) FROM sync_snapshot_items WHERE snapshot_id=$1`, empty.ID).Scan(&items)
	if markers != 1 || items != 0 {
		t.Fatal("marker lost")
	}
	create(empty.ID, 410, "snapshot_expired")
	// Three distinct concurrent creations with one quota slot: exactly one wins.
	quotaIDs := []string{id(), id(), id()}
	wins := make(chan error, 3)
	service := &commands.SnapshotService{Identity: auth, Keys: keys, ReadAggregate: func(ctx context.Context, tx pgx.Tx, ws, kind, tid string) (json.RawMessage, error) {
		switch kind {
		case "account":
			return ledger.ReadAccount(ctx, tx, ws, tid)
		case "transaction":
			return ledger.ReadTransaction(ctx, tx, ws, tid)
		default:
			return ledger.ReadClassification(ctx, tx, ws, tid, kind)
		}
	}}
	for _, qid := range quotaIDs {
		go func(qid string) {
			_, err := service.Create(ctx, user.Credential, user.Workspace.ID, qid, nil)
			wins <- err
		}(qid)
	}
	winner := 0
	for i := 0; i < 3; i++ {
		err := <-wins
		if err == nil {
			winner++
		} else {
			problem, ok := err.(*identity.Error)
			if !ok || problem.Status != 429 {
				t.Fatal("quota race", err)
			}
		}
	}
	if winner != 1 {
		t.Fatal("quota race exceeded two live snapshots")
	}
	// Actor isolation applies even when a second member knows the snapshot ID/token.
	if _, e = db.Exec(ctx, `INSERT INTO memberships(workspace_id,user_id,role) VALUES($1,$2,'member')`, user.Workspace.ID, other.Profile.ID); e != nil {
		t.Fatal(e)
	}
	call("GET", root+"/sync/snapshots/"+snapshot.ID+"/pages?page_token="+snapshot.First, nil, "", other.Credential, 404, "not_found")
	// Head SHARE holds writers until copying finishes; snapshot base/data are old,
	// then the next pull receives the complete writer group.
	if _, e = db.Exec(ctx, `UPDATE sync_snapshots SET created_at=statement_timestamp()-interval '16 minutes',expires_at=statement_timestamp()-interval '1 minute' WHERE workspace_id=$1`, user.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	entered := make(chan struct{})
	release := make(chan struct{})
	originalReader := service.ReadAggregate
	var once sync.Once
	service.ReadAggregate = func(ctx context.Context, tx pgx.Tx, ws, kind, tid string) (json.RawMessage, error) {
		once.Do(func() { close(entered); <-release })
		return originalReader(ctx, tx, ws, kind, tid)
	}
	type created struct {
		snapshot commands.Snapshot
		err      error
	}
	pending := make(chan created, 1)
	blockedID := id()
	go func() {
		s, err := service.Create(ctx, user.Credential, user.Workspace.ID, blockedID, nil)
		pending <- created{s, err}
	}()
	<-entered
	writerDone := make(chan error, 1)
	runner := commands.Runner{Identity: auth}
	action := id()
	requestID := id()
	go func() {
		_, err := runner.Execute(ctx, commands.Request{Credential: other.Credential, WorkspaceID: user.Workspace.ID, GenerationID: user.Workspace.Generation, ActionID: action, RequestID: requestID, Method: "PUT", Path: root + "/tags/" + tagID, Body: []byte(`{"fixture":"head wait"}`)}, ledger.ClassificationCommand(user.Workspace.ID, tagID, "tag", true, ledger.ClassificationInput{Name: "After head lock", ExpectedVersion: "3", Archived: true}))
		writerDone <- err
	}()
	select {
	case err := <-writerDone:
		t.Fatal("writer did not wait", err)
	case <-time.After(100 * time.Millisecond):
	}
	close(release)
	createdResult := <-pending
	if createdResult.err != nil {
		t.Fatal(createdResult.err)
	}
	if e = <-writerDone; e != nil {
		t.Fatal(e)
	}
	var follow commands.PullPage
	json.Unmarshal(call("GET", root+"/sync/changes?cursor="+url.QueryEscape(createdResult.snapshot.Resume), nil, "", user.Credential, 200, ""), &follow)
	if len(follow.Groups) != 1 {
		t.Fatal("snapshot/commit handoff")
	}
	service.ReadAggregate = originalReader
	// Generation reset expires live IDs without deleting their markers.
	newGeneration := id()
	reset, e := db.Begin(ctx)
	if e != nil {
		t.Fatal(e)
	}
	for _, query := range []string{`DELETE FROM sync_groups WHERE workspace_id=$1`, `UPDATE workspaces SET sync_generation_id=$2 WHERE id=$1`, `UPDATE sync_heads SET generation_id=$2,last_sequence=0,min_available_sequence=1 WHERE workspace_id=$1`} {
		if strings.Contains(query, "$2") {
			_, e = reset.Exec(ctx, query, user.Workspace.ID, newGeneration)
		} else {
			_, e = reset.Exec(ctx, query, user.Workspace.ID)
		}
		if e != nil {
			t.Fatal(e)
		}
	}
	if e = reset.Commit(ctx); e != nil {
		t.Fatal(e)
	}
	get(createdResult.snapshot, 410, "snapshot_expired")
	page(createdResult.snapshot, createdResult.snapshot.First, 410, "snapshot_expired")
	create(createdResult.snapshot.ID, 410, "snapshot_expired")
	commandGeneration = newGeneration
	refreshed := create(id(), 201, "")
	if refreshed.Generation != newGeneration || refreshed.Base != "0" {
		t.Fatal("new generation bootstrap")
	}
	if _, e = db.Exec(ctx, `UPDATE memberships SET revoked_at=clock_timestamp() WHERE workspace_id=$1 AND user_id=$2`, user.Workspace.ID, user.Profile.ID); e != nil {
		t.Fatal(e)
	}
	get(refreshed, 404, "not_found")
	page(refreshed, "bad", 404, "not_found")
	if _, e = db.Exec(ctx, `UPDATE memberships SET revoked_at=NULL WHERE workspace_id=$1 AND user_id=$2`, user.Workspace.ID, user.Profile.ID); e != nil {
		t.Fatal(e)
	}
	// Failed COPY rolls back the metadata marker and all items; same-ID retry works.
	faultID := id()
	if _, e = db.Exec(ctx, `CREATE FUNCTION snapshot_copy_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.position=2 THEN RAISE EXCEPTION 'synthetic snapshot copy failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER snapshot_copy_fault BEFORE INSERT ON sync_snapshot_items FOR EACH ROW EXECUTE FUNCTION snapshot_copy_fault()`); e != nil {
		t.Fatal(e)
	}
	create(faultID, 503, "service_unavailable")
	var faultMarkers int
	db.QueryRow(ctx, `SELECT count(*) FROM sync_snapshots WHERE id=$1`, faultID).Scan(&faultMarkers)
	if faultMarkers != 0 {
		t.Fatal("failed snapshot marker persisted")
	}
	if _, e = db.Exec(ctx, `DROP TRIGGER snapshot_copy_fault ON sync_snapshot_items; DROP FUNCTION snapshot_copy_fault()`); e != nil {
		t.Fatal(e)
	}
	recovered := create(faultID, 201, "")
	if recovered.Count != 109 {
		t.Fatal("snapshot retry truncated")
	}
	// Cookie POST requires Origin and CSRF, even though snapshot is read-only.
	csrfReq, _ := http.NewRequest("POST", server.URL+root+"/sync/snapshots", strings.NewReader(`{"id":"`+id()+`"}`))
	csrfReq.Header.Set("Content-Type", "application/json")
	csrfReq.Header.Set("Idempotency-Key", id())
	csrfReq.AddCookie(&http.Cookie{Name: "__Host-accounting_session", Value: user.Credential})
	// Use matching body/header so CSRF is the actual rejection, not wire validation.
	csrfID := id()
	csrfReq.Body = io.NopCloser(strings.NewReader(`{"id":"` + csrfID + `"}`))
	csrfReq.ContentLength = -1
	csrfReq.Header.Set("Idempotency-Key", csrfID)
	csrfResponse, e := server.Client().Do(csrfReq)
	if e != nil {
		t.Fatal(e)
	}
	csrfData, _ := io.ReadAll(csrfResponse.Body)
	csrfResponse.Body.Close()
	if csrfResponse.StatusCode != 403 {
		t.Fatalf("snapshot CSRF status %d", csrfResponse.StatusCode)
	}
	captured = append(captured, observed{"Error", csrfData})
	// Journal age fixtures: stop at the first young group even if a later group is
	// older. A locked head must be skipped, and receipts survive group cleanup.
	for i := 0; i < 3; i++ {
		call("POST", root+"/tags", map[string]any{"id": id(), "name": fmt.Sprintf("Retention %d", i)}, id(), user.Credential, 201, "")
	}
	if _, e = db.Exec(ctx, `UPDATE sync_groups SET created_at=statement_timestamp()-interval '100 days' WHERE workspace_id=$1 AND sequence IN(1,3)`, user.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	held, e := db.Begin(ctx)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = held.Exec(ctx, `SELECT 1 FROM sync_heads WHERE workspace_id=$1 FOR UPDATE`, user.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	skipped, e := commands.CleanupSync(ctx, db, 100)
	if e != nil || skipped.Groups != 0 || skipped.Snapshots != 0 {
		t.Fatal("cleanup did not skip held head", e)
	}
	held.Rollback(ctx)
	var receiptCount int
	db.QueryRow(ctx, `SELECT count(*) FROM actions WHERE workspace_id=$1`, user.Workspace.ID).Scan(&receiptCount)
	cleaned, e = commands.CleanupSync(ctx, db, 100)
	if e != nil || cleaned.Groups != 1 {
		t.Fatal("retention prefix", cleaned, e)
	}
	var minimum int64
	db.QueryRow(ctx, `SELECT min_available_sequence FROM sync_heads WHERE workspace_id=$1`, user.Workspace.ID).Scan(&minimum)
	if minimum != 2 {
		t.Fatal("retention crossed young group")
	}
	var changeCount int
	db.QueryRow(ctx, `SELECT count(*) FROM sync_changes WHERE workspace_id=$1 AND sequence=1`, user.Workspace.ID).Scan(&changeCount)
	if changeCount != 0 {
		t.Fatal("partial retained group")
	}
	cleaned, e = commands.CleanupSync(ctx, db, 100)
	if e != nil || cleaned.Groups != 0 {
		t.Fatal("young prefix was skipped", e)
	}
	zeroCurrent, _ := keys.Sign(user.Profile.ID, user.Workspace.ID, newGeneration, 0)
	call("GET", root+"/sync/changes?cursor="+url.QueryEscape(zeroCurrent), nil, "", user.Credential, 410, "cursor_expired")
	boundaryCurrent, _ := keys.Sign(user.Profile.ID, user.Workspace.ID, newGeneration, 1)
	call("GET", root+"/sync/changes?cursor="+url.QueryEscape(boundaryCurrent), nil, "", user.Credential, 200, "")
	// Snapshot payload deletion and retention advance roll back together on failure.
	expire(refreshed.ID)
	if _, e = db.Exec(ctx, `UPDATE sync_groups SET created_at=statement_timestamp()-interval '100 days' WHERE workspace_id=$1 AND sequence=2`, user.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(ctx, `CREATE FUNCTION retention_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic retention failure'; END $$; CREATE TRIGGER retention_fault BEFORE DELETE ON sync_groups FOR EACH ROW EXECUTE FUNCTION retention_fault()`); e != nil {
		t.Fatal(e)
	}
	if _, e = commands.CleanupSync(ctx, db, 100); e == nil {
		t.Fatal("retention fault accepted")
	}
	var stillLive bool
	db.QueryRow(ctx, `SELECT payload_cleared_at IS NULL FROM sync_snapshots WHERE id=$1`, refreshed.ID).Scan(&stillLive)
	db.QueryRow(ctx, `SELECT count(*) FROM sync_snapshot_items WHERE snapshot_id=$1`, refreshed.ID).Scan(&items)
	db.QueryRow(ctx, `SELECT min_available_sequence FROM sync_heads WHERE workspace_id=$1`, user.Workspace.ID).Scan(&minimum)
	if !stillLive || items != refreshed.Count || minimum != 2 {
		t.Fatal("cleanup left partial state")
	}
	if _, e = db.Exec(ctx, `DROP TRIGGER retention_fault ON sync_groups; DROP FUNCTION retention_fault()`); e != nil {
		t.Fatal(e)
	}
	cleaned, e = commands.CleanupSync(ctx, db, 100)
	if e != nil || cleaned.Groups != 2 || cleaned.Snapshots != 1 {
		t.Fatal("cleanup retry", cleaned, e)
	}
	db.QueryRow(ctx, `SELECT min_available_sequence FROM sync_heads WHERE workspace_id=$1`, user.Workspace.ID).Scan(&minimum)
	if minimum != 4 {
		t.Fatal("empty journal boundary")
	}
	var survivingReceipts int
	db.QueryRow(ctx, `SELECT count(*) FROM actions WHERE workspace_id=$1`, user.Workspace.ID).Scan(&survivingReceipts)
	if survivingReceipts != receiptCount {
		t.Fatal("retention erased receipts")
	}
	create(refreshed.ID, 410, "snapshot_expired")
	page(refreshed, refreshed.First, 410, "snapshot_expired")
	// Budget overflow is a complete failure, never a truncated successful snapshot.
	expire(recovered.ID)
	if _, e = db.Exec(ctx, `INSERT INTO tags(id,workspace_id,name,name_normalized) SELECT gen_random_uuid(),$1,'Budget '||n,'budget '||n FROM generate_series(1,50001)n`, user.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	countID := id()
	create(countID, 422, "snapshot_too_large")
	db.QueryRow(ctx, `SELECT count(*) FROM sync_snapshots WHERE id=$1`, countID).Scan(&faultMarkers)
	if faultMarkers != 0 {
		t.Fatal("count overflow marker")
	}
	if _, e = db.Exec(ctx, `DELETE FROM tags WHERE workspace_id=$1 AND name_normalized LIKE 'budget %'`, user.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(ctx, `WITH inserted AS (INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,note) SELECT gen_random_uuid(),$1,'income','posted',statement_timestamp(),'UTC',repeat('😀',2000) FROM generate_series(1,8000) RETURNING id,workspace_id), movements AS (INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) SELECT gen_random_uuid(),workspace_id,id,$2,1 FROM inserted) INSERT INTO allocations(id,workspace_id,transaction_id,amount_minor) SELECT gen_random_uuid(),workspace_id,id,1 FROM inserted`, user.Workspace.ID, accountID); e != nil {
		t.Fatal(e)
	}
	// Bulk SQL fixtures need fresh planner statistics before the size acceptance.
	if _, e = db.Exec(ctx, `ANALYZE tags; ANALYZE transactions; ANALYZE entries; ANALYZE allocations`); e != nil {
		t.Fatal(e)
	}
	bytesID := id()
	create(bytesID, 422, "snapshot_too_large")
	db.QueryRow(ctx, `SELECT count(*) FROM sync_snapshots WHERE id=$1`, bytesID).Scan(&faultMarkers)
	if faultMarkers != 0 {
		t.Fatal("byte overflow marker")
	}

	if artifact := os.Getenv("SNAPSHOT_RESPONSE_ARTIFACT"); artifact != "" {
		if e = os.MkdirAll(filepath.Dir(artifact), 0700); e != nil {
			t.Fatal(e)
		}
		raw, e := json.Marshal(captured)
		if e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(artifact, raw, 0600); e != nil {
			t.Fatal(e)
		}
	}
}
