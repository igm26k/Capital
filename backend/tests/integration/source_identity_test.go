package integration

import (
	"accounting/backend/internal/ledger"
	"crypto/sha256"
	"encoding/json"
	"os"
	"testing"
)

func TestSourceIdentityCompatibility(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	account := f.account(a)
	other := f.account(a)
	seed := f.sourceEvent(account)
	var input ledger.SourceEventInput
	raw, _ := json.Marshal(seed)
	if err := json.Unmarshal(raw, &input); err != nil {
		t.Fatal(err)
	}
	canonical := input
	canonical.ID = ""
	wire, _ := json.Marshal(canonical)
	hash := sha256.Sum256(wire)
	// A v1 row stays byte-for-byte intact, including its old provenance and hash.
	if _, err := f.db.Exec(f.ctx, `INSERT INTO source_events(workspace_id,id,actor_user_id,session_id,generation_id,connection_id,external_id,account_id,payload_hash,payload) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`, a.Workspace.ID, input.ID, a.Profile.ID, a.Session.ID, a.Workspace.Generation, input.ConnectionID, input.ExternalID, account, hash[:], raw); err != nil {
		t.Fatal(err)
	}
	before := f.digest()
	changed := accessClone(seed)
	changed["id"] = f.id()
	changed["connection_id"] = f.id()
	changed["profile_id"] = "replacement-profile"
	got := f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: changed}, 200, "")
	if got.body["id"] != input.ID || got.body["profile_id"] != input.ProfileID {
		t.Fatal("legacy provenance rewritten")
	}
	var format int
	var persistedHash []byte
	if err := f.db.QueryRow(f.ctx, `SELECT payload_hash_format,payload_hash FROM source_events WHERE workspace_id=$1 AND id=$2`, a.Workspace.ID, input.ID).Scan(&format, &persistedHash); err != nil || format != 1 || string(persistedHash) != string(hash[:]) {
		t.Fatal("legacy hash changed", format, err)
	}
	reassigned := accessClone(changed)
	reassigned["account_id"] = other
	f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: reassigned}, 409, "source_event_conflict")
	// Ambiguous legacy records are retained rather than silently selected/merged.
	second := input
	second.ID = f.id()
	second.ConnectionID = f.id()
	second.ProfileID = "second-legacy-profile"
	raw, _ = json.Marshal(second)
	canonical = second
	canonical.ID = ""
	wire, _ = json.Marshal(canonical)
	secondHash := sha256.Sum256(wire)
	if _, err := f.db.Exec(f.ctx, `INSERT INTO source_events(workspace_id,id,actor_user_id,session_id,generation_id,connection_id,external_id,account_id,payload_hash,payload) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`, a.Workspace.ID, second.ID, a.Profile.ID, a.Session.ID, a.Workspace.Generation, second.ConnectionID, second.ExternalID, account, secondHash[:], raw); err != nil {
		t.Fatal(err)
	}
	f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: changed}, 409, "source_event_conflict")
	var count int
	if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM source_events WHERE workspace_id=$1`, a.Workspace.ID).Scan(&count); err != nil || count != 2 {
		t.Fatal("legacy source records lost", count, err)
	}
	if before != f.digest() {
		t.Fatal("identity migration changed financial state")
	}
	if path := os.Getenv("SOURCE_IDENTITY_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
