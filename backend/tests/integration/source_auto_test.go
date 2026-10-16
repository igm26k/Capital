package integration

import (
	"accounting/backend/internal/ledger"
	"crypto/sha256"
	"fmt"
	"os"
	"testing"
)

func TestAutomaticSources(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	account := f.account(a)
	auto := func(input map[string]any, status int) map[string]any {
		return f.call(accessRequest{method: "POST", path: root + "/source-events/auto", actor: a, key: f.id(), body: input}, status, "").body
	}
	makeInput := func(account string) map[string]any {
		in := f.sourceEvent(account)
		in["notification_key_hash"] = fmt.Sprintf("%x", sha256.Sum256([]byte(in["id"].(string))))
		return in
	}
	input := makeInput(account)
	before := f.digest()
	event := auto(input, 201)
	if _, exposed := event["notification_key_hash"]; exposed {
		t.Fatal("private slot leaked into public response")
	}
	if event["state"] != "queued" || f.digest() != before {
		t.Fatal("automatic intake must only queue", event)
	}
	repeated := accessClone(input)
	repeated["id"] = f.id()
	repeated["profile_id"] = "replacement"
	if got := auto(repeated, 200); got["id"] != event["id"] || got["state"] != "queued" {
		t.Fatal("replay changed identity")
	}
	// Equal independent purchases require review; they are neither discarded nor merged.
	second := makeInput(account)
	second["merchant"] = "Different merchant"
	if got := auto(second, 201); got["state"] != "review" || got["transaction_id"] != nil {
		t.Fatal("weak identity guessed a merge", got)
	}
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 10); err != nil {
		t.Fatal(err)
	}
	balance := f.call(accessRequest{method: "GET", path: root + "/accounts/" + account, actor: a}, 200, "").body
	if balance["pending_delta_minor"] != "-100" || balance["posted_balance_minor"] != "10000" {
		t.Fatal("automatic pending counted twice", balance)
	}
	// An existing review is never promoted just by changing delivery mode.
	manual := makeInput(f.account(a))
	f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: manual}, 201, "")
	if got := auto(manual, 200); got["state"] != "review" {
		t.Fatal("automatic replay overrode a manual review")
	}
	legacy := f.sourceEvent(f.account(a))
	if got := auto(legacy, 201); got["state"] != "review" {
		t.Fatal("legacy source without slot auto-created money")
	}
	updated := makeInput(account)
	updated["notification_key_hash"] = input["notification_key_hash"]
	updated["observed_at"] = "2026-10-03T12:00:00Z"
	updated["amount_minor"] = "300"
	updated["merchant"] = "Changed bank description"
	if got := auto(updated, 201); got["state"] != "review" {
		t.Fatal("updated notification slot created a second charge")
	}
	blank := makeInput(f.account(a))
	blank["merchant"] = ""
	if got := auto(blank, 201); got["state"] != "review" {
		t.Fatal("empty merchant auto-created money")
	}
	// A posted purchase or either transfer leg is only a candidate, never an auto attachment.
	existingAccount := f.account(a)
	target := f.account(a)
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: f.transfer(existingAccount, target)}, 201, "")
	for _, pair := range []struct{ account, kind string }{{existingAccount, "expense"}, {target, "income"}} {
		candidate := makeInput(pair.account)
		candidate["kind"] = pair.kind
		if got := auto(candidate, 201); got["state"] != "review" {
			t.Fatal("transfer direction was overlooked")
		}
	}
	deletedAccount := f.account(a)
	purchase := f.basic(deletedAccount, "expense")
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: purchase}, 201, "")
	f.call(accessRequest{method: "DELETE", path: root + "/transactions/" + purchase["id"].(string), actor: a, key: f.id(), body: map[string]any{"expected_version": "1", "related_versions": []any{}}}, 200, "")
	if got := auto(makeInput(deletedAccount), 201); got["state"] != "review" {
		t.Fatal("deleted purchase was revived")
	}
	// In-flight CSV must finish before a notification can be considered safe.
	csvAccount := f.account(a)
	if _, err := f.db.Exec(f.ctx, `INSERT INTO csv_imports(workspace_id,id,actor_user_id,session_id,generation_id,account_id,state,preview,expires_at) VALUES($1,$2,$3,$4,$5,$6,'queued','{}',clock_timestamp()+interval '1 day')`, a.Workspace.ID, f.id(), a.Profile.ID, a.Session.ID, a.Workspace.Generation, csvAccount); err != nil {
		t.Fatal(err)
	}
	if got := auto(makeInput(csvAccount), 201); got["state"] != "review" {
		t.Fatal("in-flight CSV was ignored")
	}
	stopped := auto(makeInput(f.account(a)), 201)
	if stopped["state"] != "queued" {
		t.Fatal("fresh source did not queue")
	}
	f.call(accessRequest{method: "DELETE", path: "/api/v1/sessions/" + a.Session.ID, actor: a}, 200, "")
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 10); err != nil {
		t.Fatal(err)
	}
	var failed, unlinked bool
	if err := f.db.QueryRow(f.ctx, `SELECT state='failed',NOT EXISTS(SELECT 1 FROM transaction_sources l WHERE l.workspace_id=s.workspace_id AND l.event_id=s.id) FROM source_events s WHERE workspace_id=$1 AND id=$2`, a.Workspace.ID, stopped["id"]).Scan(&failed, &unlinked); err != nil || !failed || !unlinked {
		t.Fatal("revoked auto job applied", err)
	}
	if path := os.Getenv("SOURCE_AUTO_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
