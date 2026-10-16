package integration

import (
	"accounting/backend/internal/ledger"
	"encoding/base64"
	"os"
	"strings"
	"testing"
)

func TestUserData(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	b := f.b
	account := f.account(a)
	other := f.account(b)
	source := f.sourceEvent(account)
	f.call(accessRequest{method: "POST", path: accessRoot(a) + "/source-events", actor: a, key: f.id(), body: source}, 201, "")
	body := f.basic(account, "expense")
	f.call(accessRequest{method: "POST", path: accessRoot(a) + "/transactions", actor: a, key: f.id(), body: body}, 201, "")

	category := f.id()
	f.call(accessRequest{method: "POST", path: accessRoot(a) + "/categories", actor: a, key: f.id(), body: map[string]any{"id": category, "name": "Deletion category", "parent_id": nil}}, 201, "")
	f.call(accessRequest{method: "POST", path: accessRoot(a) + "/budgets", actor: a, key: f.id(), body: map[string]any{"id": f.id(), "name": "Deletion budget", "category_id": category, "currency": "EUR", "month": "2026-10", "timezone": "UTC", "limit_minor": "100"}}, 201, "")
	preview := f.call(accessRequest{method: "POST", path: accessRoot(a) + "/imports", actor: a, key: f.id(), body: map[string]any{"id": f.id(), "format": "csv", "content_base64": base64.StdEncoding.EncodeToString([]byte("date,amount\n2026-10-01,-1.00\n")), "mapping": map[string]any{"account_id": account, "date_column": "date", "amount_column": "amount", "payee_column": nil, "date_format": "YYYY-MM-DD", "timezone": "UTC", "delimiter": ",", "encoding": "UTF-8", "decimal_separator": ".", "amount_convention": "signed_income_positive"}}}, 201, "")
	f.call(accessRequest{method: "POST", path: accessRoot(a) + "/imports/" + preview.body["id"].(string) + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": "1", "decisions": []any{map[string]any{"row_number": 2, "action": "create", "transaction_id": nil, "expected_transaction_version": nil}}}}, 200, "")
	data := f.call(accessRequest{method: "GET", path: "/api/v1/me/export", actor: a}, 200, "")
	raw := string(data.raw)
	if !strings.Contains(raw, source["id"].(string)) || !strings.Contains(raw, account) || strings.Contains(raw, other) || strings.Contains(raw, "password_hash") || strings.Contains(raw, "token_hash") || strings.Contains(raw, a.Credential) {
		t.Fatal("export leaked credentials or foreign data")
	}
	f.call(accessRequest{method: "GET", path: "/api/v1/me/export"}, 401, "unauthenticated")
	request := accessRequest{method: "DELETE", path: "/api/v1/me", actor: a, body: map[string]any{"password": "wrong-password", "confirmation": "DELETE"}}
	f.call(request, 403, "password_invalid")
	request.body = map[string]any{"password": "synthetic transfer password", "confirmation": "DELETE"}
	if _, err := f.db.Exec(f.ctx, `INSERT INTO memberships(workspace_id,user_id,role) VALUES($1,$2,'member')`, a.Workspace.ID, b.Profile.ID); err != nil {
		t.Fatal(err)
	}
	f.call(request, 409, "shared_workspace")
	if _, err := f.db.Exec(f.ctx, `UPDATE memberships SET revoked_at=clock_timestamp() WHERE workspace_id=$1 AND user_id=$2`, a.Workspace.ID, b.Profile.ID); err != nil {
		t.Fatal(err)
	}
	f.call(request, 200, "")
	f.call(accessRequest{method: "GET", path: "/api/v1/auth/session", actor: a}, 401, "unauthenticated")
	var count int
	if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM accounts WHERE workspace_id=$1`, a.Workspace.ID).Scan(&count); err != nil || count != 0 {
		t.Fatal("personal financial data not erased", err, count)
	}
	if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM accounts WHERE workspace_id=$1`, b.Workspace.ID).Scan(&count); err != nil || count != 1 {
		t.Fatal("another user was changed", err, count)
	}
	if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM source_events WHERE actor_user_id=$1`, a.Profile.ID).Scan(&count); err != nil || count != 0 {
		t.Fatal("private source observations not erased", err, count)
	}
	f.call(accessRequest{method: "GET", path: accessRoot(b) + "/accounts/" + other, actor: b}, 200, "")
	if n, err := ledger.ProcessImports(f.ctx, f.auth, 100); err != nil || n != 0 {
		t.Fatal("deleted user job continued", n, err)
	}
	if path := os.Getenv("USER_DATA_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
