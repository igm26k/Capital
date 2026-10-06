package ledger

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestBasicWireShape(t *testing.T) {
	body := map[string]any{"id": "c0000000-0000-4000-8000-000000000001", "kind": "expense", "account_id": "c0000000-0000-4000-8000-000000000002", "amount_minor": "100", "occurred_at": "2026-01-01T00:00:00Z", "occurred_timezone": "UTC", "note": "", "payee": "", "tag_ids": []string{}, "allocations": []any{map[string]any{"id": "c0000000-0000-4000-8000-000000000003", "category_id": nil, "amount_minor": "100"}}}
	encode := func(v any) []byte { b, _ := json.Marshal(v); return b }
	if _, e := DecodeBasic(encode(body), false); e != nil {
		t.Fatal(e)
	}
	for field := range body {
		copy := map[string]any{}
		for k, v := range body {
			copy[k] = v
		}
		delete(copy, field)
		if _, e := DecodeBasic(encode(copy), false); e == nil {
			t.Fatalf("missing %s accepted", field)
		}
		copy[field] = nil
		if _, e := DecodeBasic(encode(copy), false); e == nil {
			t.Fatalf("null %s accepted", field)
		}
	}
	for field, values := range map[string][]any{"amount_minor": {"0", "-1", 1}, "note": {strings.Repeat("x", 2001), "nul\x00text"}, "tag_ids": {[]string{"C0000000-0000-4000-8000-000000000003", "c0000000-0000-4000-8000-000000000003"}}, "allocations": {[]any{}, []any{map[string]any{"id": "c0000000-0000-4000-8000-000000000003", "amount_minor": "100"}}, []any{map[string]any{"id": "c0000000-0000-4000-8000-000000000003", "category_id": nil, "amount_minor": "100", "unknown": true}}}} {
		for _, value := range values {
			copy := map[string]any{}
			for k, v := range body {
				copy[k] = v
			}
			copy[field] = value
			if _, e := DecodeBasic(encode(copy), false); e == nil {
				t.Fatalf("invalid %s accepted", field)
			}
		}
	}
	parts := []any{map[string]any{"id": "c0000000-0000-4000-8000-000000000003", "category_id": nil, "amount_minor": "60"}, map[string]any{"id": "C0000000-0000-4000-8000-000000000003", "category_id": nil, "amount_minor": "40"}}
	body["allocations"] = parts
	if _, e := DecodeBasic(encode(body), false); e == nil {
		t.Fatal("duplicate part UUID accepted")
	}
	body["allocations"] = parts[:1]
	delete(body, "id")
	body["expected_version"] = "1"
	body["expected_parent_version"] = nil
	if _, e := DecodeBasic(encode(body), true); e != nil {
		t.Fatal(e)
	}
	delete(body, "expected_parent_version")
	if _, e := DecodeBasic(encode(body), true); e == nil {
		t.Fatal("missing nullable parent version accepted")
	}
	body["kind"] = "income"
	if _, e := DecodeBasic(encode(body), true); e != nil {
		t.Fatal(e)
	}
}
func TestClassificationWireShape(t *testing.T) {
	for _, test := range []struct {
		body, kind string
		update     bool
		valid      bool
	}{
		{`{"id":"c0000000-0000-4000-8000-000000000001","name":"Food","parent_id":null}`, "category", false, true},
		{`{"id":"c0000000-0000-4000-8000-000000000001","name":"Food"}`, "category", false, false},
		{`{"id":"c0000000-0000-4000-8000-000000000001","name":"Trip"}`, "tag", false, true},
		{`{"expected_version":"1","name":"Trip","archived":false}`, "tag", true, true},
		{`{"expected_version":"1","name":"Trip","archived":null}`, "tag", true, false},
		{`{"expected_version":"01","name":"Trip","archived":false}`, "tag", true, false},
		{`{"expected_version":"1","name":"Food","archived":false,"Parent_id":null}`, "category", true, false},
	} {
		_, e := DecodeClassification([]byte(test.body), test.kind, test.update)
		if (e == nil) != test.valid {
			t.Fatalf("classification shape: %s %v", test.body, e)
		}
	}
}
