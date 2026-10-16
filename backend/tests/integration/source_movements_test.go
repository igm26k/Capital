package integration

import (
	"accounting/backend/internal/ledger"
	"os"
	"testing"
)

func TestSourceMovementLinks(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	account := f.account(a)
	target := f.account(a)
	expense := f.basic(account, "expense")
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: expense}, 201, "")
	refund := f.refund(account, expense["id"].(string), expense["allocations"].([]any)[0].(map[string]any)["id"].(string))
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: refund}, 201, "")
	transfer := f.transfer(account, target)
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: transfer}, 201, "")
	for _, pair := range []struct{ account, kind, amount, id string }{{account, "income", "1", refund["id"].(string)}, {account, "expense", "100", transfer["id"].(string)}, {target, "income", "100", transfer["id"].(string)}} {
		input := f.sourceEvent(pair.account)
		input["kind"] = pair.kind
		input["amount_minor"] = pair.amount
		event := f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: input}, 201, "").body
		before := f.digest()
		linked := f.call(accessRequest{method: "POST", path: root + "/source-events/" + event["id"].(string) + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": "1", "decision": "link", "transaction_id": pair.id, "expected_transaction_version": "1"}}, 200, "")
		if linked.body["transaction_id"] != pair.id || f.digest() != before {
			t.Fatal("transfer/refund link changed money")
		}
	}
	// Absolute amount alone cannot attach an incoming signal to an outgoing transfer leg.
	wrong := f.sourceEvent(account)
	wrong["kind"] = "income"
	event := f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: wrong}, 201, "").body
	f.call(accessRequest{method: "POST", path: root + "/source-events/" + event["id"].(string) + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": "1", "decision": "link", "transaction_id": transfer["id"], "expected_transaction_version": "1"}}, 409, "version_conflict")
	if path := os.Getenv("SOURCE_MOVEMENT_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
func TestSourceUserCorrections(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	original := f.account(a)
	corrected := f.account(a)
	input := f.sourceEvent(original)
	event := f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: input}, 201, "").body
	decide := func(event map[string]any, decision string, id, version any) {
		f.call(accessRequest{method: "POST", path: root + "/source-events/" + event["id"].(string) + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": event["version"], "decision": decision, "transaction_id": id, "expected_transaction_version": version}}, 200, "")
	}
	read := func() map[string]any {
		return f.call(accessRequest{method: "GET", path: root + "/source-events/" + event["id"].(string), actor: a}, 200, "").body
	}
	decide(event, "create_pending", nil, nil)
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); err != nil {
		t.Fatal(err)
	}
	applied := read()
	id := applied["transaction_id"].(string)
	edit := f.basic(corrected, "expense")
	delete(edit, "id")
	edit["amount_minor"] = "200"
	edit["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "200"
	edit["expected_version"] = "1"
	edit["expected_parent_version"] = nil
	edit["note"] = "Owner changed account and amount"
	f.call(accessRequest{method: "PUT", path: root + "/transactions/" + id, actor: a, key: f.id(), body: edit}, 200, "")
	decide(applied, "confirm_posted", id, "2")
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); err != nil {
		t.Fatal(err)
	}
	result := f.call(accessRequest{method: "GET", path: root + "/transactions/" + id, actor: a}, 200, "").body
	entry := result["entries"].([]any)[0].(map[string]any)
	if result["status"] != "posted" || result["note"] != "Owner changed account and amount" || entry["account_id"] != corrected || entry["amount_minor"] != "-200" {
		t.Fatal("confirmation overwrote user correction", result)
	}
	first := f.call(accessRequest{method: "GET", path: root + "/accounts/" + original, actor: a}, 200, "").body
	second := f.call(accessRequest{method: "GET", path: root + "/accounts/" + corrected, actor: a}, 200, "").body
	if first["posted_balance_minor"] != "10000" || first["pending_delta_minor"] != "0" || second["posted_balance_minor"] != "9800" || second["pending_delta_minor"] != "0" {
		t.Fatal("wrong account was bumped", first, second)
	}
	if path := os.Getenv("SOURCE_CORRECTION_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
