package integration

import (
	"os"
	"testing"
)

func TestPlanning(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	account := f.account(a)
	parent, child := f.id(), f.id()
	for _, v := range []struct {
		id     string
		parent any
	}{{parent, nil}, {child, parent}} {
		f.call(accessRequest{method: "POST", path: root + "/categories", actor: a, key: f.id(), body: map[string]any{"id": v.id, "name": v.id, "parent_id": v.parent}}, 201, "")
	}
	expense := f.basic(account, "expense")
	expense["allocations"].([]any)[0].(map[string]any)["category_id"] = child
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: expense}, 201, "")
	pending := f.basic(account, "expense")
	pending["amount_minor"] = "200"
	pending["allocations"].([]any)[0].(map[string]any)["amount_minor"] = "200"
	pending["allocations"].([]any)[0].(map[string]any)["category_id"] = child
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: pending}, 201, "")
	if _, err := f.db.Exec(f.ctx, `UPDATE transactions SET status='pending' WHERE id=$1`, pending["id"]); err != nil {
		t.Fatal(err)
	}
	budget := map[string]any{"id": f.id(), "name": "Food month", "category_id": parent, "currency": "EUR", "month": "2026-10", "timezone": "UTC", "limit_minor": "300"}
	key := f.id()
	request := accessRequest{method: "POST", path: root + "/budgets", actor: a, key: key, body: budget}
	created := f.call(request, 201, "")
	replayed := f.call(request, 201, "")
	if string(created.raw) != string(replayed.raw) || replayed.header.Get("Idempotency-Replayed") != "true" {
		t.Fatal("planning lost-response replay changed result")
	}
	changed := accessClone(budget)
	changed["limit_minor"] = "400"
	request.body = changed
	f.call(request, 409, "idempotency_conflict")
	list := f.call(accessRequest{method: "GET", path: root + "/budgets", actor: a}, 200, "").body["items"].([]any)
	v := list[0].(map[string]any)
	if v["spent_minor"] != "300" || v["posted_spent_minor"] != "100" || v["pending_spent_minor"] != "200" || v["warning"] != "exceeded" {
		t.Fatalf("subtree/pending limit incorrect: %v", v)
	}
	update := accessClone(changed)
	delete(update, "id")
	update["expected_version"] = "1"
	f.call(accessRequest{method: "PUT", path: root + "/budgets/" + budget["id"].(string), actor: a, key: f.id(), body: update}, 200, "")
	stale := accessRequest{method: "PUT", path: root + "/budgets/" + budget["id"].(string), actor: a, key: f.id(), body: update}
	f.call(stale, 409, "version_conflict")
	f.call(stale, 409, "version_conflict")
	f.call(accessRequest{method: "GET", path: root + "/budgets", actor: f.b}, 404, "not_found")
	f.call(accessRequest{method: "DELETE", path: root + "/budgets/" + budget["id"].(string), actor: a, key: f.id(), body: map[string]any{"expected_version": "2"}}, 200, "")
	if len(f.call(accessRequest{method: "GET", path: root + "/budgets", actor: a}, 200, "").body["items"].([]any)) != 0 {
		t.Fatal("deleted budget visible")
	}
	eligible := f.basic(account, "expense")
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: eligible}, 201, "")
	rule := map[string]any{"id": f.id(), "name": "Shop", "category_id": parent, "priority": 10, "enabled": true, "transaction_kind": "expense", "payee_contains": "synthetic", "note_contains": ""}
	f.call(accessRequest{method: "POST", path: root + "/rules", actor: a, key: f.id(), body: rule}, 201, "")

	previewPath := root + "/rules/preview?from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z"
	preview := f.call(accessRequest{method: "GET", path: previewPath, actor: a}, 200, "").body
	batches := preview["batches"].([]any)
	if len(batches) != 1 {
		t.Fatal("rule failed to suggest", preview)
	}
	batch := batches[0].(map[string]any)
	items := batch["items"].([]any)
	if len(items) != 1 || items[0].(map[string]any)["id"] != eligible["id"] {
		t.Fatal("manual classification would be overwritten")
	}
	f.call(accessRequest{method: "POST", path: root + "/transactions/classification", actor: a, key: f.id(), body: batch}, 200, "")
	if len(f.call(accessRequest{method: "GET", path: previewPath, actor: a}, 200, "").body["batches"].([]any)) != 0 {
		t.Fatal("classified record suggested again")
	}

	automatic := f.basic(account, "expense")
	result := f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: automatic}, 201, "")
	if result.body["transactions"].([]any)[0].(map[string]any)["allocations"].([]any)[0].(map[string]any)["category_id"] != parent {
		t.Fatal("new unclassified purchase did not use rule")
	}
	var head int
	var audit int
	if err := f.db.QueryRow(f.ctx, `SELECT last_sequence FROM sync_heads WHERE workspace_id=$1`, a.Workspace.ID).Scan(&head); err != nil {
		t.Fatal(err)
	}
	if head != 8 {
		t.Fatal("configuration polluted financial journal", head)
	}
	if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM planning_audit WHERE workspace_id=$1`, a.Workspace.ID).Scan(&audit); err != nil {
		t.Fatal(err)
	}
	if path := os.Getenv("PLANNING_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
	if audit != 4 {
		t.Fatal("planning replay duplicated audit", audit)
	}
}
