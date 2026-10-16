package ledger

import (
	"testing"
	"time"
)

func TestBudgetCalendarAndProgress(t *testing.T) {
	from, to, err := BudgetPeriod("2026-03", "Europe/Berlin")
	if err != nil {
		t.Fatal(err)
	}
	if from.Format(time.RFC3339) != "2026-02-28T23:00:00Z" || to.Format(time.RFC3339) != "2026-03-31T22:00:00Z" {
		t.Fatal("DST month boundary incorrect", from, to)
	}
	for _, month := range []string{"2026-3", "2026-13", "0001-01", "2026-03-01"} {
		if _, _, err := BudgetPeriod(month, "UTC"); err == nil {
			t.Fatal("invalid month accepted", month)
		}
	}
	if _, _, err := BudgetPeriod("2026-03", "Unknown/Zone"); err == nil {
		t.Fatal("invalid timezone accepted")
	}
	for _, v := range []struct{ limit, spent, remaining, over, warning string }{
		{"100", "79", "21", "0", "normal"}, {"100", "80", "20", "0", "approaching"}, {"100", "100", "0", "0", "exceeded"}, {"100", "101", "-1", "1", "exceeded"},
		{"100", "-20", "120", "0", "normal"}, {"9000000000000000", "18000000000000000", "-9000000000000000", "9000000000000000", "exceeded"},
	} {
		r, o, w := BudgetProgress(v.limit, v.spent)
		if r != v.remaining || o != v.over || w != v.warning {
			t.Fatal("incorrect exact budget threshold", v, r, o, w)
		}
	}
}
