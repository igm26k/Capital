package integration

import (
	"accounting/backend/internal/ledger"
	"encoding/base64"
	"os"
	"testing"
)

func TestBankCSV(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	preview := func(account, content string) map[string]any {
		body := map[string]any{"id": f.id(), "format": "csv", "content_base64": base64.StdEncoding.EncodeToString([]byte(content)), "mapping": map[string]any{"account_id": account, "date_column": "date", "amount_column": "amount", "payee_column": "payee", "date_format": "YYYY-MM-DD", "timezone": "UTC", "delimiter": ",", "encoding": "UTF-8", "decimal_separator": ".", "amount_convention": "signed_income_positive"}}
		return f.call(accessRequest{method: "POST", path: root + "/imports", actor: a, key: f.id(), body: body}, 201, "").body
	}
	confirm := func(p map[string]any, action string, id, version any, status int, code string) map[string]any {
		return f.call(accessRequest{method: "POST", path: root + "/imports/" + p["id"].(string) + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": p["version"], "decisions": []any{map[string]any{"row_number": 2, "action": action, "transaction_id": id, "expected_transaction_version": version}}}}, status, code).body
	}
	candidate := func(p map[string]any, id string) {
		t.Helper()
		row := p["rows"].([]any)[0].(map[string]any)
		if row["status"] != "ambiguous" {
			t.Fatal("bank candidate not shown", row)
		}
		found := false
		for _, value := range row["candidate_transaction_ids"].([]any) {
			found = found || value == id
		}
		if !found {
			t.Fatal("candidate missing", row, id)
		}
	}
	attach := func(account, kind, amount, id string) {
		input := f.sourceEvent(account)
		input["kind"] = kind
		input["amount_minor"] = amount
		f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: input}, 201, "")
		f.call(accessRequest{method: "POST", path: root + "/source-events/" + input["id"].(string) + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": "1", "decision": "link", "transaction_id": id, "expected_transaction_version": "1"}}, 200, "")
	}
	account := f.account(a)
	stale := preview(account, "date,amount,payee\n2026-10-02,-1.00,CSV wording\n")
	input := f.sourceEvent(account)
	input["notification_key_hash"] = input["external_id"]
	f.call(accessRequest{method: "POST", path: root + "/source-events/auto", actor: a, key: f.id(), body: input}, 201, "")
	confirm(stale, "create", nil, nil, 409, "import_source_pending")
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); err != nil {
		t.Fatal(err)
	}
	source := f.call(accessRequest{method: "GET", path: root + "/source-events/" + input["id"].(string), actor: a}, 200, "").body
	transaction := source["transaction_id"].(string)
	before := f.digest()
	refreshed := confirm(stale, "create", nil, nil, 200, "")
	if refreshed["state"] != "ready" || refreshed["version"] != "2" || refreshed["error_code"] != "version_conflict" || f.digest() != before {
		t.Fatal("stale preview queued duplicate money", refreshed)
	}
	candidate(refreshed, transaction)
	linked := confirm(refreshed, "link", transaction, "1", 200, "")
	if linked["state"] != "committed" || f.digest() != before {
		t.Fatal("CSV link changed preliminary money")
	}
	f.call(accessRequest{method: "POST", path: root + "/source-events/" + input["id"].(string) + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": source["version"], "decision": "confirm_posted", "transaction_id": transaction, "expected_transaction_version": "1"}}, 200, "")
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); err != nil {
		t.Fatal(err)
	}
	balance := f.call(accessRequest{method: "GET", path: root + "/accounts/" + account, actor: a}, 200, "").body
	if balance["posted_balance_minor"] != "9900" || balance["pending_delta_minor"] != "0" {
		t.Fatal("source and CSV counted twice", balance)
	}
	// A known weak match may still be an independent equal purchase after explicit review.
	independent := preview(account, "date,amount,payee\n2026-10-02,-1.00,Another CSV wording\n")
	candidate(independent, transaction)
	confirm(independent, "create", nil, nil, 200, "")
	if _, err := ledger.ProcessImports(f.ctx, f.auth, 10); err != nil {
		t.Fatal(err)
	}
	balance = f.call(accessRequest{method: "GET", path: root + "/accounts/" + account, actor: a}, 200, "").body
	if balance["posted_balance_minor"] != "9800" {
		t.Fatal("independent equal purchase was merged")
	}
	repeated := preview(account, "date,amount,payee\n2026-10-02,-1.00,Another CSV wording\n")
	before = f.digest()
	again := confirm(repeated, "create", nil, nil, 200, "")
	if again["state"] != "committed" || again["processing"].([]any)[0].(map[string]any)["state"] != "linked" || f.digest() != before {
		t.Fatal("weak bank match overrode exact CSV identity", again)
	}
	// Different dates and empty descriptions must still show bank-linked transfer and refund legs.
	outgoing := f.account(a)
	incoming := f.account(a)
	transfer := f.transfer(outgoing, incoming)
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: transfer}, 201, "")
	attach(incoming, "income", "100", transfer["id"].(string))
	p := preview(incoming, "date,amount,payee\n2026-10-02,1.00,\n")
	candidate(p, transfer["id"].(string))
	before = f.digest()
	confirm(p, "link", transfer["id"], "1", 200, "")
	if f.digest() != before {
		t.Fatal("CSV transfer link changed money")
	}
	wrong := preview(incoming, "date,amount,payee\n2026-10-02,-1.00,wrong direction\n")
	confirm(wrong, "link", transfer["id"], "1", 422, "validation_error")
	refundAccount := f.account(a)
	expense := f.basic(refundAccount, "expense")
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: expense}, 201, "")
	refund := f.refund(refundAccount, expense["id"].(string), expense["allocations"].([]any)[0].(map[string]any)["id"].(string))
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: refund}, 201, "")
	attach(refundAccount, "income", "1", refund["id"].(string))
	p = preview(refundAccount, "date,amount,payee\n2026-10-02,0.01,\n")
	candidate(p, refund["id"].(string))
	before = f.digest()
	confirm(p, "link", refund["id"], "1", 200, "")
	if f.digest() != before {
		t.Fatal("CSV refund link changed money")
	}
	if path := os.Getenv("BANK_CSV_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
