package ledger

import (
	"accounting/backend/internal/identity"
	"context"
	"encoding/json"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

type StoredRule struct {
	RuleInput
	ID      string `json:"id"`
	Version string `json:"version"`
}

// Rules only suggest classification for unclassified, independent single-part transactions.
// Preview has no writes. Applying a batch uses the existing financial CommandRunner and versions.
type RuleBatch struct {
	Items      []BulkItem `json:"items"`
	CategoryID *string    `json:"category_id"`
	TagIDs     []string   `json:"tag_ids"`
}

func ReadRulePreview(ctx context.Context, tx pgx.Tx, workspace string, from, to time.Time) ([]RuleBatch, bool, error) {
	if !from.Before(to) || to.Sub(from) > 366*24*time.Hour {
		return nil, false, &identity.Error{Status: 400, Code: "bad_request"}
	}
	rawRules, err := ListPlanning(ctx, tx, workspace, "rule")
	if err != nil {
		return nil, false, err
	}
	rules := []StoredRule{}
	for _, raw := range rawRules {
		var rule StoredRule
		if err := json.Unmarshal(raw, &rule); err != nil {
			return nil, false, err
		}
		if rule.Enabled {
			rules = append(rules, rule)
		}
	}
	sort.Slice(rules, func(i, j int) bool {
		if rules[i].Priority != rules[j].Priority {
			return rules[i].Priority < rules[j].Priority
		}
		return rules[i].ID < rules[j].ID
	})
	// Match before LIMIT, so nonmatching transactions cannot hide later matches.
	rows, err := tx.Query(ctx, transactionSelect+` WHERE t.workspace_id=$1 AND t.deleted_at IS NULL
 AND t.kind IN ('expense','income') AND t.parent_transaction_id IS NULL AND t.occurred_at>=$2 AND t.occurred_at<$3
 AND (SELECT count(*) FROM entries e WHERE e.workspace_id=t.workspace_id AND e.transaction_id=t.id)=1
 AND (SELECT count(*) FROM allocations p WHERE p.workspace_id=t.workspace_id AND p.transaction_id=t.id)=1
 AND EXISTS(SELECT 1 FROM allocations p WHERE p.workspace_id=t.workspace_id AND p.transaction_id=t.id AND p.category_id IS NULL)
 AND NOT EXISTS(SELECT 1 FROM transactions r WHERE r.workspace_id=t.workspace_id AND r.parent_transaction_id=t.id AND r.kind='refund' AND r.deleted_at IS NULL)
 AND NOT EXISTS(SELECT 1 FROM entries e JOIN accounts a ON (a.workspace_id,a.id)=(e.workspace_id,e.account_id) WHERE e.workspace_id=t.workspace_id AND e.transaction_id=t.id AND a.archived_at IS NOT NULL)
 AND EXISTS(SELECT 1 FROM planning_resources r JOIN categories c ON c.workspace_id=r.workspace_id AND c.id=(r.data->>'category_id')::uuid
 WHERE r.workspace_id=t.workspace_id AND r.kind='rule' AND r.deleted_at IS NULL AND c.archived_at IS NULL AND c.deleted_at IS NULL
 AND (r.data->>'enabled')::boolean AND r.data->>'transaction_kind'=t.kind
 AND strpos(lower(t.payee),lower(r.data->>'payee_contains'))>0 AND strpos(lower(t.note),lower(r.data->>'note_contains'))>0)
 ORDER BY t.occurred_at DESC,t.id DESC LIMIT 101`, workspace, from, to)
	if err != nil {
		return nil, false, err
	}
	items, err := reportRows(rows)
	if err != nil {
		return nil, false, err
	}
	more := len(items) > 100
	if more {
		items = items[:100]
	}
	// Exclude archived target categories from first-match priority resolution too.
	active := map[string]bool{}
	for _, r := range rules {
		var usable bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM categories WHERE workspace_id=$1 AND id=$2 AND archived_at IS NULL AND deleted_at IS NULL)`, workspace, r.CategoryID).Scan(&usable); err != nil {
			return nil, false, err
		}
		active[r.CategoryID] = usable
	}
	batches := []RuleBatch{}
	indices := map[string]int{}
	for _, raw := range items {
		var item struct {
			ID      string   `json:"id"`
			Version string   `json:"version"`
			Kind    string   `json:"kind"`
			Note    string   `json:"note"`
			Payee   string   `json:"payee"`
			Tags    []string `json:"tag_ids"`
		}
		if err := json.Unmarshal(raw, &item); err != nil {
			return nil, false, err
		}
		for _, r := range rules {
			if !active[r.CategoryID] || !RuleMatches(r.RuleInput, item.Kind, item.Payee, item.Note) {
				continue
			}
			tags, _ := json.Marshal(item.Tags)
			key := r.CategoryID + string(tags)
			index, ok := indices[key]
			if !ok {
				index = len(batches)
				indices[key] = index
				category := r.CategoryID
				batches = append(batches, RuleBatch{CategoryID: &category, TagIDs: item.Tags, Items: []BulkItem{}})
			}
			batches[index].Items = append(batches[index].Items, BulkItem{ID: item.ID, ExpectedVersion: item.Version})
			break
		}
	}
	return batches, more, nil
}
func RuleMatches(rule RuleInput, kind, payee, note string) bool {
	return rule.Enabled && rule.Kind == kind && strings.Contains(strings.ToLower(payee), strings.ToLower(rule.Payee)) && strings.Contains(strings.ToLower(note), strings.ToLower(rule.Note))
}

func applyCreateRule(ctx context.Context, tx pgx.Tx, workspace string, input *BasicInput) error {
	if len(input.Allocations) != 1 || input.Allocations[0].CategoryID != nil {
		return nil
	}
	var category string
	err := tx.QueryRow(ctx, `SELECT r.data->>'category_id' FROM planning_resources r JOIN categories c ON c.workspace_id=r.workspace_id AND c.id=(r.data->>'category_id')::uuid
 WHERE r.workspace_id=$1 AND r.kind='rule' AND r.deleted_at IS NULL AND c.deleted_at IS NULL AND c.archived_at IS NULL
 AND (r.data->>'enabled')::boolean AND r.data->>'transaction_kind'=$2
 AND strpos(lower($3),lower(r.data->>'payee_contains'))>0 AND strpos(lower($4),lower(r.data->>'note_contains'))>0
 ORDER BY (r.data->>'priority')::integer,r.id LIMIT 1`, workspace, input.Kind, input.Payee, input.Note).Scan(&category)
	if err == pgx.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	input.Allocations[0].CategoryID = &category
	return nil
}
