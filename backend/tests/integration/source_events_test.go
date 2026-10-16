package integration

import (
	"os"
	"strings"
	"sync"
	"testing"
)

func (f *accessFixture) sourceEvent(account string) map[string]any {
	return map[string]any{"id": f.id(), "connection_id": f.id(), "external_id": strings.Repeat("a", 64), "account_id": account, "package_name": "example.bank", "profile_id": "personal-v1", "parser_version": 1, "kind": "expense", "amount_minor": "100", "currency": "EUR", "observed_at": "2026-10-01T00:00:00Z", "observed_timezone": "UTC", "merchant": "Synthetic shop", "card_last_four": nil}
}
func TestSourceEvents(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	account := f.account(a)
	input := f.sourceEvent(account)
	before := f.digest()
	key := f.id()
	request := accessRequest{method: "POST", path: root + "/source-events", actor: a, key: key, body: input}
	created := f.call(request, 201, "")
	if created.body["state"] != "review" {
		t.Fatal("observation unexpectedly applied")
	}
	replay := f.call(request, 201, "")
	if string(created.raw) != string(replay.raw) {
		t.Fatal("receipt changed")
	}
	fresh := accessClone(input)
	fresh["id"] = f.id()
	f.call(accessRequest{method: "POST", path: request.path, actor: a, key: f.id(), body: fresh}, 200, "")
	conflict := accessClone(input)
	conflict["amount_minor"] = "101"
	f.call(accessRequest{method: "POST", path: request.path, actor: a, key: f.id(), body: conflict}, 409, "source_event_conflict")
	second := accessClone(input)
	second["id"] = f.id()
	second["external_id"] = strings.Repeat("b", 64)
	f.call(accessRequest{method: "POST", path: request.path, actor: a, key: f.id(), body: second}, 201, "")
	list := f.call(accessRequest{method: "GET", path: request.path, actor: a}, 200, "")
	if len(list.body["items"].([]any)) != 2 {
		t.Fatal("identical purchases merged")
	}
	f.call(accessRequest{method: "GET", path: request.path + "/" + input["id"].(string), actor: a}, 200, "")
	if before != f.digest() {
		t.Fatal("review admission changed financial journal or entries")
	}
	invalid := accessClone(input)
	invalid["raw_text"] = "must never be accepted"
	f.call(accessRequest{method: "POST", path: request.path, actor: a, key: f.id(), body: invalid}, 422, "validation_error")
	invalid = accessClone(input)
	invalid["amount_minor"] = "0"
	f.call(accessRequest{method: "POST", path: request.path, actor: a, key: f.id(), body: invalid}, 422, "validation_error")
	wrong := accessClone(input)
	wrong["currency"] = "USD"
	f.call(accessRequest{method: "POST", path: request.path, actor: a, key: f.id(), body: wrong}, 422, "validation_error")
	f.call(accessRequest{method: "POST", path: request.path, actor: a, key: f.id(), body: input, headers: map[string]string{"X-Sync-Generation": f.id()}}, 409, "sync_generation_conflict")
	// A member of the same workspace still cannot inspect another user's raw source metadata.
	if _, err := f.db.Exec(f.ctx, `INSERT INTO memberships(workspace_id,user_id,role) VALUES($1,$2,'member')`, a.Workspace.ID, f.b.Profile.ID); err != nil {
		t.Fatal(err)
	}
	f.call(accessRequest{method: "GET", path: request.path + "/" + input["id"].(string), actor: f.b}, 404, "not_found")
	others := f.call(accessRequest{method: "GET", path: request.path, actor: f.b}, 200, "")
	if len(others.body["items"].([]any)) != 0 {
		t.Fatal("shared member saw private source observations")
	}
	// Two action IDs racing on the same immutable source identity publish one observation.
	third := accessClone(input)
	third["id"] = f.id()
	third["external_id"] = strings.Repeat("c", 64)
	fourth := accessClone(third)
	fourth["id"] = f.id()
	queries := []accessRequest{{method: "POST", path: request.path, actor: a, key: f.id(), body: third}, {method: "POST", path: request.path, actor: a, key: f.id(), body: fourth}}
	results := make([]accessResponse, 2)
	var wait sync.WaitGroup
	for i, q := range queries {
		wait.Add(1)
		go func(i int, q accessRequest) { defer wait.Done(); results[i] = f.send(q) }(i, q)
	}
	wait.Wait()
	statuses := map[int]int{}
	for _, r := range results {
		if r.err != nil {
			t.Fatal(r.err)
		}
		statuses[r.status]++
	}
	if statuses[201] != 1 || statuses[200] != 1 {
		t.Fatal("concurrent source publication", statuses)
	}
	if before != f.digest() {
		t.Fatal("concurrent intake changed financial state")
	}
	if path := os.Getenv("SOURCE_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
