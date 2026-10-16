package integration

import (
	"os"
	"testing"
)

func TestSourcePages(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	account := f.account(a)
	ids := map[string]bool{}
	for i := 0; i < 103; i++ {
		input := f.sourceEvent(account)
		f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: input}, 201, "")
		ids[input["id"].(string)] = true
	}
	// Equal timestamps must still paginate without skipping UUID ties.
	if _, err := f.db.Exec(f.ctx, `UPDATE source_events SET received_at='2026-10-16T00:00:00Z' WHERE workspace_id=$1`, a.Workspace.ID); err != nil {
		t.Fatal(err)
	}
	legacy := f.call(accessRequest{method: "GET", path: root + "/source-events", actor: a}, 200, "").body
	if _, ok := legacy["next_before_id"]; ok {
		t.Fatal("legacy response changed shape")
	}
	if len(legacy["items"].([]any)) != 100 || legacy["has_more"] != true {
		t.Fatal("legacy page changed")
	}
	seen := map[string]bool{}
	cursor := ""
	pages := 0
	for {
		suffix := "?limit=17"
		if cursor != "" {
			suffix += "&before_id=" + cursor
		}
		page := f.call(accessRequest{method: "GET", path: root + "/source-events" + suffix, actor: a}, 200, "").body
		rows := page["items"].([]any)
		if len(rows) > 17 {
			t.Fatal("unbounded page")
		}
		for _, row := range rows {
			id := row.(map[string]any)["id"].(string)
			if seen[id] || !ids[id] {
				t.Fatal("duplicate or foreign event", id)
			}
			seen[id] = true
		}
		pages++
		if pages == 1 {
			newer := f.sourceEvent(account)
			f.call(accessRequest{method: "POST", path: root + "/source-events", actor: a, key: f.id(), body: newer}, 201, "")
			if _, err := f.db.Exec(f.ctx, `UPDATE source_events SET received_at='2026-10-17T00:00:00Z' WHERE workspace_id=$1 AND id=$2`, a.Workspace.ID, newer["id"]); err != nil {
				t.Fatal(err)
			}
		}
		if page["has_more"] == false {
			if page["next_before_id"] != nil {
				t.Fatal("terminal cursor")
			}
			break
		}
		cursor = page["next_before_id"].(string)
		if pages > 10 {
			t.Fatal("pagination did not terminate")
		}
	}
	if len(seen) != 103 || pages != 7 {
		t.Fatal("incomplete pagination", len(seen), pages)
	}
	for _, q := range []string{"?limit=0", "?limit=101", "?limit=2&limit=3", "?before_id=no", "?unexpected=1"} {
		f.call(accessRequest{method: "GET", path: root + "/source-events" + q, actor: a}, 400, "bad_request")
	}
	f.call(accessRequest{method: "GET", path: root + "/source-events?before_id=" + f.id(), actor: a}, 404, "not_found")
	foreignAccount := f.account(f.b)
	foreign := f.sourceEvent(foreignAccount)
	f.call(accessRequest{method: "POST", path: accessRoot(f.b) + "/source-events", actor: f.b, key: f.id(), body: foreign}, 201, "")
	f.call(accessRequest{method: "GET", path: root + "/source-events?before_id=" + foreign["id"].(string), actor: a}, 404, "not_found")
	if path := os.Getenv("SOURCE_PAGES_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
}
