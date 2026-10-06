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
	"time"

	"github.com/jackc/pgx/v5"
)

type RelatedVersion struct {
	ID      string `json:"id"`
	Version string `json:"version"`
}
type TransactionDeleteInput struct {
	ExpectedVersion string           `json:"expected_version"`
	Related         []RelatedVersion `json:"related_versions"`
}

func DecodeTransactionDelete(body []byte) (TransactionDeleteInput, error) {
	var input TransactionDeleteInput
	if e := decodeFields(body, &input, "expected_version", "related_versions"); e != nil {
		return input, e
	}
	if _, e := version(input.ExpectedVersion); e != nil {
		return input, e
	}
	if len(input.Related) > 4 {
		return input, invalid()
	}
	var raw struct {
		Related []json.RawMessage `json:"related_versions"`
	}
	if e := json.Unmarshal(body, &raw); e != nil {
		return input, invalid()
	}
	seen := map[string]bool{}
	for i, b := range raw.Related {
		var related RelatedVersion
		if e := decodeFields(b, &related, "id", "version"); e != nil {
			return input, e
		}
		related.ID = strings.ToLower(related.ID)
		if !uuid(related.ID) || seen[related.ID] {
			return input, invalid()
		}
		if _, e := version(related.Version); e != nil {
			return input, e
		}
		seen[related.ID] = true
		input.Related[i] = related
	}
	return input, nil
}

