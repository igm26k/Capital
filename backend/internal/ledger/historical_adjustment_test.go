package ledger

import (
	"encoding/json"
	"testing"
)

func TestHistoricalAdjustmentWire(t *testing.T) {
	body := map[string]any{"id": "a0000000-0000-4000-8000-000000000001", "kind": "adjustment", "account_id": "a0000000-0000-4000-8000-000000000002", "amount_minor": "-1234", "occurred_at": "2025-08-12T15:00:00Z", "occurred_timezone": "UTC", "reason": "Wallet correction", "note": "Original metadata", "payee": ""}
	decode := func(b map[string]any) error {
		raw, _ := json.Marshal(b)
		_, err := DecodeHistoricalAdjustment(raw)
		return err
	}
	if err := decode(body); err != nil {
		t.Fatal(err)
	}
	body["amount_minor"] = "1234"
	if err := decode(body); err != nil {
		t.Fatal(err)
	}
	for _, amount := range []any{"0", "-0", "01", "1.5", "9000000000000001", "-9000000000000001", 1234} {
		body["amount_minor"] = amount
		if decode(body) == nil {
			t.Fatal("invalid signed movement accepted", amount)
		}
	}
	body["amount_minor"] = "-1234"
	for field := range body {
		copy := map[string]any{}
		for k, v := range body {
			if k != field {
				copy[k] = v
			}
		}
		if decode(copy) == nil {
			t.Fatal("missing field accepted", field)
		}
	}
	for field, value := range map[string]any{"kind": "income", "reason": " ", "account_id": "bad", "occurred_at": "2025-02-30T00:00:00Z", "occurred_timezone": "bad-zone"} {
		original := body[field]
		body[field] = value
		if decode(body) == nil {
			t.Fatal("invalid field accepted", field)
		}
		body[field] = original
	}
	body["target_balance_minor"] = "0"
	if decode(body) == nil {
		t.Fatal("balance replacement accepted as historical delta")
	}
}
