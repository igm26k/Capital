// Package banking contains conservative source reconciliation. Suggestions never authorize mutations.
package banking

import (
	"errors"
	"sort"
	"strings"
	"time"
	"unicode"
)

// Observation is normalized structured data. External IDs must come from one connection's namespace.
type Observation struct {
	Connection, ExternalID, PendingID, Account, Currency, Kind, Amount, Merchant, Status string
	ObservedAt                                                                           time.Time
}
type Existing struct {
	ID, Version string
	Observation
	Deleted bool
}
type Decision struct {
	// linked means the source explicitly identifies an existing transaction. The caller must still
	// check current versions, access, financial invariants and persist its receipt atomically.
	Kind, TransactionID, ExpectedVersion, Reason string
	Candidates                                   []string
}

func validate(o Observation) error {
	if o.Connection == "" || len(o.Connection) > 200 || len(o.ExternalID) > 200 || len(o.PendingID) > 200 || o.Account == "" || len(o.Currency) != 3 ||
		(o.Kind != "expense" && o.Kind != "income") || (o.Status != "pending" && o.Status != "posted" && o.Status != "cancelled") || o.ObservedAt.IsZero() || len(o.Merchant) > 200 {
		return errors.New("invalid normalized observation")
	}
	if len(o.Amount) < 1 || len(o.Amount) > 16 || o.Amount[0] == '0' {
		return errors.New("invalid minor units")
	}
	var amount int64
	for _, c := range o.Amount {
		if c < '0' || c > '9' {
			return errors.New("invalid minor units")
		}
		amount = amount*10 + int64(c-'0')
	}
	if amount > 9000000000000000 {
		return errors.New("minor units out of range")
	}
	for _, c := range o.Currency {
		if c < 'A' || c > 'Z' {
			return errors.New("invalid currency")
		}
	}
	return nil
}
func merchant(value string) string {
	return strings.ToLower(strings.Join(strings.FieldsFunc(value, unicode.IsSpace), " "))
}

// Reconcile never automatically merges on amount/date/merchant. Stable source IDs have precedence;
// an explicit pending link can change amount/time while retaining the original transaction identity.
func Reconcile(in Observation, existing []Existing) (Decision, error) {
	if len(existing) > 10000 {
		return Decision{}, errors.New("too many reconciliation candidates")
	}
	if err := validate(in); err != nil {
		return Decision{}, err
	}
	exact := []Existing{}
	for _, old := range existing {
		if old.Connection != in.Connection {
			continue
		}
		sameID := in.ExternalID != "" && old.ExternalID == in.ExternalID
		pendingLink := in.PendingID != "" && old.ExternalID == in.PendingID
		if sameID || pendingLink {
			exact = append(exact, old)
		}
	}
	if len(exact) > 1 {
		return Decision{Kind: "review", Reason: "ambiguous_source_identity", Candidates: ids(exact)}, nil
	}
	if len(exact) == 1 {
		old := exact[0]
		if old.Deleted {
			return Decision{Kind: "review", Reason: "previously_deleted", Candidates: ids(exact)}, nil
		}
		if old.Account != in.Account || old.Currency != in.Currency || old.Kind != in.Kind {
			return Decision{Kind: "review", Reason: "source_identity_conflict", Candidates: ids(exact)}, nil
		}
		if in.PendingID != "" && old.ExternalID == in.PendingID && old.Status != "pending" && old.ExternalID != in.ExternalID {
			return Decision{Kind: "review", Reason: "pending_link_already_resolved", Candidates: ids(exact)}, nil
		}
		if old.Status == "posted" && in.Status == "pending" || old.Status == "cancelled" && in.Status != "cancelled" {
			return Decision{Kind: "review", Reason: "status_regression", Candidates: ids(exact)}, nil
		}
		return Decision{Kind: "linked", TransactionID: old.ID, ExpectedVersion: old.Version, Reason: "explicit_source_identity", Candidates: []string{}}, nil
	}
	if in.Status == "cancelled" || in.PendingID != "" {
		return Decision{Kind: "review", Reason: "missing_source_link", Candidates: []string{}}, nil
	}
	candidates := []Existing{}
	normalized := merchant(in.Merchant)
	if normalized != "" {
		for _, old := range existing {
			if old.Deleted || old.Account != in.Account || old.Currency != in.Currency || old.Amount != in.Amount || old.Kind != in.Kind || old.Status == "cancelled" || merchant(old.Merchant) != normalized {
				continue
			}
			delta := old.ObservedAt.Sub(in.ObservedAt)
			if delta >= -72*time.Hour && delta <= 72*time.Hour {
				candidates = append(candidates, old)
			}
		}
	}
	if len(candidates) > 0 {
		return Decision{Kind: "review", Reason: "possible_duplicate", Candidates: ids(candidates)}, nil
	}
	return Decision{Kind: "new", Reason: "no_match", Candidates: []string{}}, nil
}
func ids(items []Existing) []string {
	result := []string{}
	seen := map[string]bool{}
	for _, item := range items {
		if !seen[item.ID] {
			seen[item.ID] = true
			result = append(result, item.ID)
		}
	}
	// Stable proposals keep UI previews deterministic across database row order.
	sort.Strings(result)
	return result
}
