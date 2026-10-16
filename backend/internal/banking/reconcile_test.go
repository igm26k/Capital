package banking

import (
	"testing"
	"time"
)

func observation() Observation {
	return Observation{Connection: "bank-A", ExternalID: "new", Account: "account", Currency: "RUB", Kind: "expense", Amount: "123456", Merchant: "SHOP", Status: "posted", ObservedAt: time.Date(2026, 10, 16, 12, 0, 0, 0, time.UTC)}
}
func TestSourceIdentityHasPrecedenceAndPreservesTransactionVersion(t *testing.T) {
	in := observation()
	old := Existing{ID: "tx", Version: "7", Observation: in}
	old.Amount = "100"
	old.Status = "pending"
	got, err := Reconcile(in, []Existing{old})
	if err != nil || got.Kind != "linked" || got.TransactionID != "tx" || got.ExpectedVersion != "7" {
		t.Fatal(got, err)
	}
}
func TestExplicitDifferentPendingID(t *testing.T) {
	in := observation()
	in.PendingID = "old-bank-id"
	old := Existing{ID: "tx", Version: "3", Observation: in}
	old.ExternalID = "old-bank-id"
	old.Status = "pending"
	old.Amount = "100"
	got, err := Reconcile(in, []Existing{old})
	if err != nil || got.Kind != "linked" {
		t.Fatal(got, err)
	}
	old.Status = "posted"
	got, _ = Reconcile(in, []Existing{old})
	if got.Kind != "review" {
		t.Fatal("resolved pending reused", got)
	}
}
func TestIdenticalPurchasesOnlyOfferReviewNeverMerge(t *testing.T) {
	in := observation()
	old := Existing{ID: "one", Version: "1", Observation: in}
	old.ExternalID = "another"
	got, _ := Reconcile(in, []Existing{old})
	if got.Kind != "review" || got.Reason != "possible_duplicate" {
		t.Fatal(got)
	}
	old.Merchant = ""
	in.Merchant = ""
	got, _ = Reconcile(in, []Existing{old})
	if got.Kind != "new" {
		t.Fatal("amount and date merged", got)
	}
}
func TestIdentityIsScopedToConnectionAndAccount(t *testing.T) {
	in := observation()
	old := Existing{ID: "tx", Version: "1", Observation: in}
	old.Connection = "other"
	old.Merchant = ""
	got, _ := Reconcile(in, []Existing{old})
	if got.Kind != "new" {
		t.Fatal(got)
	}
	old.Connection = in.Connection
	old.Account = "other"
	got, _ = Reconcile(in, []Existing{old})
	if got.Reason != "source_identity_conflict" {
		t.Fatal(got)
	}
	old.Account = in.Account
	old.Currency = "USD"
	got, _ = Reconcile(in, []Existing{old})
	if got.Kind != "review" {
		t.Fatal(got)
	}
}
func TestDeletedCancelledAndStatusRegressionNeverRevive(t *testing.T) {
	in := observation()
	old := Existing{ID: "tx", Version: "1", Observation: in, Deleted: true}
	got, _ := Reconcile(in, []Existing{old})
	if got.Reason != "previously_deleted" {
		t.Fatal(got)
	}
	old.Deleted = false
	old.Status = "cancelled"
	got, _ = Reconcile(in, []Existing{old})
	if got.Reason != "status_regression" {
		t.Fatal(got)
	}
	old.Status = "posted"
	in.Status = "pending"
	got, _ = Reconcile(in, []Existing{old})
	if got.Kind != "review" {
		t.Fatal(got)
	}
}
func TestAmbiguousAndUnknownReferencesRequireReview(t *testing.T) {
	in := observation()
	a := Existing{ID: "a", Version: "1", Observation: in}
	b := a
	b.ID = "b"
	got, _ := Reconcile(in, []Existing{b, a})
	if got.Reason != "ambiguous_source_identity" || len(got.Candidates) != 2 || got.Candidates[0] != "a" {
		t.Fatal(got)
	}
	in.PendingID = "missing"
	in.ExternalID = "fresh"
	got, _ = Reconcile(in, nil)
	if got.Reason != "missing_source_link" {
		t.Fatal(got)
	}
	in.PendingID = ""
	in.Status = "cancelled"
	got, _ = Reconcile(in, nil)
	if got.Kind != "review" {
		t.Fatal(got)
	}
}
func TestHeuristicUsesMerchantAndBoundedTime(t *testing.T) {
	in := observation()
	old := Existing{ID: "tx", Version: "1", Observation: in}
	old.ExternalID = "other"
	old.Merchant = " shop  "
	old.ObservedAt = in.ObservedAt.Add(72 * time.Hour)
	got, _ := Reconcile(in, []Existing{old})
	if got.Kind != "review" {
		t.Fatal(got)
	}
	old.ObservedAt = old.ObservedAt.Add(time.Second)
	got, _ = Reconcile(in, []Existing{old})
	if got.Kind != "new" {
		t.Fatal(got)
	}
	old.ObservedAt = in.ObservedAt
	old.Amount = "123455"
	got, _ = Reconcile(in, []Existing{old})
	if got.Kind != "new" {
		t.Fatal(got)
	}
}
func TestRejectMalformedNormalizedValues(t *testing.T) {
	for _, amount := range []string{"0", "01", "-1", "1.00", "9000000000000001", "99999999999999999", ""} {
		in := observation()
		in.Amount = amount
		if _, err := Reconcile(in, nil); err == nil {
			t.Fatal(amount)
		}
	}
	for _, status := range []string{"refund", "unknown"} {
		in := observation()
		in.Status = status
		if _, err := Reconcile(in, nil); err == nil {
			t.Fatal(status)
		}
	}
}
