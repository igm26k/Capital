package integration

import (
	"accounting/backend/internal/ledger"
	"os"
	"testing"
)

func TestSourceRelink(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	account := f.account(a)
	target := f.account(a)
	input := f.sourceEvent(account)
	f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: input}, 201, "")
	read := func() map[string]any {
		return f.call(accessRequest{method: "GET", path: root + "/source-events/" + input["id"].(string), actor: a}, 200, "").body
	}
	decide := func(event map[string]any, decision string, id, version any, status int, code string) map[string]any {
		return f.call(accessRequest{method: "POST", path: root + "/source-events/" + input["id"].(string) + "/confirm", actor: a, key: f.id(), body: map[string]any{"expected_version": event["version"], "decision": decision, "transaction_id": id, "expected_transaction_version": version}}, status, code).body
	}
	decide(read(), "create_pending", nil, nil, 200, "")
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); err != nil {
		t.Fatal(err)
	}
	applied := read()
	old := applied["transaction_id"].(string)
	transfer := f.transfer(account, target)
	f.call(accessRequest{method: "POST", path: root + "/transactions", actor: a, key: f.id(), body: transfer}, 201, "")
	// A live preliminary cannot be silently replaced or cancelled by metadata linking.
	decide(applied, "link", transfer["id"], "1", 409, "source_event_not_ready")
	decide(applied, "cancel_pending", old, "1", 200, "")
	if _, err := ledger.ProcessSourceEvents(f.ctx, f.auth, 1); err != nil {
		t.Fatal(err)
	}
	tombstoned := read()
	before := f.digest()
	linked := decide(tombstoned, "link", transfer["id"], "1", 200, "")
	if linked["transaction_id"] != transfer["id"] || f.digest() != before {
		t.Fatal("relink changed money or revived deleted transaction")
	}
	f.call(accessRequest{method: "GET", path: root + "/transactions/" + old, actor: a}, 404, "not_found")
	var original string
	if err := f.db.QueryRow(f.ctx, `SELECT job_history->(jsonb_array_length(job_history)-1)->'link'->>'from_transaction_id' FROM source_events WHERE workspace_id=$1 AND id=$2`, a.Workspace.ID, input["id"]).Scan(&original); err != nil || original != old {
		t.Fatal("relink history missing", err)
	}
	decide(tombstoned, "link", transfer["id"], "1", 409, "version_conflict")
	// The remapped live transfer cannot be replaced again without its own tombstone.
	decide(linked, "link", transfer["id"], "1", 409, "source_event_not_ready")
	repeated := f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: input}, 200, "").body
	if repeated["transaction_id"] != transfer["id"] {
		t.Fatal("reimport lost the explicit reconciliation")
	}
	if path := os.Getenv("SOURCE_RELINK_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
