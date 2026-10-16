package integration

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	"github.com/jackc/pgx/v5"
	"os"
	"testing"
)

func TestReports(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	account := f.account(a)
	target := f.account(a)
	category := f.id()
	f.call(accessRequest{method: "POST", path: root + "/categories", actor: a, key: f.id(), body: map[string]any{"id": category, "name": "Food", "parent_id": nil}}, 201, "")
	create := func(account, kind, amount, date string, cat any) map[string]any {
		body := f.basic(account, kind)
		body["amount_minor"] = amount
		body["occurred_at"] = date
		body["note"] = "reports fixture"
		body["allocations"] = []any{map[string]any{"id": f.id(), "category_id": cat, "amount_minor": amount}}
		f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: body}, 201, "")
		return body
	}
	expense := f.basic(account, "expense")
	expense["amount_minor"] = "1200"
	expense["occurred_at"] = "2026-09-01T00:00:00Z"
	part := f.id()
	expense["allocations"] = []any{map[string]any{"id": part, "category_id": category, "amount_minor": "500"}, map[string]any{"id": f.id(), "category_id": nil, "amount_minor": "700"}}
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: expense}, 201, "")
	create(account, "income", "3000", "2026-09-15T00:00:00Z", nil)
	transfer := f.transfer(account, target)
	transfer["occurred_at"] = "2026-09-01T00:00:00Z"
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: transfer}, 201, "")
	refund := f.refund(account, expense["id"].(string), part)
	refund["amount_minor"] = "100"
	refund["occurred_at"] = "2026-09-01T00:00:00Z"
	refund["allocations"] = []any{map[string]any{"id": f.id(), "original_allocation_id": part, "amount_minor": "100"}}
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: refund}, 201, "")
	pending := create(account, "expense", "200", "2026-09-30T23:59:59.999999Z", nil)
	if _, err := f.db.Exec(f.ctx, `UPDATE transactions SET status='pending' WHERE id=$1`, pending["id"]); err != nil {
		t.Fatal(err)
	}
	create(account, "expense", "99", "2026-08-31T23:59:59.999999Z", nil)
	create(account, "expense", "500", "2026-10-01T00:00:00Z", nil)
	kwd := f.id()
	f.call(accessRequest{method: "POST", path: root + "/accounts", actor: a, key: f.id(), body: map[string]any{"id": kwd, "name": "KWD", "type": "cash", "currency": "KWD", "opened_at": "2026-01-01T00:00:00Z", "occurred_timezone": "UTC", "opening_balance_minor": "1234"}}, 201, "")
	create(kwd, "expense", "22", "2026-09-16T00:00:00Z", nil)
	query := "?from=2026-09-01T00:00:00Z&to=2026-10-01T00:00:00Z"
	from, _ := ledger.ParseInstant("2026-09-01T00:00:00Z")
	to, _ := ledger.ParseInstant("2026-10-01T00:00:00Z")
	if err := f.auth.WithSessionIsolation(f.ctx, a.Credential, pgx.RepeatableRead, func(tx pgx.Tx, p identity.Principal) error {
		_, err := ledger.ReadReport(f.ctx, tx, a.Workspace.ID, ledger.ReportQuery{From: from, To: to, Status: "all"})
		return err
	}); err != nil {
		t.Fatal("report calculation:", err)
	}
	report := f.call(accessRequest{method: "GET", path: root + "/reports" + query, actor: a}, 200, "").body
	totals := report["currencies"].([]any)
	if len(totals) != 2 {
		t.Fatalf("currencies mixed: %v", totals)
	}
	eur := totals[0].(map[string]any)
	for key, value := range map[string]string{"currency": "EUR", "income_minor": "3000", "expense_minor": "1300", "posted_expense_minor": "1100", "pending_expense_minor": "200"} {
		if eur[key] != value {
			t.Fatalf("%s: got %v want %s", key, eur[key], value)
		}
	}
	balances := report["balances"].([]any)
	balance := balances[0].(map[string]any)
	for key, value := range map[string]string{"opening_minor": "19901", "closing_minor": "21801", "projected_closing_minor": "21601"} {
		if balance[key] != value {
			t.Fatalf("%s: got %v want %s", key, balance[key], value)
		}
	}
	if balances[1].(map[string]any)["closing_minor"] != "1212" {
		t.Fatal("KWD scale changed")
	}
	selected := f.call(accessRequest{method: "GET", path: root + "/reports" + query + "&category_id=" + category, actor: a}, 200, "").body
	if selected["currencies"].([]any)[0].(map[string]any)["expense_minor"] != "400" {
		t.Fatal("split/refund category counted twice")
	}
	if selected["balances"].([]any)[0].(map[string]any)["closing_minor"] != "21801" {
		t.Fatal("classification changed real balance")
	}
	selected = f.call(accessRequest{method: "GET", path: root + "/reports" + query + "&status=pending", actor: a}, 200, "").body
	if selected["currencies"].([]any)[0].(map[string]any)["expense_minor"] != "200" {
		t.Fatal("pending excluded")
	}
	selected = f.call(accessRequest{method: "GET", path: root + "/reports" + query + "&account_id=" + account, actor: a}, 200, "").body
	if selected["balances"].([]any)[0].(map[string]any)["closing_minor"] != "11701" {
		t.Fatal("transfer side filter ignored")
	}
	foreign := f.account(f.b)
	f.call(accessRequest{method: "GET", path: root + "/reports" + query + "&account_id=" + foreign, actor: a}, 404, "not_found")
	f.call(accessRequest{method: "GET", path: root + "/reports" + query, actor: f.b}, 404, "not_found")
	f.call(accessRequest{method: "GET", path: root + "/reports" + query}, 401, "unauthenticated")
	for _, q := range []string{"", "?from=x&to=y", query + "&status=invalid", query + "&status=all&status=posted", query + "&unknown=true", "?from=2026-09-01T00:00:00Z&to=2026-09-01T00:00:00Z", "?from=2020-01-01T00:00:00Z&to=2026-09-01T00:00:00Z"} {
		f.call(accessRequest{method: "GET", path: root + "/reports" + q, actor: a}, 400, "bad_request")
	}
	if path := os.Getenv("REPORT_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
