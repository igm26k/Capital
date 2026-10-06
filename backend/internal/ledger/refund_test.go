package ledger

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestRefundWireAndExactParts(t *testing.T) {
	original := "f0000000-0000-4000-8000-000000000003"
	part := map[string]any{"id": "f0000000-0000-4000-8000-000000000004", "original_allocation_id": original, "amount_minor": "1234"}
	body := map[string]any{"id": "f0000000-0000-4000-8000-000000000001", "kind": "refund", "account_id": "f0000000-0000-4000-8000-000000000002", "parent_transaction_id": "f0000000-0000-4000-8000-000000000005", "amount_minor": "1234", "expected_parent_version": "1", "expected_transfer_version": nil, "occurred_at": "2026-01-03T00:00:00Z", "occurred_timezone": "UTC", "note": "", "payee": "", "tag_ids": []string{}, "allocations": []any{part}}
	encode := func(v any) []byte { b, _ := json.Marshal(v); return b }
	valid, e := DecodeRefundCreate(encode(body))
	if e != nil || refundSum(valid) != nil {
		t.Fatal("valid refund rejected")
	}
	for field := range body {
		copy := map[string]any{}
		for k, v := range body {
			copy[k] = v
		}
		delete(copy, field)
		if _, e := DecodeRefundCreate(encode(copy)); e == nil {
			t.Fatalf("missing %s accepted", field)
		}
	}
	// Replacement supplies its own version and uses path identity only.
	delete(body, "id")
	body["expected_version"] = "1"
	if _, e := DecodeRefundReplace(encode(body)); e != nil {
		t.Fatal(e)
	}
	for field := range body {
		copy := map[string]any{}
		for k, v := range body {
			copy[k] = v
		}
		delete(copy, field)
		if _, e := DecodeRefundReplace(encode(copy)); e == nil {
			t.Fatalf("missing replacement %s accepted", field)
		}
	}
	body["id"] = "f0000000-0000-4000-8000-000000000001"
	if _, e := DecodeRefundReplace(encode(body)); e == nil {
		t.Fatal("replacement body identity accepted")
	}
	delete(body, "expected_version")
	part["category_id"] = nil
	if _, e := DecodeRefundCreate(encode(body)); e == nil {
		t.Fatal("client category override accepted")
	}
	delete(part, "category_id")
	body["allocations"] = []any{part, map[string]any{"id": "f0000000-0000-4000-8000-000000000006", "original_allocation_id": strings.ToUpper(original), "amount_minor": "1"}}
	if _, e := DecodeRefundCreate(encode(body)); e == nil {
		t.Fatal("duplicate normalized original allocation accepted")
	}
	body["allocations"] = []any{part}
	part["amount_minor"] = "1233"
	value, e := DecodeRefundCreate(encode(body))
	if e != nil {
		t.Fatal(e)
	}
	if refundSum(value) == nil {
		t.Fatal("part sum difference of 1 rounded away")
	}
	part["amount_minor"] = "0"
	if _, e := DecodeRefundCreate(encode(body)); e == nil {
		t.Fatal("zero part accepted")
	}
}

func TestTransactionDeleteWire(t *testing.T) {
	encode := func(v any) []byte { b, _ := json.Marshal(v); return b }
	relatedID := "f0000000-0000-4000-8000-000000000001"
	body := map[string]any{"expected_version": "1", "related_versions": []any{map[string]any{"id": relatedID, "version": "2"}}}
	if _, e := DecodeTransactionDelete(encode(body)); e != nil {
		t.Fatal(e)
	}
	invalidBodies := []any{
		map[string]any{"expected_version": "1"},
		map[string]any{"expected_version": "1", "related_versions": nil},
		map[string]any{"expected_version": "0", "related_versions": []any{}},
		map[string]any{"expected_version": "1", "related_versions": []any{map[string]any{"id": relatedID, "version": "2", "extra": true}}},
		map[string]any{"expected_version": "1", "related_versions": []any{map[string]any{"id": relatedID, "version": "2"}, map[string]any{"id": strings.ToUpper(relatedID), "version": "3"}}},
	}
	for _, bad := range invalidBodies {
		if _, e := DecodeTransactionDelete(encode(bad)); e == nil {
			t.Fatal("invalid delete body accepted")
		}
	}
}
