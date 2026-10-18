package integration

import (
	"testing"
	"time"
)

func TestHistoricalAdjustments(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	account := f.account(a)
	// A correction adds to the actual ledger, including any intervening activity.
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: f.basic(account, "expense")}, 201, "")
	makeRequest := func(amount string) accessRequest {
		return accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: map[string]any{"id": f.id(), "kind": "adjustment", "account_id": account, "amount_minor": amount, "occurred_at": "2026-10-01T08:15:30.123Z", "occurred_timezone": "UTC", "reason": "Wallet missing counterparty", "note": "Original Wallet correction", "payee": "Synthetic"}}
	}
	credit := makeRequest("300")
	debit := makeRequest("-500")
	for _, q := range []accessRequest{credit, debit, credit, debit} {
		f.call(q, 201, "")
	}
	current := f.call(accessRequest{method: "GET", path: root + "/accounts/" + account, actor: a}, 200, "")
	if current.body["posted_balance_minor"] != "9700" {
		t.Fatal("correction replaced balance or replay duplicated it", current.body)
	}
	id := credit.body.(map[string]any)["id"].(string)
	transaction := f.call(accessRequest{method: "GET", path: root + "/transactions/" + id, actor: a}, 200, "")
	occurred, dateErr := time.Parse(time.RFC3339Nano, transaction.body["occurred_at"].(string))
	expectedDate, _ := time.Parse(time.RFC3339Nano, "2026-10-01T08:15:30.123Z")
	if dateErr != nil || !occurred.Equal(expectedDate) || transaction.body["kind"] != "adjustment" || transaction.body["reason"] != "Wallet missing counterparty" || transaction.body["note"] != "Original Wallet correction" {
		t.Fatal("historical correction metadata changed", transaction.body)
	}
	var count, allocations int
	if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM transactions WHERE workspace_id=$1 AND kind='adjustment'`, a.Workspace.ID).Scan(&count); err != nil || count != 2 {
		t.Fatal("incorrect adjustment count", count, err)
	}
	if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM allocations p JOIN transactions t ON (t.workspace_id,t.id)=(p.workspace_id,p.transaction_id) WHERE t.workspace_id=$1 AND t.kind='adjustment'`, a.Workspace.ID).Scan(&allocations); err != nil || allocations != 0 {
		t.Fatal("correction gained expense allocations", allocations, err)
	}
	overflow := makeRequest("9000000000000000")
	f.call(overflow, 422, "amount_out_of_range")
	for _, date := range []string{"2025-01-01T00:00:00Z", "9998-01-01T00:00:00Z"} {
		q := makeRequest("1")
		q.body.(map[string]any)["occurred_at"] = date
		f.call(q, 422, "validation_error")
	}
	foreign := makeRequest("1")
	foreign.body.(map[string]any)["account_id"] = f.account(f.b)
	f.call(foreign, 404, "not_found")
	if _, err := f.db.Exec(f.ctx, `UPDATE accounts SET archived_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, a.Workspace.ID, account); err != nil {
		t.Fatal(err)
	}
	f.call(makeRequest("1"), 422, "account_archived")
	if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM transactions WHERE workspace_id=$1 AND kind='adjustment'`, a.Workspace.ID).Scan(&count); err != nil || count != 2 {
		t.Fatal("rejected correction was partially written", count, err)
	}
}
