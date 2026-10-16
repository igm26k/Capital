package integration

import (
	"accounting/backend/internal/ledger"
	"encoding/base64"
	"os"
	"testing"
)

func TestCSV(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	account := f.account(a)
	dateFormat := "YYYY-MM-DD"
	makePreview := func(content string) accessResponse {
		body := map[string]any{"id": f.id(), "format": "csv", "content_base64": base64.StdEncoding.EncodeToString([]byte(content)), "mapping": map[string]any{"account_id": account, "date_column": "date", "amount_column": "amount", "payee_column": "payee", "date_format": dateFormat, "timezone": "UTC", "delimiter": ",", "encoding": "UTF-8", "decimal_separator": ".", "amount_convention": "signed_income_positive"}}
		return f.call(accessRequest{method: "POST", path: root + "/imports", actor: a, key: f.id(), body: body}, 201, "")
	}
	content := "date,amount,payee,note\n2026-10-01,-1.23,Shop,purchase\n2026-10-01,-1.23,Shop,purchase\n2026-10-01,0,Shop,invalid\n"
	preview := makePreview(content)
	id := preview.body["id"].(string)
	if len(preview.body["rows"].([]any)) != 3 {
		t.Fatal("preview lost rows")
	}
	decisions := []any{map[string]any{"row_number": 2, "action": "create", "transaction_id": nil, "expected_transaction_version": nil}, map[string]any{"row_number": 3, "action": "create", "transaction_id": nil, "expected_transaction_version": nil}, map[string]any{"row_number": 4, "action": "skip", "transaction_id": nil, "expected_transaction_version": nil}}
	confirm := accessRequest{method: "POST", path: root + "/imports/" + id + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": "1", "decisions": decisions}}
	f.call(confirm, 200, "")
	f.call(confirm, 200, "")
	if n, err := ledger.ProcessImports(f.ctx, f.auth, 1); err != nil || n != 1 {
		t.Fatal("first job row", n, err)
	}
	progress := f.call(accessRequest{method: "GET", path: root + "/imports/" + id, actor: a}, 200, "")
	if progress.body["state"] != "queued" {
		t.Fatal("partial progress not durable")
	}

	if _, err := f.db.Exec(f.ctx, `UPDATE sessions SET revoked_at=clock_timestamp() WHERE id=$1`, a.Session.ID); err != nil {
		t.Fatal(err)
	}
	if n, err := ledger.ProcessImports(f.ctx, f.auth, 100); err != nil || n != 1 {
		t.Fatal("revoked job", n, err)
	}
	fresh, err := f.auth.Login(f.ctx, a.Profile.Email, "synthetic transfer password", "resumed CSV")
	if err != nil {
		t.Fatal(err)
	}
	a = fresh
	failed := f.call(accessRequest{method: "GET", path: root + "/imports/" + id, actor: a}, 200, "")
	if failed.body["state"] != "failed" {
		t.Fatal("revoked session kept writing")
	}
	f.call(accessRequest{method: "POST", path: root + "/imports/" + id + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": failed.body["version"], "decisions": []any{}}}, 200, "")
	if n, err := ledger.ProcessImports(f.ctx, f.auth, 100); err != nil || n != 1 {
		t.Fatal("restart/retry job row", n, err)
	}
	if n, err := ledger.ProcessImports(f.ctx, f.auth, 100); err != nil || n != 0 {
		t.Fatal("finished job ran again", n, err)
	}
	done := f.call(accessRequest{method: "GET", path: root + "/imports/" + id, actor: a}, 200, "")
	if done.body["state"] != "committed" {
		t.Fatal("job incomplete", done.body["state"])
	}
	var count int
	var balance string
	if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM transactions WHERE workspace_id=$1 AND kind='expense'`, a.Workspace.ID).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 2 {
		t.Fatal("identical real purchases were merged", count)
	}
	if err := f.db.QueryRow(f.ctx, `SELECT sum(e.amount_minor)::text FROM entries e JOIN transactions t ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id) WHERE t.workspace_id=$1 AND t.deleted_at IS NULL`, a.Workspace.ID).Scan(&balance); err != nil {
		t.Fatal(err)
	}
	if balance != "9754" {
		t.Fatal("incorrect imported money", balance)
	}
	again := makePreview(content)
	againID := again.body["id"].(string)
	f.call(accessRequest{method: "POST", path: root + "/imports/" + againID + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": "1", "decisions": decisions}}, 200, "")
	if n, err := ledger.ProcessImports(f.ctx, f.auth, 100); err != nil || n != 0 {
		t.Fatal("repeat file created duplicate", n, err)
	}
	f.call(accessRequest{method: "GET", path: root + "/imports/" + id, actor: f.b}, 404, "not_found")
	f.call(accessRequest{method: "GET", path: root + "/imports/" + id}, 401, "unauthenticated")
	export := f.call(accessRequest{method: "GET", path: root + "/exports?from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z", actor: a}, 200, "")
	data, err := base64.StdEncoding.DecodeString(export.body["content_base64"].(string))
	if err != nil || len(data) == 0 || export.body["row_count"] != float64(2) {
		t.Fatal("export incomplete", err, export.body["row_count"])
	}

	// Native exports round-trip existing complex operations by their strong source IDs.
	target := f.account(a)
	transfer := f.transfer(account, target)
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: transfer}, 201, "")
	first := done.body["processing"].([]any)[0].(map[string]any)["transaction_id"].(string)
	original := f.call(accessRequest{method: "GET", path: root + "/transactions/" + first, actor: a}, 200, "").body
	allocation := original["allocations"].([]any)[0].(map[string]any)["id"].(string)
	refund := f.refund(account, first, allocation)
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: refund}, 201, "")
	currentAccount := f.call(accessRequest{method: "GET", path: root + "/accounts/" + account, actor: a}, 200, "").body
	f.call(accessRequest{method: "POST", path: root + "/accounts/" + account + "/adjustments", actor: a, key: f.id(), body: map[string]any{"id": f.id(), "expected_balance_version": currentAccount["balance_version"], "target_balance_minor": "10000", "reason": "Count", "note": "Native adjustment", "occurred_timezone": "UTC"}}, 201, "")
	complete := f.call(accessRequest{method: "GET", path: root + "/exports?from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z&account_id=" + account, actor: a}, 200, "")
	exportBytes, err := base64.StdEncoding.DecodeString(complete.body["content_base64"].(string))
	if err != nil {
		t.Fatal(err)
	}
	// A user's metadata edit after export must survive the subsequent import.
	edit := f.basic(account, "expense")
	delete(edit, "id")
	edit["expected_version"] = "2"
	edit["expected_parent_version"] = nil
	edit["amount_minor"] = "123"
	edit["payee"] = "Shop"
	edit["note"] = "User preserved"
	edit["allocations"] = []any{map[string]any{"id": allocation, "category_id": nil, "amount_minor": "123"}}
	f.call(accessRequest{method: "PUT", path: root + "/transactions/" + first, actor: a, key: f.id(), body: edit}, 200, "")
	dateFormat = "RFC3339"
	roundtrip := makePreview(string(exportBytes))
	roundID := roundtrip.body["id"].(string)
	links := []any{}
	for _, raw := range roundtrip.body["rows"].([]any) {
		row := raw.(map[string]any)
		if row["status"] == "invalid" {
			t.Fatal("native exported operation rejected", row["row_number"])
		}
		id := row["source_transaction_id"].(string)
		current := f.call(accessRequest{method: "GET", path: root + "/transactions/" + id, actor: a}, 200, "").body
		links = append(links, map[string]any{"row_number": row["row_number"], "action": "link", "transaction_id": id, "expected_transaction_version": current["version"]})
	}
	var beforeHead int
	if err := f.db.QueryRow(f.ctx, `SELECT last_sequence FROM sync_heads WHERE workspace_id=$1`, a.Workspace.ID).Scan(&beforeHead); err != nil {
		t.Fatal(err)
	}
	f.call(accessRequest{method: "POST", path: root + "/imports/" + roundID + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": "1", "decisions": links}}, 200, "")
	if n, err := ledger.ProcessImports(f.ctx, f.auth, 100); err != nil || n != 0 {
		t.Fatal("roundtrip reissued financial records", n, err)
	}
	var afterHead int
	f.db.QueryRow(f.ctx, `SELECT last_sequence FROM sync_heads WHERE workspace_id=$1`, a.Workspace.ID).Scan(&afterHead)
	if afterHead != beforeHead {
		t.Fatal("read-only roundtrip changed financial journal")
	}
	kept := f.call(accessRequest{method: "GET", path: root + "/transactions/" + first, actor: a}, 200, "").body
	if kept["note"] != "User preserved" {
		t.Fatal("import overwrote user note")
	}
	if path := os.Getenv("CSV_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
