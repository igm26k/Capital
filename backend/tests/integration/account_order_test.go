package integration

import (
	"encoding/json"
	"os"
	"reflect"
	"strconv"
	"testing"
)

func TestAccountOrder(t *testing.T) {
	f := newAccessFixture(t)
	a := f.a
	root := accessRoot(a)
	empty := f.call(accessRequest{method: "GET", path: root + "/accounts/order", actor: a}, 200, "").body
	if ids, ok := empty["account_ids"].([]any); !ok || len(ids) != 0 || empty["version"] != "0" {
		t.Fatal("empty workspace order is not a complete array")
	}
	first, second, third := f.account(a), f.account(a), f.account(a)
	foreign := f.account(f.b)
	read := func() map[string]any {
		return f.call(accessRequest{method: "GET", path: root + "/accounts/order", actor: a}, 200, "").body
	}
	initial := read()
	initialVersion := initial["version"].(string)
	ids := []string{third, first, second}
	var balanceVersion string
	if err := f.db.QueryRow(f.ctx, "SELECT balance_version::text FROM accounts WHERE id=$1", third).Scan(&balanceVersion); err != nil {
		t.Fatal(err)
	}
	request := accessRequest{method: "PUT", path: root + "/accounts/order", actor: a, key: f.id(), body: map[string]any{"expected_version": initialVersion, "account_ids": ids}}
	result := f.call(request, 200, "")
	replay := f.call(request, 200, "")
	if !reflect.DeepEqual(result.body, replay.body) || replay.header.Get("Idempotency-Replayed") != "true" {
		t.Fatal("reorder replay changed result")
	}
	var got []string
	raw, _ := json.Marshal(read()["account_ids"])
	json.Unmarshal(raw, &got)
	if !reflect.DeepEqual(ids, got) {
		t.Fatal("saved order differs")
	}
	request.key = f.id()
	f.call(request, 409, "version_conflict")
	request.body = map[string]any{"expected_version": read()["version"], "account_ids": []string{third, first, foreign}}
	request.key = f.id()
	f.call(request, 404, "not_found")
	request.body = map[string]any{"expected_version": read()["version"], "account_ids": []string{third, first}}
	request.key = f.id()
	f.call(request, 409, "version_conflict")
	f.call(accessRequest{method: "GET", path: root + "/accounts/order", actor: f.b}, 404, "not_found")
	f.call(accessRequest{method: "PUT", path: root + "/accounts/order", actor: f.b, key: f.id(), body: map[string]any{"expected_version": read()["version"], "account_ids": ids}}, 404, "not_found")
	f.call(accessRequest{method: "GET", path: root + "/accounts/order?unexpected=1", actor: a}, 400, "bad_request")
	// Archive and metadata changes retain the same place in the complete order.
	record := f.call(accessRequest{method: "GET", path: root + "/accounts/" + first, actor: a}, 200, "").body
	f.call(accessRequest{method: "PUT", path: root + "/accounts/" + first, actor: a, key: f.id(), body: map[string]any{"expected_version": record["version"], "name": "Archived", "type": "bank", "archived": true}}, 200, "")
	raw, _ = json.Marshal(read()["account_ids"])
	json.Unmarshal(raw, &got)
	if !reflect.DeepEqual(ids, got) {
		t.Fatal("archiving changed order")
	}
	fourth := f.account(a)
	ids = append(ids, fourth)
	raw, _ = json.Marshal(read()["account_ids"])
	json.Unmarshal(raw, &got)
	if !reflect.DeepEqual(ids, got) {
		t.Fatal("new account did not append")
	}
	fifth := f.account(a)
	ids = append(ids, fifth)
	raw, _ = json.Marshal(read()["account_ids"])
	json.Unmarshal(raw, &got)
	if !reflect.DeepEqual(ids, got) {
		t.Fatal("a later account reshuffled existing positions")
	}
	var afterBalanceVersion string
	if err := f.db.QueryRow(f.ctx, "SELECT balance_version::text FROM accounts WHERE id=$1", third).Scan(&afterBalanceVersion); err != nil {
		t.Fatal(err)
	}
	if balanceVersion != afterBalanceVersion {
		t.Fatal("display order changed financial balance version")
	}
	var total string
	if err := f.db.QueryRow(f.ctx, "SELECT sum(amount_minor)::text FROM entries WHERE workspace_id=$1", a.Workspace.ID).Scan(&total); err != nil {
		t.Fatal(err)
	}
	if total != "50000" {
		t.Fatal("display preference changed money")
	}
	// Competing orders use an independent preference version: only one wins.
	raceVersion := read()["version"].(string)
	one := accessRequest{method: "PUT", path: root + "/accounts/order", actor: a, key: f.id(), body: map[string]any{"expected_version": raceVersion, "account_ids": []string{fourth, second, first, third, fifth}}}
	two := one
	two.key = f.id()
	two.body = map[string]any{"expected_version": raceVersion, "account_ids": []string{second, first, third, fourth, fifth}}
	c1, c2 := f.async(one), f.async(two)
	r1, r2 := f.await(c1), f.await(c2)
	if !((r1.status == 200 && r2.status == 409) || (r1.status == 409 && r2.status == 200)) {
		t.Fatal("concurrent reorders did not serialize")
	}
	n, _ := strconv.ParseInt(raceVersion, 10, 64)
	if read()["version"] != strconv.FormatInt(n+1, 10) {
		t.Fatal("order version incorrect")
	}
	if file := os.Getenv("ACCOUNT_ORDER_RESPONSES"); file != "" {
		f.saveTo(file)
	}
}
