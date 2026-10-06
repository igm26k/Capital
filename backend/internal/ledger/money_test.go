package ledger

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func TestMoneyContract(t *testing.T) {
	for _, s := range []string{"0", "1", "-1", "9000000000000000", "-9000000000000000"} {
		m, err := ParseMoney(s)
		if err != nil || m.String() != s {
			t.Fatalf("%q: %v", s, err)
		}
		b, err := json.Marshal(m)
		if err != nil || string(b) != `"`+s+`"` {
			t.Fatalf("JSON %q: %s %v", s, b, err)
		}
	}
	for _, s := range []string{"", "-0", "00", "01", "-01", "+1", " 1", "1\n", "1.00", "1e3", "1,000", "١", "--1"} {
		if _, err := ParseMoney(s); !errors.Is(err, ErrMoneyFormat) {
			t.Fatalf("format %q: %v", s, err)
		}
	}
	for _, s := range []string{"9000000000000001", "-9000000000000001", "9223372036854775808", strings.Repeat("9", 100)} {
		if _, err := ParseMoney(s); !errors.Is(err, ErrMoneyRange) {
			t.Fatalf("range %q: %v", s, err)
		}
	}
	for _, s := range []string{"0", "-1"} {
		if _, err := ParsePositiveMoney(s); !errors.Is(err, ErrMoneyRange) {
			t.Fatalf("positive %q: %v", s, err)
		}
	}
}

func TestMoneyExactCancellationAndBalances(t *testing.T) {
	max, _ := NewMoney(MaxMinor)
	values := make([]Money, 0, 4096)
	// Partial sums exceed int64 before cancellation; final result is exact.
	for range 2048 {
		values = append(values, max)
	}
	for range 2048 {
		values = append(values, max.Negate())
	}
	sum, err := SumMoney(values...)
	if err != nil || sum.Minor() != 0 {
		t.Fatalf("cancellation: %v %v", sum, err)
	}
	one, _ := NewMoney(1)
	if _, err := SumMoney(max, one); !errors.Is(err, ErrMoneyRange) {
		t.Fatal(err)
	}
	opening, _ := NewMoney(100000)
	expense, _ := NewMoney(-1234)
	pending, _ := NewMoney(-100)
	p, d, projected, err := Balances([]Money{opening, expense}, []Money{pending})
	if err != nil || p.String() != "98766" || d.String() != "-100" || projected.String() != "98666" {
		t.Fatalf("balances: %v %v %v %v", p, d, projected, err)
	}
	// All three limits apply even if posted and pending cancel in projected.
	if _, _, _, err := Balances([]Money{max, one}, []Money{one.Negate()}); !errors.Is(err, ErrMoneyRange) {
		t.Fatal(err)
	}
	if _, _, _, err := Balances(nil, []Money{max, one}); !errors.Is(err, ErrMoneyRange) {
		t.Fatal(err)
	}
	if _, _, _, err := Balances([]Money{max}, []Money{one}); !errors.Is(err, ErrMoneyRange) {
		t.Fatal(err)
	}
}

func TestAggregateMoney(t *testing.T) {
	max := strings.Repeat("9", 40)
	m, err := ParseAggregateMoney(max)
	if err != nil {
		t.Fatal(err)
	}
	opposite, _ := ParseAggregateMoney("-" + max)
	sum, err := SumAggregateMoney(m, opposite)
	if err != nil || sum.String() != "0" {
		t.Fatalf("cancellation: %v %v", sum, err)
	}
	one, _ := ParseAggregateMoney("1")
	if _, err := SumAggregateMoney(m, one); !errors.Is(err, ErrMoneyRange) {
		t.Fatal(err)
	}
	if _, err := ParseAggregateMoney(strings.Repeat("9", 41)); !errors.Is(err, ErrMoneyRange) {
		t.Fatal(err)
	}
	if _, err := ParseAggregateMoney("-0"); !errors.Is(err, ErrMoneyFormat) {
		t.Fatal(err)
	}
	b, _ := json.Marshal(AggregateMoney{})
	if string(b) != `"0"` {
		t.Fatalf("zero: %s", b)
	}
}
