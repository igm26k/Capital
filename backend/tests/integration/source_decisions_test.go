package integration

import (
	"accounting/backend/internal/ledger"
	"os"
	"sync"
	"testing"
)

func TestSourceDecisions(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	account := f.account(a)
	admit := func() map[string]any {
		in := f.sourceEvent(account)
		return f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: in}, 201, "").body
	}
	decide := func(event map[string]any, decision string, tx, version any) map[string]any {
		return f.call(accessRequest{method: "POST", path: root + "/source-events/" + event["id"].(string) + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": event["version"], "decision": decision, "transaction_id": tx, "expected_transaction_version": version}}, 200, "").body
	}
	read := func(event map[string]any) map[string]any {
		return f.call(accessRequest{method: "GET", path: root + "/source-events/" + event["id"].(string), actor: a}, 200, "").body
	}
	balances := func(posted, pending, projected string) {
		t.Helper()
		v := f.call(accessRequest{method: "GET", path: root + "/accounts/" + account, actor: a}, 200, "").body
		if v["posted_balance_minor"] != posted || v["pending_delta_minor"] != pending || v["projected_balance_minor"] != projected {
			t.Fatal("wrong balances", v)
		}
	}
	first := admit()
	before := f.digest()
	queued := decide(first, "create_pending", nil, nil)
	if queued["state"] != "queued" || f.digest() != before {
		t.Fatal("confirmation directly changed money")
	}
	// Concurrent workers use the same financial action, transaction and receipt.
	var wait sync.WaitGroup
	failures := make(chan error, 2)
	for i := 0; i < 2; i++ {
		wait.Add(1)
		go func() { defer wait.Done(); _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); failures <- err }()
	}
	wait.Wait()
	close(failures)
	for err := range failures {
		if err != nil {
			t.Fatal(err)
		}
	}
	event := read(first)
	if event["state"] != "applied" {
		t.Fatal(event)
	}
	id := event["transaction_id"].(string)
	pendingTx := f.call(accessRequest{method: "GET", path: root + "/transactions/" + id, actor: a}, 200, "").body
	if pendingTx["status"] != "pending" {
		t.Fatal("source created a posted transaction")
	}
	balances("10000", "-100", "9900")
	var entries, receipts int
	f.db.QueryRow(f.ctx, `SELECT count(*) FROM entries WHERE transaction_id=$1`, id).Scan(&entries)
	f.db.QueryRow(f.ctx, `SELECT count(*) FROM actions WHERE workspace_id=$1 AND entity_refs @> $2::jsonb`, a.Workspace.ID, `[{"entity_type":"transaction","id":"`+id+`"}]`).Scan(&receipts)
	if entries != 1 || receipts != 1 {
		t.Fatal("duplicate financial application", entries, receipts)
	}
	// User edits remain untouched by status confirmation; version conflicts stop the whole job.
	edited := f.basic(account, "expense")
	delete(edited, "id")
	edited["expected_version"] = "1"
	edited["expected_parent_version"] = nil
	edited["note"] = "Owner note"
	edited["payee"] = "Owner merchant"
	f.call(accessRequest{method: "PUT", path: root + "/transactions/" + id, actor: a, key: f.id(), body: edited}, 200, "")
	decide(event, "confirm_posted", id, "1")
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); err != nil {
		t.Fatal(err)
	}
	failed := read(first)
	if failed["state"] != "failed" || failed["error_code"] != "version_conflict" {
		t.Fatal(failed)
	}
	balances("10000", "-100", "9900")
	decide(failed, "confirm_posted", id, "2")
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); err != nil {
		t.Fatal(err)
	}
	confirmed := read(first)
	if confirmed["state"] != "applied" {
		t.Fatal(confirmed)
	}
	balances("9900", "0", "9900")
	tx := f.call(accessRequest{method: "GET", path: root + "/transactions/" + id, actor: a}, 200, "").body
	if tx["note"] != "Owner note" || tx["payee"] != "Owner merchant" || tx["status"] != "posted" {
		t.Fatal("bank confirmation overwrote user fields", tx)
	}
	// A second observation can explicitly attach to the existing transaction without reposting money.
	second := admit()
	before = f.digest()
	linked := decide(second, "link", id, "3")
	if linked["transaction_id"] != id || f.digest() != before {
		t.Fatal("link changed financial data")
	}
	// Canceling a pending observation produces the ordinary transaction tombstone.
	third := admit()
	decide(third, "create_pending", nil, nil)
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); err != nil {
		t.Fatal(err)
	}
	current := read(third)
	cancelledID := current["transaction_id"].(string)
	balances("9900", "-100", "9800")
	decide(current, "cancel_pending", cancelledID, "1")
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); err != nil {
		t.Fatal(err)
	}
	balances("9900", "0", "9900")
	f.call(accessRequest{method: "GET", path: root + "/transactions/" + cancelledID, actor: a}, 404, "not_found")
	// Unknown outcomes cannot be dismissed or silently turned into a new financial action.
	fourth := admit()
	decide(fourth, "create_pending", nil, nil)
	if _, err := f.db.Exec(f.ctx, `UPDATE source_events SET state='failed',error_code='action_result_expired',version=version+1 WHERE workspace_id=$1 AND id=$2`, a.Workspace.ID, fourth["id"]); err != nil {
		t.Fatal(err)
	}
	unknown := read(fourth)
	f.call(accessRequest{method: "POST", path: root + "/source-events/" + unknown["id"].(string) + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": unknown["version"], "decision": "dismiss", "transaction_id": nil, "expected_transaction_version": nil}}, 409, "source_event_not_ready")
	// Revocation stops a queued job. A new, explicit session can retry only proven unapplied work.
	fifth := admit()
	decide(fifth, "create_pending", nil, nil)
	f.call(accessRequest{method: "DELETE", path: "/api/v1/sessions/" + a.Session.ID, actor: a}, 200, "")
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 10); err != nil {
		t.Fatal(err)
	}
	var state string
	if err := f.db.QueryRow(f.ctx, `SELECT state FROM source_events WHERE workspace_id=$1 AND id=$2`, a.Workspace.ID, fifth["id"]).Scan(&state); err != nil || state != "failed" {
		t.Fatal("revoked source job ran", state, err)
	}
	oldSession := a.Session.ID
	var loginErr error
	a, loginErr = f.auth.Login(f.ctx, a.Profile.Email, "synthetic transfer password", "explicit-source-resume")
	if loginErr != nil {
		t.Fatal(loginErr)
	}
	stopped := read(fifth)
	decide(stopped, "create_pending", nil, nil)
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); err != nil {
		t.Fatal(err)
	}
	balances("9900", "-100", "9800")
	var originalIssuer string
	if err := f.db.QueryRow(f.ctx, `SELECT job_history#>>'{0,job,session_id}' FROM source_events WHERE workspace_id=$1 AND id=$2`, a.Workspace.ID, fifth["id"]).Scan(&originalIssuer); err != nil || originalIssuer != oldSession {
		t.Fatal("original issuer evidence lost", originalIssuer, err)
	}
	// An applied receipt blocks a fresh action even if a damaged status claims an ordinary failure.
	if _, err := f.db.Exec(f.ctx, `UPDATE source_events SET job=jsonb_set(job,'{action_id}',(SELECT job->'action_id' FROM source_events x WHERE x.workspace_id=$1 AND x.id=$2)),error_code='unauthenticated' WHERE workspace_id=$1 AND id=$3`, a.Workspace.ID, first["id"], fourth["id"]); err != nil {
		t.Fatal(err)
	}
	damaged := read(fourth)
	f.call(accessRequest{method: "POST", path: root + "/source-events/" + damaged["id"].(string) + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": damaged["version"], "decision": "create_pending", "transaction_id": nil, "expected_transaction_version": nil}}, 409, "source_event_not_ready")
	if path := os.Getenv("SOURCE_DECISION_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
