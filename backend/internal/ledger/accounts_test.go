package ledger

import (
	"encoding/json"
	"testing"
)

func TestAccountInputShape(t *testing.T) {
	base := map[string]any{"id": "c0000000-0000-4000-8000-000000000001", "name": "Wallet", "type": "cash", "currency": "EUR", "opened_at": "2026-01-01T00:00:00Z", "occurred_timezone": "Europe/Nicosia", "opening_balance_minor": "0"}
	encode := func(v map[string]any) []byte { b, _ := json.Marshal(v); return b }
	if _, e := DecodeAccountCreate(encode(base)); e != nil {
		t.Fatal(e)
	}
	for field := range base {
		copy := map[string]any{}
		for k, v := range base {
			copy[k] = v
		}
		delete(copy, field)
		if _, e := DecodeAccountCreate(encode(copy)); e == nil {
			t.Fatalf("missing %s accepted", field)
		}
		copy[field] = nil
		if _, e := DecodeAccountCreate(encode(copy)); e == nil {
			t.Fatalf("null %s accepted", field)
		}
	}
	for field, values := range map[string][]any{"id": {"bad"}, "name": {"", "  "}, "type": {"crypto"}, "currency": {"eur", "EU"}, "opened_at": {"2026-01-01T00:00:00+00:00", "bad"}, "occurred_timezone": {"Local", "Missing/Zone"}, "opening_balance_minor": {"-0", 1, "9000000000000001"}} {
		for _, value := range values {
			copy := map[string]any{}
			for k, v := range base {
				copy[k] = v
			}
			copy[field] = value
			if _, e := DecodeAccountCreate(encode(copy)); e == nil {
				t.Fatalf("invalid %s accepted", field)
			}
		}
	}
	base["unknown"] = true
	if _, e := DecodeAccountCreate(encode(base)); e == nil {
		t.Fatal("unknown accepted")
	}
	valid := `{"expected_version":"1","name":"Wallet","type":"cash","archived":false}`
	if _, e := DecodeAccountUpdate([]byte(valid)); e != nil {
		t.Fatal(e)
	}
	for _, body := range []string{`{"expected_version":"1","name":"Wallet","type":"cash"}`, `{"expected_version":"9223372036854775808","name":"Wallet","type":"cash","archived":false}`, `{"expected_version":"1","name":"Wallet","type":"cash","archived":null}`, `{"expected_version":"1","name":"Wallet","type":"cash","archived":false,"currency":"USD"}`, `{"expected_version":"1","name":"Wallet","type":"cash","archived":false,"archived":true}`} {
		if _, e := DecodeAccountUpdate([]byte(body)); e == nil {
			t.Fatal("invalid update accepted")
		}
	}
}

func TestInstantContract(t *testing.T) {
	for _, s := range []string{"0000-01-01T00:00:00Z", "2026-01-01T0:00:00Z", "2026-01-01T00:00:00,1Z", "2026-01-01T00:00:00+00:00", "2026-02-30T00:00:00Z"} {
		if _, e := ParseInstant(s); e == nil {
			t.Fatalf("invalid RFC3339 accepted: %s", s)
		}
	}
	for _, s := range []string{"0001-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00.123456789Z"} {
		if _, e := ParseInstant(s); e != nil {
			t.Fatal(e)
		}
	}
}
