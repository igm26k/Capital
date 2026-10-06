package ledger

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestExactTransferRate(t *testing.T) {
	cases := []struct {
		source, target string
		s, d           int
		expected       Rate
	}{
		{"300", "100", 2, 2, Rate{"1", "3"}},
		{"10000", "100", 2, 0, Rate{"1", "1"}},
		{"100", "100000", 0, 3, Rate{"1", "1"}},
		{"9000000000000000", "8999999999999999", 0, 3, Rate{"8999999999999999", "9000000000000000000"}},
	}
	for _, c := range cases {
		rate, e := ExactTransferRate(c.source, c.target, c.s, c.d, nil)
		if e != nil || rate != c.expected {
			t.Fatalf("exact rate got %v %v", rate, e)
		}
	}
	equivalent := Rate{strings.Repeat("3", 40), strings.Repeat("9", 40)}
	rate, e := ExactTransferRate("3", "1", 2, 2, &equivalent)
	if e != nil || rate != (Rate{"1", "3"}) {
		t.Fatal("equivalent 40-digit ratio not reduced")
	}
	for _, r := range []Rate{{"33333333", "100000000"}, {"0", "1"}, {"01", "3"}, {"1", "0"}, {"1", "3.0"}, {strings.Repeat("1", 41), "3"}} {
		if _, e := ExactTransferRate("3", "1", 2, 2, &r); e == nil {
			t.Fatalf("inexact/invalid rate accepted: %v", r)
		}
	}
}
func TestTransferNestedWire(t *testing.T) {
	body := map[string]any{"id": "e0000000-0000-4000-8000-000000000001", "kind": "transfer", "source_account_id": "e0000000-0000-4000-8000-000000000002", "target_account_id": "e0000000-0000-4000-8000-000000000003", "source_amount_minor": "300", "target_amount_minor": "100", "occurred_at": "2026-01-01T00:00:00Z", "occurred_timezone": "UTC", "note": "", "payee": "", "tag_ids": []string{}, "rate": nil, "fee": nil}
	encode := func(v any) []byte { b, _ := json.Marshal(v); return b }
	if _, e := DecodeTransferCreate(encode(body)); e != nil {
		t.Fatal(e)
	}
	for _, field := range []string{"rate", "fee", "tag_ids", "source_amount_minor"} {
		copy := map[string]any{}
		for k, v := range body {
			copy[k] = v
		}
		delete(copy, field)
		if _, e := DecodeTransferCreate(encode(copy)); e == nil {
			t.Fatalf("missing %s accepted", field)
		}
	}
	for _, bad := range []any{map[string]any{"numerator": "1", "denominator": "3", "extra": true}, map[string]any{"numerator": 1, "denominator": "3"}} {
		body["rate"] = bad
		if _, e := DecodeTransferCreate(encode(body)); e == nil {
			t.Fatal("invalid rate shape accepted")
		}
	}
	body["rate"] = nil
	part := map[string]any{"id": "e0000000-0000-4000-8000-000000000005", "category_id": nil, "amount_minor": "10"}
	fee := map[string]any{"id": "e0000000-0000-4000-8000-000000000004", "account_id": body["source_account_id"], "amount_minor": "10", "allocations": []any{part}, "note": "", "tag_ids": []string{}}
	body["fee"] = fee
	if _, e := DecodeTransferCreate(encode(body)); e != nil {
		t.Fatal(e)
	}
	part["unknown"] = true
	if _, e := DecodeTransferCreate(encode(body)); e == nil {
		t.Fatal("unknown fee allocation field accepted")
	}
	delete(part, "unknown")
	delete(part, "category_id")
	if _, e := DecodeTransferCreate(encode(body)); e == nil {
		t.Fatal("missing fee category nullable field accepted")
	}
}

func TestTransferReplaceWire(t *testing.T) {
	body := map[string]any{"kind": "transfer", "source_account_id": "e0000000-0000-4000-8000-000000000002", "target_account_id": "e0000000-0000-4000-8000-000000000003", "source_amount_minor": "300", "target_amount_minor": "100", "occurred_at": "2026-01-01T00:00:00Z", "occurred_timezone": "UTC", "note": "", "payee": "", "tag_ids": []string{}, "rate": nil, "fee": nil, "expected_version": "1", "expected_fee_version": nil}
	encode := func(v any) []byte { b, _ := json.Marshal(v); return b }
	if _, e := DecodeTransferReplace(encode(body)); e != nil {
		t.Fatal(e)
	}
	for field := range body {
		copy := map[string]any{}
		for k, v := range body {
			copy[k] = v
		}
		delete(copy, field)
		if _, e := DecodeTransferReplace(encode(copy)); e == nil {
			t.Fatalf("missing PUT field %s accepted", field)
		}
	}
	body["id"] = "e0000000-0000-4000-8000-000000000001"
	if _, e := DecodeTransferReplace(encode(body)); e == nil {
		t.Fatal("body identity accepted for PUT")
	}
	delete(body, "id")
	body["expected_fee_version"] = "01"
	if _, e := DecodeTransferReplace(encode(body)); e == nil {
		t.Fatal("noncanonical fee version accepted")
	}
	body["expected_fee_version"] = nil
	body["expected_version"] = 0
	if _, e := DecodeTransferReplace(encode(body)); e == nil {
		t.Fatal("numeric version accepted")
	}
}
