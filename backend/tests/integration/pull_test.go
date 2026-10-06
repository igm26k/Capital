package integration

import (
	"accounting/backend/internal/identity"
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
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestPull(t *testing.T) {
	databaseURL := os.Getenv("TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
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
	schema := fmt.Sprintf("pull_%d", time.Now().UnixNano())
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
	user, e := auth.Register(ctx, "pull@example.test", "synthetic transfer password", "advanced", "UTC")
	if e != nil {
		t.Fatal(e)
	}
	other, e := auth.Register(ctx, "other-pull@example.test", "synthetic other password", "other", "UTC")
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

	var counter int
	id := func() string { counter++; return fmt.Sprintf("d1000000-0000-4000-8000-%012d", counter) }
	root := "/api/v1/workspaces/" + user.Workspace.ID
	type observed struct {
		Schema string          `json:"schema"`
		Body   json.RawMessage `json:"body"`
	}
	captured := []observed{}
	call := func(method, path string, body any, token string, want int, code string) json.RawMessage {
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
			req.Header.Set("Idempotency-Key", id())
			req.Header.Set("X-Sync-Generation", user.Workspace.Generation)
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
			t.Fatalf("%s: status %d wanted %d: %s", path, response.StatusCode, want, data)
		}
		schema := "SyncChanges"
		if want >= 400 {
			schema = "Error"
			var value struct{ Code string }
			json.Unmarshal(data, &value)
			if value.Code != code {
				t.Fatalf("code %s wanted %s", value.Code, code)
			}
		} else if method != "GET" {
			schema = "MutationResult"
		}
		captured = append(captured, observed{schema, data})
		return data
	}
	sign := func(actor, workspace, generation string, seq int64) string {
		s, e := keys.Sign(actor, workspace, generation, seq)
		if e != nil {
			t.Fatal(e)
		}
		return s
	}
	pull := func(cursor string, limit int, want int, code string) commands.PullPage {
		data := call("GET", root+"/sync/changes?cursor="+url.QueryEscape(cursor)+"&limit="+strconv.Itoa(limit), nil, user.Credential, want, code)
		var page commands.PullPage
		if want == 200 {
			if e := json.Unmarshal(data, &page); e != nil {
				t.Fatal(e)
			}
		}
		return page
	}
	zero := sign(user.Profile.ID, user.Workspace.ID, user.Workspace.Generation, 0)
	empty := pull(zero, 50, 200, "")
	if len(empty.Groups) != 0 || empty.Next != zero || empty.More || empty.Head != "0" {
		t.Fatal("empty cursor advanced")
	}
	accountID := id()
	data := call("POST", root+"/accounts", map[string]any{"id": accountID, "name": "Pull account", "type": "bank", "currency": "EUR", "occurred_timezone": "UTC", "opened_at": "2026-01-01T00:00:00Z", "opening_balance_minor": "1000"}, user.Credential, 201, "")
	var mutation struct{ Transactions []json.RawMessage }
	json.Unmarshal(data, &mutation)
	first := pull(zero, 1, 200, "")
	if len(first.Groups) != 1 || len(first.Groups[0].Changes) != 2 || first.Head != "1" || first.More {
		t.Fatal("opening group split")
	}
	stable := pull(zero, 1, 200, "")
	a, _ := json.Marshal(first)
	b, _ := json.Marshal(stable)
	if !bytes.Equal(a, b) {
		t.Fatal("repeat page changed")
	}
	if pull(first.Next, 50, 200, "").Next != first.Next {
		t.Fatal("empty cursor changed")
	}
	pull(zero[:len(zero)-5]+"aaaaa", 50, 400, "cursor_invalid")
	pull(sign(other.Profile.ID, user.Workspace.ID, user.Workspace.Generation, 0), 50, 400, "cursor_invalid")
	pull(sign(user.Profile.ID, other.Workspace.ID, user.Workspace.Generation, 0), 50, 400, "cursor_invalid")
	pull(sign(user.Profile.ID, user.Workspace.ID, user.Workspace.Generation, 2), 50, 400, "cursor_invalid")
	pull(sign(user.Profile.ID, user.Workspace.ID, other.Workspace.Generation, 0), 50, 410, "cursor_expired")
	call("GET", root+"/sync/changes?cursor=bad", nil, "", 401, "unauthenticated")
	call("GET", "/api/v1/workspaces/"+other.Workspace.ID+"/sync/changes?cursor=bad", nil, user.Credential, 404, "not_found")
	pull(zero, 0, 400, "bad_request")
	call("GET", root+"/sync/changes?cursor="+zero+"&cursor="+zero, nil, user.Credential, 400, "bad_request")
	// A writer holds the head and has uncommitted data. Pull must return promptly
	// from its old snapshot; no group/ordinal may leak before commit.
	writer, e := db.Begin(ctx)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = writer.Exec(ctx, `SELECT last_sequence FROM sync_heads WHERE workspace_id=$1 FOR UPDATE`, user.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	if _, e = writer.Exec(ctx, `UPDATE sync_heads SET last_sequence=2 WHERE workspace_id=$1`, user.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	before := pull(first.Next, 50, 200, "")
	if before.Head != "1" || len(before.Groups) != 0 {
		t.Fatal("uncommitted head leaked")
	}
	writer.Rollback(ctx)
	// Journal-only fault fixture: a missing ordinal must fail the whole page.
	tx, e := db.Begin(ctx)
	if e != nil {
		t.Fatal(e)
	}
	// Commit corruption temporarily to exercise production detection, then restore.
	var removed []byte
	var changeID, entityType, entityID, operation string
	var version int64
	var created time.Time
	e = tx.QueryRow(ctx, `DELETE FROM sync_changes WHERE workspace_id=$1 AND sequence=1 AND ordinal=2 RETURNING change_id::text,entity_type,entity_id::text,entity_version,operation,payload,created_at`, user.Workspace.ID).Scan(&changeID, &entityType, &entityID, &version, &operation, &removed, &created)
	if e != nil {
		t.Fatal(e)
	}
	if e = tx.Commit(ctx); e != nil {
		t.Fatal(e)
	}
	pull(zero, 50, 503, "service_unavailable")
	_, e = db.Exec(ctx, `INSERT INTO sync_changes(workspace_id,generation_id,sequence,ordinal,change_id,entity_type,entity_id,entity_version,operation,payload,created_at) VALUES($1,$2,1,2,$3,$4,$5,$6,$7,$8,$9)`, user.Workspace.ID, user.Workspace.Generation, changeID, entityType, entityID, version, operation, removed, created)
	if e != nil {
		t.Fatal(e)
	}
	// Create genuine bounded journal groups through Runner. Payloads are synthetic
	// valid transaction aggregates; this fixture isolates pagination byte boundaries.
	runner := commands.Runner{Identity: auth}
	for group := 0; group < 5; group++ {
		changes := []commands.Change{}
		for item := 0; item < 110; item++ {
			var payload map[string]any
			json.Unmarshal(mutation.Transactions[0], &payload)
			transactionID := id()
			payload["id"] = transactionID
			payload["note"] = strings.Repeat("😀", 2000)
			encoded, _ := json.Marshal(payload)
			changes = append(changes, commands.Change{EntityType: "transaction", ID: transactionID, Version: "1", Operation: "upsert", Data: encoded})
		}
		action := id()
		_, e = runner.Execute(ctx, commands.Request{Credential: user.Credential, WorkspaceID: user.Workspace.ID, GenerationID: user.Workspace.Generation, ActionID: action, RequestID: id(), Method: "POST", Path: root + "/transactions", Body: []byte(`{"fixture":true}`)}, func(context.Context, pgx.Tx, identity.Principal) (commands.Mutation, error) {
			return commands.Mutation{Status: 201, Changes: changes}, nil
		})
		if e != nil {
			t.Fatal(e)
		}
	}
	page := pull(first.Next, 100, 200, "")
	encoded, _ := json.Marshal(page)
	if len(encoded)+1 > commands.MaxPullBytes || !page.More || len(page.Groups) < 1 || len(page.Groups) >= 5 {
		t.Fatal("byte boundary failed")
	}
	for i, g := range page.Groups {
		if g.Sequence != strconv.Itoa(i+2) || len(g.Changes) != 110 {
			t.Fatal("group boundary failed")
		}
	}
	last, e := strconv.Atoi(page.Groups[len(page.Groups)-1].Sequence)
	if e != nil {
		t.Fatal(e)
	}
	next := pull(page.Next, 100, 200, "")
	if next.Groups[0].Sequence != strconv.Itoa(last+1) || next.More {
		t.Fatal("next cursor jumped to head")
	}
	// Retention cutoff is inclusive at min-1; no partial group can remain.
	_, e = db.Exec(ctx, `DELETE FROM sync_groups WHERE workspace_id=$1 AND sequence<5`, user.Workspace.ID)
	if e != nil {
		t.Fatal(e)
	}
	_, e = db.Exec(ctx, `UPDATE sync_heads SET min_available_sequence=5 WHERE workspace_id=$1`, user.Workspace.ID)
	if e != nil {
		t.Fatal(e)
	}
	pull(sign(user.Profile.ID, user.Workspace.ID, user.Workspace.Generation, 3), 1, 410, "cursor_expired")
	boundary := pull(sign(user.Profile.ID, user.Workspace.ID, user.Workspace.Generation, 4), 1, 200, "")
	if boundary.Groups[0].Sequence != "5" || boundary.Minimum != "5" || boundary.Head != "6" || !boundary.More {
		t.Fatal("retention boundary")
	}
	// DR-S01: actor legitimately belongs to both workspaces, but cursor is scoped.
	if _, e = db.Exec(ctx, `INSERT INTO memberships(workspace_id,user_id,role) VALUES($1,$2,'member')`, other.Workspace.ID, user.Profile.ID); e != nil {
		t.Fatal(e)
	}
	call("GET", "/api/v1/workspaces/"+other.Workspace.ID+"/sync/changes?cursor="+zero, nil, user.Credential, 400, "cursor_invalid")
	// Prove repeatable-read consistency across an actual concurrent commit. The
	// second actor avoids activity-update contention on the reader's session.
	if _, e = db.Exec(ctx, `INSERT INTO memberships(workspace_id,user_id,role) VALUES($1,$2,'member')`, user.Workspace.ID, other.Profile.ID); e != nil {
		t.Fatal(e)
	}
	e = auth.WithSessionIsolation(ctx, user.Credential, pgx.RepeatableRead, func(tx pgx.Tx, p identity.Principal) error {
		if e := identity.RequireMembership(ctx, tx, p.Profile.ID, user.Workspace.ID); e != nil {
			return e
		}
		var oldHead int64
		if e := tx.QueryRow(ctx, `SELECT last_sequence FROM sync_heads WHERE workspace_id=$1`, user.Workspace.ID).Scan(&oldHead); e != nil {
			return e
		}
		done := make(chan error, 1)
		action := id()
		requestID := id()
		go func() {
			_, err := runner.Execute(ctx, commands.Request{Credential: other.Credential, WorkspaceID: user.Workspace.ID, GenerationID: user.Workspace.Generation, ActionID: action, RequestID: requestID, Method: "POST", Path: root + "/transactions", Body: []byte(`{"fixture":"concurrent"}`)}, func(context.Context, pgx.Tx, identity.Principal) (commands.Mutation, error) {
				return commands.Mutation{Status: 201, Changes: []commands.Change{{EntityType: "transaction", ID: mutationID(mutation.Transactions[0]), Version: "1", Operation: "upsert", Data: mutation.Transactions[0]}}}, nil
			})
			done <- err
		}()
		select {
		case err := <-done:
			if err != nil {
				return err
			}
		case <-ctx.Done():
			return ctx.Err()
		}
		var sameHead int64
		if e := tx.QueryRow(ctx, `SELECT last_sequence FROM sync_heads WHERE workspace_id=$1`, user.Workspace.ID).Scan(&sameHead); e != nil {
			return e
		}
		if sameHead != oldHead {
			return fmt.Errorf("snapshot head changed")
		}
		var visible int
		if e := tx.QueryRow(ctx, `SELECT count(*) FROM sync_groups WHERE workspace_id=$1 AND sequence>$2`, user.Workspace.ID, oldHead).Scan(&visible); e != nil {
			return e
		}
		if visible != 0 {
			return fmt.Errorf("new group leaked into snapshot")
		}
		return nil
	})
	if e != nil {
		t.Fatal(e)
	}
	following := pull(sign(user.Profile.ID, user.Workspace.ID, user.Workspace.Generation, 6), 50, 200, "")
	if following.Head != "7" || len(following.Groups) != 1 || following.Groups[0].Sequence != "7" {
		t.Fatal("foreign action missing after commit")
	}
	// Generation reset cannot reuse a valid old cursor, even at the same sequence.
	newGeneration := id()
	reset, e := db.Begin(ctx)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = reset.Exec(ctx, `DELETE FROM sync_groups WHERE workspace_id=$1`, user.Workspace.ID); e != nil {
		t.Fatal(e)
	}
	if _, e = reset.Exec(ctx, `UPDATE workspaces SET sync_generation_id=$2 WHERE id=$1`, user.Workspace.ID, newGeneration); e != nil {
		t.Fatal(e)
	}
	if _, e = reset.Exec(ctx, `UPDATE sync_heads SET generation_id=$2,last_sequence=0,min_available_sequence=1 WHERE workspace_id=$1`, user.Workspace.ID, newGeneration); e != nil {
		t.Fatal(e)
	}
	if e = reset.Commit(ctx); e != nil {
		t.Fatal(e)
	}
	pull(zero, 50, 410, "cursor_expired")
	renewed := sign(user.Profile.ID, user.Workspace.ID, newGeneration, 0)
	if pull(renewed, 50, 200, "").Next != renewed {
		t.Fatal("reset empty cursor")
	}
	// Access is checked before cursor decoding, including revoked membership/session.
	_, e = db.Exec(ctx, `UPDATE memberships SET revoked_at=clock_timestamp() WHERE workspace_id=$1 AND user_id=$2`, user.Workspace.ID, user.Profile.ID)
	if e != nil {
		t.Fatal(e)
	}
	pull("bad", 50, 404, "not_found")
	_, e = db.Exec(ctx, `UPDATE memberships SET revoked_at=NULL WHERE workspace_id=$1 AND user_id=$2`, user.Workspace.ID, user.Profile.ID)
	if e != nil {
		t.Fatal(e)
	}
	_, e = db.Exec(ctx, `UPDATE sessions SET revoked_at=clock_timestamp() WHERE id=$1`, user.Session.ID)
	if e != nil {
		t.Fatal(e)
	}
	pull("bad", 50, 401, "unauthenticated")
	if artifact := os.Getenv("PULL_RESPONSE_ARTIFACT"); artifact != "" {
		if e = os.MkdirAll(filepath.Dir(artifact), 0700); e != nil {
			t.Fatal(e)
		}
		encoded, e := json.Marshal(captured)
		if e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(artifact, encoded, 0600); e != nil {
			t.Fatal(e)
		}
	}
}

func mutationID(raw json.RawMessage) string {
	var v struct{ ID string }
	json.Unmarshal(raw, &v)
	return v.ID
}
