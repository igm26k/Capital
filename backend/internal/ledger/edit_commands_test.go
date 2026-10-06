package ledger

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestAdjustmentWire(t *testing.T) {
	encode := func(v any) []byte { b, _ := json.Marshal(v); return b }
	body := map[string]any{"id": "a0000000-0000-4000-8000-000000000001", "expected_balance_version": "1", "target_balance_minor": "0", "reason": "Сверка", "note": "", "occurred_timezone": "UTC"}
	if _, e := DecodeAdjustmentCreate(encode(body)); e != nil {
		t.Fatal(e)
	}
	for field := range body {
		copied := map[string]any{}
		for k, v := range body {
			copied[k] = v
		}
		delete(copied, field)
		if _, e := DecodeAdjustmentCreate(encode(copied)); e == nil {
			t.Fatalf("missing %s accepted", field)
		}
	}
	for _, bad := range []any{"-0", "01", "1.0", "9000000000000001", 1} {
		body["target_balance_minor"] = bad
		if _, e := DecodeAdjustmentCreate(encode(body)); e == nil {
			t.Fatal("invalid target accepted")
		}
	}
	body["target_balance_minor"] = "0"
	body["reason"] = ""
	if _, e := DecodeAdjustmentCreate(encode(body)); e == nil {
		t.Fatal("empty reason accepted")
	}
	update := map[string]any{"kind": "adjustment", "expected_version": "1", "note": "literal note"}
	if _, e := DecodeAdjustmentReplace(encode(update)); e != nil {
		t.Fatal(e)
	}
	for _, forbidden := range []string{"target_balance_minor", "amount_minor", "reason", "account_id", "occurred_at"} {
		update[forbidden] = "1"
		if _, e := DecodeAdjustmentReplace(encode(update)); e == nil {
			t.Fatalf("adjustment edit accepted %s", forbidden)
		}
		delete(update, forbidden)
	}
}

func TestBulkClassificationWire(t *testing.T) {
	encode := func(v any) []byte { b, _ := json.Marshal(v); return b }
	id := "a0000000-0000-4000-8000-000000000001"
	body := map[string]any{"items": []any{map[string]any{"id": id, "expected_version": "1"}}, "category_id": nil, "tag_ids": []string{}}
	if _, e := DecodeBulkClassification(encode(body)); e != nil {
		t.Fatal(e)
	}
	for field := range body {
		copied := map[string]any{}
		for k, v := range body {
			copied[k] = v
		}
		delete(copied, field)
		if _, e := DecodeBulkClassification(encode(copied)); e == nil {
			t.Fatalf("missing %s accepted", field)
		}
	}
	body["items"] = []any{map[string]any{"id": id, "expected_version": "1"}, map[string]any{"id": strings.ToUpper(id), "expected_version": "2"}}
	if _, e := DecodeBulkClassification(encode(body)); e == nil {
		t.Fatal("duplicate normalized transaction accepted")
	}
	body["items"] = []any{map[string]any{"id": id, "expected_version": "1", "amount_minor": "1"}}
	if _, e := DecodeBulkClassification(encode(body)); e == nil {
		t.Fatal("nested financial field accepted")
	}
	body["items"] = []any{map[string]any{"id": id, "expected_version": "1"}}
	body["tag_ids"] = []string{id, strings.ToUpper(id)}
	if _, e := DecodeBulkClassification(encode(body)); e == nil {
		t.Fatal("duplicate normalized tag accepted")
	}
	body["tag_ids"] = []string{}
	body["items"] = []any{}
	if _, e := DecodeBulkClassification(encode(body)); e == nil {
		t.Fatal("empty package accepted")
	}
	items := make([]any, 101)
	for i := range items {
		items[i] = map[string]any{"id": id, "expected_version": "1"}
	}
	body["items"] = items
	if _, e := DecodeBulkClassification(encode(body)); e == nil {
		t.Fatal("oversized package accepted")
	}
}