// The workspace head held by CommandRunner serializes dependency discovery.
// Account locks precede ordered transaction locks for every affected aggregate.
func DeleteTransaction(workspace, id string, input TransactionDeleteInput) commands.Command {
	id = strings.ToLower(id)
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		if !uuid(id) {
			return commands.Mutation{}, &identity.Error{Status: 400, Code: "bad_request"}
		}
		body, e := json.Marshal(input)
		if e != nil {
			return commands.Mutation{}, e
		}
		validated, e := DecodeTransactionDelete(body)
		if e != nil {
			return commands.Mutation{}, e
		}
		type transaction struct {
			kind    string
			parent  *string
			version int64
			deleted *time.Time
		}
		entities := map[string]transaction{}
		load := func(entityID string) error {
			var entity transaction
			e := tx.QueryRow(ctx, `SELECT kind,parent_transaction_id::text,version,deleted_at FROM transactions WHERE workspace_id=$1 AND id=$2`, workspace, entityID).Scan(&entity.kind, &entity.parent, &entity.version, &entity.deleted)
			if errors.Is(e, pgx.ErrNoRows) {
				return &identity.Error{Status: 404, Code: "not_found"}
			}
			if e != nil {
				return e
			}
			if entity.deleted != nil {
				return domainReject(409, "validation_error")
			}
			entities[entityID] = entity
			return nil
		}
		if e = load(id); e != nil {
			return commands.Mutation{}, e
		}
		root := entities[id]
		if root.kind == "opening" {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		deleted := map[string]bool{id: true}
		if root.kind == "transfer" {
			var feeID string
			e = tx.QueryRow(ctx, `SELECT id::text FROM transactions WHERE workspace_id=$1 AND parent_transaction_id=$2 AND kind='expense' AND deleted_at IS NULL`, workspace, id).Scan(&feeID)
			if e != nil && !errors.Is(e, pgx.ErrNoRows) {
				return commands.Mutation{}, e
			}
			if e == nil {
				if e = load(feeID); e != nil {
					return commands.Mutation{}, e
				}
				deleted[feeID] = true
			}
		}
		if root.parent != nil {
			if e = load(*root.parent); e != nil {
				return commands.Mutation{}, e
			}
			parent := entities[*root.parent]
			if root.kind == "refund" && parent.parent != nil {
				if e = load(*parent.parent); e != nil {
					return commands.Mutation{}, e
				}
			}
		}
		ids := []string{}
		for entityID := range entities {
			ids = append(ids, entityID)
		}
		sort.Strings(ids)
		// Only accounts whose entries are removed are financially affected. Parent
		// accounts are also locked and checked so archive cannot bypass dependencies.
		rows, e := tx.Query(ctx, `SELECT DISTINCT account_id::text FROM entries WHERE workspace_id=$1 AND transaction_id=ANY($2::uuid[]) ORDER BY account_id::text`, workspace, ids)
		if e != nil {
			return commands.Mutation{}, e
		}
		accountIDs := []string{}
		for rows.Next() {
			var accountID string
			if e = rows.Scan(&accountID); e != nil {
				rows.Close()
				return commands.Mutation{}, e
			}
			accountIDs = append(accountIDs, accountID)
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
		expected := map[string]string{id: validated.ExpectedVersion}
		for _, related := range validated.Related {
			if related.ID == id {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
			expected[related.ID] = related.Version
		}
		if len(expected) != len(entities) {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		for _, entityID := range ids {
			var current int64
			if e = tx.QueryRow(ctx, `SELECT version FROM transactions WHERE workspace_id=$1 AND id=$2 FOR UPDATE`, workspace, entityID).Scan(&current); e != nil {
				return commands.Mutation{}, e
			}
			requested, exists := expected[entityID]
			if !exists {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
			v, _ := version(requested)
			if v != current {
				return commands.Mutation{}, transactionVersionConflict(entityID, current)
			}
			if current == math.MaxInt64 {
				return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
			}
		}
		for _, accountID := range accountIDs {
			if accounts[accountID].Archived {
				return commands.Mutation{}, domainReject(422, "account_archived")
			}
		}
		deletedIDs := []string{}
		for entityID := range deleted {
			deletedIDs = append(deletedIDs, entityID)
			var dependent bool
			if e = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM transactions WHERE workspace_id=$1 AND parent_transaction_id=$2 AND kind='refund' AND deleted_at IS NULL AND status IN ('posted','pending'))`, workspace, entityID).Scan(&dependent); e != nil {
				return commands.Mutation{}, e
			}
			if dependent {
				return commands.Mutation{}, domainReject(409, "dependent_transactions")
			}
		}
		sort.Strings(deletedIDs)
		rows, e = tx.Query(ctx, `SELECT DISTINCT account_id::text FROM entries WHERE workspace_id=$1 AND transaction_id=ANY($2::uuid[]) ORDER BY account_id::text`, workspace, deletedIDs)
		if e != nil {
			return commands.Mutation{}, e
		}
		affected := []string{}
		for rows.Next() {
			var accountID string
			if e = rows.Scan(&accountID); e != nil {
				rows.Close()
				return commands.Mutation{}, e
			}
			affected = append(affected, accountID)
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return commands.Mutation{}, e
		}
		changes := []commands.Change{}
		for _, entityID := range deletedIDs {
			change, e := softDeleteTransaction(ctx, tx, workspace, entityID, entities[entityID].version)
			if e != nil {
				return commands.Mutation{}, e
			}
			changes = append(changes, change)
		}
		for _, accountID := range affected {
			change, e := bumpAccount(ctx, tx, workspace, accounts[accountID])
			if e != nil {
				return commands.Mutation{}, e
			}
			changes = append(changes, change)
		}
		for _, entityID := range ids {
			if deleted[entityID] {
				continue
			}
			change, e := bumpRefundParent(ctx, tx, workspace, entityID, entities[entityID].version)
			if e != nil {
				return commands.Mutation{}, e
			}
			changes = append(changes, change)
		}
		return commands.Mutation{Status: 200, Changes: changes}, nil
	}
}

func softDeleteTransaction(ctx context.Context, tx pgx.Tx, workspace, id string, current int64) (commands.Change, error) {
	var deletedAt time.Time
	if e := tx.QueryRow(ctx, `UPDATE transactions SET deleted_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1 WHERE workspace_id=$1 AND id=$2 RETURNING deleted_at`, workspace, id).Scan(&deletedAt); e != nil {
		return commands.Change{}, e
	}
	resultVersion := strconv.FormatInt(current+1, 10)
	data, e := json.Marshal(map[string]any{"entity_type": "transaction", "id": id, "workspace_id": workspace, "version": resultVersion, "deleted_at": deletedAt.UTC().Format(time.RFC3339Nano)})
	return commands.Change{EntityType: "transaction", ID: id, Version: resultVersion, Operation: "delete", Data: data}, e
}
