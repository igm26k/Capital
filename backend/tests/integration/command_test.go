package integration

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/platform/httpapi"
	commands "accounting/backend/internal/sync"
	"accounting/backend/migrate"
	"accounting/backend/migrations"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestCommand(t *testing.T) {
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
	cfg.MaxConns = 8
	admin, e := pgxpool.NewWithConfig(ctx, cfg)
	if e != nil {
		t.Fatal(e)
	}
	defer admin.Close()
	schema := fmt.Sprintf("command_test_%d", time.Now().UnixNano())
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
	a, e := auth.Register(ctx, "writer@example.test", "literal writer password", "one", "UTC")
	if e != nil {
		t.Fatal(e)
	}
	b, e := auth.Register(ctx, "reader@example.test", "literal reader password", "two", "UTC")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(ctx, `INSERT INTO memberships(workspace_id,user_id,role) VALUES ($1,$2,'member')`, a.Workspace.ID, b.Profile.ID); e != nil {
		t.Fatal(e)
	}
	runner := &commands.Runner{Identity: auth}
	counter := 0
	request := func() commands.Request {
		counter++
		id := fmt.Sprintf("a0000000-0000-4000-8000-%012d", counter)
		return commands.Request{Credential: a.Credential, WorkspaceID: a.Workspace.ID, ActionID: id, GenerationID: a.Workspace.Generation, Method: "POST", Path: "/api/v1/workspaces/" + a.Workspace.ID + "/tags", Body: []byte(`{"name":"probe"}`), RequestID: "f0000000-0000-4000-8000-000000000001"}
	}
	command := func(req commands.Request) commands.Command {
		return func(ctx context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
			name := "probe " + req.ActionID
			var created, updated time.Time
			e := tx.QueryRow(ctx, `INSERT INTO tags(id,workspace_id,name,name_normalized) VALUES ($1,$2,$3,$3) RETURNING created_at,updated_at`, req.ActionID, req.WorkspaceID, name).Scan(&created, &updated)
			if e != nil {
				return commands.Mutation{}, e
			}
			payload, _ := json.Marshal(map[string]any{"id": req.ActionID, "workspace_id": req.WorkspaceID, "version": "1", "name": name, "created_at": created.UTC(), "updated_at": updated.UTC(), "deleted_at": nil, "archived_at": nil})
			return commands.Mutation{Status: 201, Changes: []commands.Change{{EntityType: "tag", ID: req.ActionID, Version: "1", Operation: "upsert", Data: payload}}}, nil
		}
	}
	expectError := func(e error, status int, code string) {
		t.Helper()
		var domain *identity.Error
		if !errors.As(e, &domain) || domain.Status != status || domain.Code != code {
			t.Fatalf("expected %d %s, got %v", status, code, e)
		}
	}
	sameJSON := func(x, y []byte) bool {
		var a, b any
		return json.Unmarshal(x, &a) == nil && json.Unmarshal(y, &b) == nil && reflect.DeepEqual(a, b)
	}
	counts := func() (int64, int, int, int) {
		t.Helper()
		var head int64
		var tags, actions, groups int
		if e := db.QueryRow(ctx, `SELECT last_sequence,(SELECT count(*) FROM tags WHERE workspace_id=$1),(SELECT count(*) FROM actions WHERE workspace_id=$1),(SELECT count(*) FROM sync_groups WHERE workspace_id=$1) FROM sync_heads WHERE workspace_id=$1`, a.Workspace.ID).Scan(&head, &tags, &actions, &groups); e != nil {
			t.Fatal(e)
		}
		return head, tags, actions, groups
	}
	initial := request()
	first, e := runner.Execute(ctx, initial, command(initial))
	if e != nil || first.Status != 201 || first.Replayed {
		t.Fatal("initial command failed", e)
	}
	repeat := initial
	repeat.Body = []byte(`{ "name" : "probe" }`)
	replay, e := runner.Execute(ctx, repeat, func(context.Context, pgx.Tx, identity.Principal) (commands.Mutation, error) {
		t.Error("replay executed callback")
		return commands.Mutation{}, errors.New("unexpected callback")
	})
	if e != nil || !replay.Replayed || !sameJSON(first.Body, replay.Body) {
		t.Fatal("replay changed response", e)
	}
	different := initial
	different.Body = []byte(`{"name":"changed"}`)
	_, e = runner.Execute(ctx, different, command(different))
	expectError(e, 409, "idempotency_conflict")
	if h, tags, actions, groups := counts(); h != 1 || tags != 1 || actions != 1 || groups != 1 {
		t.Fatal("repeat created records")
	}
	outcome, e := runner.Outcome(ctx, a.Credential, a.Workspace.ID, initial.ActionID)
	if e != nil || outcome.State != "applied" || outcome.Sequence == nil || *outcome.Sequence != "1" {
		t.Fatal("missing outcome", e)
	}
	_, e = runner.Outcome(ctx, b.Credential, a.Workspace.ID, initial.ActionID)
	expectError(e, 404, "not_found")
	var refs []map[string]any
	if e = json.Unmarshal(outcome.Entities, &refs); e != nil || len(refs) != 1 || len(refs[0]) != 3 {
		t.Fatal("receipt entities differ from contract")
	}
	// Terminal rejection rolls back the domain savepoint but commits its receipt.
	rejected := request()
	rejectionCommand := func(ctx context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
		if _, e := command(rejected)(ctx, tx, p); e != nil {
			return commands.Mutation{}, e
		}
		return commands.Mutation{}, &commands.Rejection{Status: 409, Code: "version_conflict", Message: "Версия изменена."}
	}
	rejectResult, e := runner.Execute(ctx, rejected, rejectionCommand)
	if e != nil || rejectResult.Status != 409 {
		t.Fatal("rejection not persisted", e)
	}
	rejectReplay, e := runner.Execute(ctx, rejected, command(rejected))
	if e != nil || !rejectReplay.Replayed || !sameJSON(rejectResult.Body, rejectReplay.Body) {
		t.Fatal("rejected action executed again", e)
	}
	if h, tags, actions, groups := counts(); h != 1 || tags != 1 || actions != 2 || groups != 1 {
		t.Fatal("rejection changed domain or head")
	}
	// A nonterminal failure aborts all writes and leaves the key available for retry.
	failed := request()
	_, e = runner.Execute(ctx, failed, func(ctx context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
		if _, e := command(failed)(ctx, tx, p); e != nil {
			return commands.Mutation{}, e
		}
		return commands.Mutation{}, errors.New("internal probe failure")
	})
	expectError(e, 503, "service_unavailable")
	recovered, e := runner.Execute(ctx, failed, command(failed))
	if e != nil || recovered.Replayed {
		t.Fatal("aborted action cannot retry", e)
	}
	// More than 200 changes rolls back even the callback's already-written record.
	oversized := request()
	large, e := runner.Execute(ctx, oversized, func(ctx context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
		m, e := command(oversized)(ctx, tx, p)
		if e != nil {
			return m, e
		}
		m.Changes = make([]commands.Change, 201)
		return m, nil
	})
	if e != nil || large.Status != 422 {
		t.Fatal("group count budget not enforced", e)
	}

	// Fault-injected internal payload exceeds the serialized byte budget and is never published.
	byteBudget := request()
	tooLarge, e := runner.Execute(ctx, byteBudget, func(c context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
		m, e := command(byteBudget)(c, tx, p)
		if e != nil {
			return m, e
		}
		var base map[string]any
		_ = json.Unmarshal(m.Changes[0].Data, &base)
		base["probe_padding"] = strings.Repeat("x", 16384)
		m.Changes = nil
		for i := 0; i < 100; i++ {
			id := fmt.Sprintf("b0000000-0000-4000-8000-%012d", i+1)
			base["id"] = id
			payload, _ := json.Marshal(base)
			m.Changes = append(m.Changes, commands.Change{EntityType: "tag", ID: id, Version: "1", Operation: "upsert", Data: payload})
		}
		return m, nil
	})
	if e != nil || tooLarge.Status != 422 {
		t.Fatal("serialized byte budget not enforced", e)
	}
	var persisted int
	if e = db.QueryRow(ctx, `SELECT count(*) FROM tags WHERE id=$1`, byteBudget.ActionID).Scan(&persisted); e != nil || persisted != 0 {
		t.Fatal("byte rejection leaked domain write")
	}
	// Two identical concurrent calls run the callback only once.
	duplicate := request()
	var calls atomic.Int32
	var wg sync.WaitGroup
	results := make(chan commands.Result, 2)
	errs := make(chan error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, e := runner.Execute(ctx, duplicate, func(c context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
				calls.Add(1)
				return command(duplicate)(c, tx, p)
			})
			results <- r
			errs <- e
		}()
	}
	wg.Wait()
	close(results)
	close(errs)
	for e := range errs {
		if e != nil {
			t.Fatal(e)
		}
	}
	replayed := 0
	for r := range results {
		if r.Replayed {
			replayed++
		}
	}
	if calls.Load() != 1 || replayed != 1 {
		t.Fatal("concurrent replay duplicated callback")
	}
	// Real head lock: lookup can see 404 before commit, and B waits until A aborts.
	blocking, following := request(), request()
	entered, release := make(chan struct{}), make(chan struct{})
	var releaseOnce sync.Once
	unlock := func() { releaseOnce.Do(func() { close(release) }) }
	defer unlock()
	doneA, doneB := make(chan error, 1), make(chan error, 1)
	before, _, _, _ := counts()
	go func() {
		_, e := runner.Execute(ctx, blocking, func(c context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
			if _, e := command(blocking)(c, tx, p); e != nil {
				return commands.Mutation{}, e
			}
			close(entered)
			select {
			case <-release:
			case <-c.Done():
			}
			return commands.Mutation{}, errors.New("abort first writer")
		})
		doneA <- e
	}()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("first writer failed to enter")
	}
	_, e = runner.Outcome(ctx, a.Credential, a.Workspace.ID, blocking.ActionID)
	expectError(e, 404, "not_found")
	go func() { _, e := runner.Execute(ctx, following, command(following)); doneB <- e }()
	waiting := false
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		var count int
		if e = db.QueryRow(ctx, `SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT generation_id::text,last_sequence%'`).Scan(&count); e != nil {
			t.Fatal(e)
		}
		if count > 0 {
			waiting = true
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !waiting {
		t.Fatal("second writer did not wait on PostgreSQL head lock")
	}
	if head, _, _, _ := counts(); head != before {
		t.Fatal("writer committed before first released head")
	}
	unlock()
	expectError(<-doneA, 503, "service_unavailable")
	if e = <-doneB; e != nil {
		t.Fatal(e)
	}
	if head, _, _, _ := counts(); head != before+1 {
		t.Fatal("rollback left sequence gap")
	}

	// Test-only HTTP route exercises the generic adapter; product financial routes remain absent.
	httpRequest := request()
	var httpCalls atomic.Int32
	mux := http.NewServeMux()
	httpapi.MountActions(mux, runner)
	mux.HandleFunc("POST /api/v1/workspaces/{workspace_id}/tags", httpapi.MutationHandler(runner, httpapi.AuthOptions{Origin: "https://command.example.test"}, func(body []byte) error { return nil }, func(context.Context, []byte) commands.Command {
		return func(c context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
			httpCalls.Add(1)
			return command(httpRequest)(c, tx, p)
		}
	}))
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Request-ID", httpRequest.RequestID)
		mux.ServeHTTP(w, r)
	}))
	defer server.Close()
	send := func(method, path, credential, cookie, csrf, origin string, status int, replayed string) {
		t.Helper()
		var body []byte
		if method == "POST" {
			body = httpRequest.Body
		}
		req, e := http.NewRequest(method, server.URL+path, bytes.NewReader(body))
		if e != nil {
			t.Fatal(e)
		}
		if credential != "" {
			req.Header.Set("Authorization", "Bearer "+credential)
		}
		if cookie != "" {
			req.Header.Set("Cookie", "__Host-accounting_session="+cookie)
		}
		if csrf != "" {
			req.Header.Set("X-CSRF-Token", csrf)
		}
		if origin != "" {
			req.Header.Set("Origin", origin)
		}
		req.Header.Set("Idempotency-Key", httpRequest.ActionID)
		req.Header.Set("X-Sync-Generation", httpRequest.GenerationID)
		req.Header.Set("Content-Type", "application/json")
		response, e := server.Client().Do(req)
		if e != nil {
			t.Fatal(e)
		}
		defer response.Body.Close()
		payload, e := io.ReadAll(response.Body)
		if e != nil {
			t.Fatal(e)
		}
		if response.StatusCode != status {
			t.Fatalf("HTTP adapter status %d expected %d", response.StatusCode, status)
		}
		if replayed != "" && response.Header.Get("Idempotency-Replayed") != replayed {
			t.Fatal("replay header mismatch")
		}
		if !json.Valid(payload) {
			t.Fatal("adapter returned invalid JSON")
		}
	}
	send("POST", httpRequest.Path, a.Credential, "", "", "", 201, "false")
	send("POST", httpRequest.Path, a.Credential, "", "", "", 201, "true")
	send("POST", httpRequest.Path, "", a.Credential, "", "https://command.example.test", 403, "")
	send("POST", httpRequest.Path, "", a.Credential, identity.CSRF(a.Credential), "https://foreign.example.test", 403, "")
	send("POST", httpRequest.Path, "", a.Credential, identity.CSRF(a.Credential), "https://command.example.test", 201, "true")
	if httpCalls.Load() != 1 {
		t.Fatal("HTTP replay or rejected CSRF executed callback")
	}
	actionPath := "/api/v1/workspaces/" + a.Workspace.ID + "/actions/" + httpRequest.ActionID
	send("GET", actionPath, a.Credential, "", "", "", 200, "")
	send("GET", actionPath, b.Credential, "", "", "", 404, "")
	// A real PostgreSQL deadlock forces one transaction to retry without partial receipts.
	retryA, retryB := request(), request()
	retryB.Credential = b.Credential
	retryB.WorkspaceID = b.Workspace.ID
	retryB.GenerationID = b.Workspace.Generation
	retryB.Path = "/api/v1/workspaces/" + b.Workspace.ID + "/tags"
	readyA, readyB := make(chan struct{}), make(chan struct{})
	var attemptsA, attemptsB atomic.Int32
	deadlockCommand := func(req commands.Request, first, second int64, ready, other chan struct{}, attempts *atomic.Int32) commands.Command {
		return func(c context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
			attempt := attempts.Add(1)
			if _, e := tx.Exec(c, "SELECT pg_advisory_xact_lock($1)", first); e != nil {
				return commands.Mutation{}, e
			}
			if attempt == 1 {
				close(ready)
				select {
				case <-other:
				case <-time.After(2 * time.Second):
					return commands.Mutation{}, errors.New("deadlock barrier timeout")
				}
			}
			if _, e := tx.Exec(c, "SELECT pg_advisory_xact_lock($1)", second); e != nil {
				return commands.Mutation{}, e
			}
			return command(req)(c, tx, p)
		}
	}
	retryResults := make(chan error, 2)
	go func() {
		_, e := runner.Execute(ctx, retryA, deadlockCommand(retryA, 809101, 809102, readyA, readyB, &attemptsA))
		retryResults <- e
	}()
	go func() {
		_, e := runner.Execute(ctx, retryB, deadlockCommand(retryB, 809102, 809101, readyB, readyA, &attemptsB))
		retryResults <- e
	}()
	for i := 0; i < 2; i++ {
		if e := <-retryResults; e != nil {
			t.Fatal("deadlock retry failed", e)
		}
	}
	if attemptsA.Load()+attemptsB.Load() < 3 || attemptsA.Load() > 3 || attemptsB.Load() > 3 {
		t.Fatal("deadlock did not use bounded retry")
	}
	for _, req := range []commands.Request{retryA, retryB} {
		if _, e := runner.Outcome(ctx, req.Credential, req.WorkspaceID, req.ActionID); e != nil {
			t.Fatal("retry receipt missing", e)
		}
	}

	headBefore, _, _, _ := counts()
	overflow := request()
	if _, e = db.Exec(ctx, `UPDATE sync_heads SET last_sequence=9223372036854775807 WHERE workspace_id=$1`, a.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	_, e = runner.Execute(ctx, overflow, func(context.Context, pgx.Tx, identity.Principal) (commands.Mutation, error) {
		t.Error("sequence overflow executed command")
		return commands.Mutation{}, nil
	})
	expectError(e, 503, "service_unavailable")
	if _, e = db.Exec(ctx, `UPDATE sync_heads SET last_sequence=$1 WHERE workspace_id=$2`, headBefore, a.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	// Deadline applies even before a cleanup worker physically clears response_body.
	if _, e = db.Exec(ctx, `UPDATE actions SET completed_at=now()-interval '744 hours',response_expires_at=now()-interval '24 hours' WHERE workspace_id=$1 AND actor_user_id=$2 AND action_id=$3`, a.Workspace.ID, a.Profile.ID, initial.ActionID); e != nil {
		t.Fatal(e)
	}
	_, e = runner.Execute(ctx, initial, command(initial))
	expectError(e, 409, "action_result_expired")
	expired, e := runner.Outcome(ctx, a.Credential, a.Workspace.ID, initial.ActionID)
	if e != nil || expired.State != "applied" || len(expired.Body) != 0 {
		t.Fatal("expired compact outcome unavailable", e)
	}

	compacted, e := commands.CompactReceipts(ctx, db, 1000)
	if e != nil || compacted != 1 {
		t.Fatal("expired response compaction failed", e)
	}
	var compactBody []byte
	var metadata int
	if e = db.QueryRow(ctx, `SELECT response_body,octet_length(canonical_request_hash) FROM actions WHERE workspace_id=$1 AND actor_user_id=$2 AND action_id=$3`, a.Workspace.ID, a.Profile.ID, initial.ActionID).Scan(&compactBody, &metadata); e != nil || len(compactBody) != 0 || metadata != 32 {
		t.Fatal("compaction damaged durable receipt")
	}
	_, e = runner.Execute(ctx, initial, command(initial))
	expectError(e, 409, "action_result_expired")
	// Rotation keeps old receipts, but rejects a new action from an old outbox generation.
	generation := "c0000000-0000-4000-8000-000000000001"
	tx, e := db.Begin(ctx)
	if e != nil {
		t.Fatal(e)
	}
	for _, sql := range []string{`DELETE FROM sync_groups WHERE workspace_id='` + a.Workspace.ID + `'`, `UPDATE workspaces SET sync_generation_id='` + generation + `' WHERE id='` + a.Workspace.ID + `'`, `UPDATE sync_heads SET generation_id='` + generation + `',last_sequence=0,min_available_sequence=1 WHERE workspace_id='` + a.Workspace.ID + `'`} {
		if _, e = tx.Exec(ctx, sql); e != nil {
			_ = tx.Rollback(ctx)
			t.Fatal(e)
		}
	}
	if e = tx.Commit(ctx); e != nil {
		t.Fatal(e)
	}
	oldReplay, e := runner.Execute(ctx, duplicate, command(duplicate))
	if e != nil || !oldReplay.Replayed {
		t.Fatal("old-generation receipt not replayed", e)
	}
	stale := request()
	_, e = runner.Execute(ctx, stale, command(stale))
	expectError(e, 409, "sync_generation_conflict")
	if head, _, _, groups := counts(); head != 0 || groups != 0 {
		t.Fatal("stale generation published journal")
	}
	if _, e = db.Exec(ctx, `UPDATE memberships SET revoked_at=now() WHERE workspace_id=$1 AND user_id=$2`, a.Workspace.ID, a.Profile.ID); e != nil {
		t.Fatal(e)
	}
	_, e = runner.Execute(ctx, duplicate, command(duplicate))
	expectError(e, 404, "not_found")
	_, e = runner.Outcome(ctx, a.Credential, a.Workspace.ID, duplicate.ActionID)
	expectError(e, 404, "not_found")
	if strings.Contains(string(expired.Entities), a.Credential) {
		t.Fatal("receipt contains credential")
	}
}
