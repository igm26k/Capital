package ledger

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"context"
	"encoding/json"
	"errors"
	"math"
	"sort"
	"strconv"
	"strings"

	"github.com/jackc/pgx/v5"
)

type BulkItem struct {
	ID              string `json:"id"`
	ExpectedVersion string `json:"expected_version"`
}
type BulkClassificationInput struct {
	Items      []BulkItem `json:"items"`
	CategoryID *string    `json:"category_id"`
	TagIDs     []string   `json:"tag_ids"`
}

func DecodeBulkClassification(body []byte) (BulkClassificationInput, error) {
	var input BulkClassificationInput
	if e := decodeObject(body, &input, []string{"items", "category_id", "tag_ids"}, []string{"category_id"}); e != nil {
		return input, e
	}
	if len(input.Items) < 1 || len(input.Items) > 100 || len(input.TagIDs) > 50 {
		return input, invalid()
	}
	if input.CategoryID != nil {
		id := strings.ToLower(*input.CategoryID)
		if !uuid(id) {
			return input, invalid()
		}
		input.CategoryID = &id
	}
	seen := map[string]bool{}
	for i, id := range input.TagIDs {
		id = strings.ToLower(id)
		if !uuid(id) || seen[id] {
			return input, invalid()
		}
		seen[id] = true
		input.TagIDs[i] = id
	}
	var raw struct {
		Items []json.RawMessage `json:"items"`
	}
	if e := json.Unmarshal(body, &raw); e != nil {
		return input, invalid()
	}
	seen = map[string]bool{}
	for i, body := range raw.Items {
		var item BulkItem
		if e := decodeFields(body, &item, "id", "expected_version"); e != nil {
			return input, e
		}
		item.ID = strings.ToLower(item.ID)
		if !uuid(item.ID) || seen[item.ID] {
			return input, invalid()
		}
		if _, e := version(item.ExpectedVersion); e != nil {
			return input, e
		}
		seen[item.ID] = true
		input.Items[i] = item
	}
	return input, nil
}

// Validate the complete package before writes. Head serialization and ordered
// account/transaction locks protect classification and refund dependencies.
func ClassifyTransactions(workspace string, input BulkClassificationInput) commands.Command {
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		body, e := json.Marshal(input)
		if e != nil {
			return commands.Mutation{}, e
		}
		desired, e := DecodeBulkClassification(body)
		if e != nil {
			return commands.Mutation{}, e
		}
		expected := map[string]string{}
		ids := []string{}
		for _, item := range desired.Items {
			ids = append(ids, item.ID)
			expected[item.ID] = item.ExpectedVersion
		}
		sort.Strings(ids)
		rows, e := tx.Query(ctx, `SELECT DISTINCT e.account_id::text FROM transactions t JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id) WHERE t.workspace_id=$1 AND t.id=ANY($2::uuid[]) AND t.deleted_at IS NULL ORDER BY e.account_id::text`, workspace, ids)
		if e != nil {
			return commands.Mutation{}, e
		}
		accountIDs := []string{}
		for rows.Next() {
			var id string
			if e = rows.Scan(&id); e != nil {
				rows.Close()
				return commands.Mutation{}, e
			}
			accountIDs = append(accountIDs, id)
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return commands.Mutation{}, e
		}
		accounts, e := lockAccounts(ctx, tx, workspace, accountIDs...)
		if e != nil {
			return commands.Mutation{}, e
		}
		versions := map[string]int64{}
		for _, id := range ids {
			var current int64
			var kind string
			var parent *string
			var entries, parts int
			e = tx.QueryRow(ctx, `SELECT t.version,t.kind,t.parent_transaction_id::text,(SELECT count(*) FROM entries e WHERE (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id)),(SELECT count(*) FROM allocations a WHERE (a.workspace_id,a.transaction_id)=(t.workspace_id,t.id)) FROM transactions t WHERE t.workspace_id=$1 AND t.id=$2 AND t.deleted_at IS NULL FOR UPDATE`, workspace, id).Scan(&current, &kind, &parent, &entries, &parts)
			if errors.Is(e, pgx.ErrNoRows) {
				return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
			}
			if e != nil {
				return commands.Mutation{}, e
			}
			requested, _ := version(expected[id])
			if requested != current {
				return commands.Mutation{}, transactionVersionConflict(id, current)
			}
			if current == math.MaxInt64 {
				return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
			}
			if (kind != "expense" && kind != "income") || parent != nil || entries != 1 || parts != 1 {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
			var refunds bool
			if e = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM transactions WHERE workspace_id=$1 AND parent_transaction_id=$2 AND kind='refund' AND deleted_at IS NULL)`, workspace, id).Scan(&refunds); e != nil {
				return commands.Mutation{}, e
			}
			if refunds {
				return commands.Mutation{}, domainReject(409, "dependent_transactions")
			}
			history, e := readBasicHistory(ctx, tx, workspace, id)
			if e != nil {
				return commands.Mutation{}, e
			}
			var partID string
			for allocationID := range history.Parts {
				partID = allocationID
			}
			if e = checkReferences(ctx, tx, workspace, BasicInput{Allocations: []AllocationInput{{ID: partID, CategoryID: desired.CategoryID}}, TagIDs: desired.TagIDs}, history.Parts, history.Tags); e != nil {
				return commands.Mutation{}, e
			}
			versions[id] = current
		}
		for _, account := range accounts {
			if account.Archived {
				return commands.Mutation{}, domainReject(422, "account_archived")
			}
		}
		changes := []commands.Change{}
		for _, id := range ids {
			if _, e = tx.Exec(ctx, `UPDATE allocations SET category_id=$3 WHERE workspace_id=$1 AND transaction_id=$2`, workspace, id, desired.CategoryID); e != nil {
				return commands.Mutation{}, e
			}
			if e = replaceTransactionTags(ctx, tx, workspace, id, desired.TagIDs); e != nil {
				return commands.Mutation{}, e
			}
			if _, e = tx.Exec(ctx, `UPDATE transactions SET version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, id); e != nil {
				return commands.Mutation{}, e
			}
			data, e := ReadTransaction(ctx, tx, workspace, id)
			if e != nil {
				return commands.Mutation{}, e
			}
			changes = append(changes, commands.Change{EntityType: "transaction", ID: id, Version: strconv.FormatInt(versions[id]+1, 10), Operation: "upsert", Data: data})
		}
		return commands.Mutation{Status: 200, Changes: changes}, nil
	}
}
