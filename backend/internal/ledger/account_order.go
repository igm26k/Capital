package ledger

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"context"
	"encoding/json"
	"math"
	"strconv"
	"strings"

	"github.com/jackc/pgx/v5"
)

type AccountOrder struct {
	Version    string   `json:"version"`
	AccountIDs []string `json:"account_ids"`
}
type AccountOrderUpdate struct {
	ExpectedVersion string   `json:"expected_version"`
	AccountIDs      []string `json:"account_ids"`
}

func DecodeAccountOrder(body []byte) (AccountOrderUpdate, error) {
	var input AccountOrderUpdate
	if err := decodeFields(body, &input, "expected_version", "account_ids"); err != nil {
		return input, err
	}
	n, err := strconv.ParseInt(input.ExpectedVersion, 10, 64)
	if err != nil || n < 0 || strconv.FormatInt(n, 10) != input.ExpectedVersion || len(input.AccountIDs) < 1 || len(input.AccountIDs) > 2000 {
		return input, invalid()
	}
	seen := map[string]bool{}
	for i, id := range input.AccountIDs {
		id = strings.ToLower(id)
		if !uuid(id) || seen[id] {
			return input, invalid()
		}
		seen[id] = true
		input.AccountIDs[i] = id
	}
	return input, nil
}

// A single statement keeps the preference version and the account set in one snapshot.
// New accounts not yet present in the saved order are appended deterministically.
func ReadAccountOrder(ctx context.Context, tx pgx.Tx, workspace string) (AccountOrder, error) {
	var order AccountOrder
	err := tx.QueryRow(ctx, `SELECT coalesce((SELECT version FROM account_orders WHERE workspace_id=$1),0)::text,
 coalesce((SELECT array_agg(a.id::text ORDER BY array_position(o.account_ids,a.id) NULLS LAST,a.id)
 FROM accounts a LEFT JOIN account_orders o ON o.workspace_id=a.workspace_id
 WHERE a.workspace_id=$1 AND a.deleted_at IS NULL),'{}'::text[])`, workspace).Scan(&order.Version, &order.AccountIDs)
	return order, err
}

// Account creation materializes the current order before appending, so later creates
// cannot reshuffle previously appended accounts by UUID.
func appendAccountOrder(ctx context.Context, tx pgx.Tx, workspace, id string) error {
	order, err := ReadAccountOrder(ctx, tx, workspace)
	if err != nil {
		return err
	}
	current, _ := strconv.ParseInt(order.Version, 10, 64)
	if current == math.MaxInt64 {
		return &identity.Error{Status: 503, Code: "service_unavailable"}
	}
	ids := make([]string, 0, len(order.AccountIDs))
	for _, existing := range order.AccountIDs {
		if existing != id {
			ids = append(ids, existing)
		}
	}
	ids = append(ids, id)
	_, err = tx.Exec(ctx, `INSERT INTO account_orders(workspace_id,version,account_ids) VALUES($1,$2,$3::uuid[])
 ON CONFLICT(workspace_id) DO UPDATE SET version=excluded.version,account_ids=excluded.account_ids,updated_at=clock_timestamp()`, workspace, current+1, ids)
	return err
}

func ReorderAccounts(workspace string, input AccountOrderUpdate) commands.Command {
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		data, err := json.Marshal(input)
		if err != nil {
			return commands.Mutation{}, err
		}
		input, err = DecodeAccountOrder(data)
		if err != nil {
			return commands.Mutation{}, err
		}
		// The command runner already holds the workspace head lock, shared with account creates.
		current, err := ReadAccountOrder(ctx, tx, workspace)
		if err != nil {
			return commands.Mutation{}, err
		}
		allowed := map[string]bool{}
		for _, id := range current.AccountIDs {
			allowed[id] = true
		}
		for _, id := range input.AccountIDs {
			if !allowed[id] {
				return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
			}
		}
		if current.Version != input.ExpectedVersion {
			return commands.Mutation{}, domainReject(409, "version_conflict")
		}
		if len(current.AccountIDs) != len(input.AccountIDs) {
			return commands.Mutation{}, domainReject(409, "version_conflict")
		}
		v, _ := strconv.ParseInt(current.Version, 10, 64)
		var anchorVersion int64
		anchor := input.AccountIDs[0]
		if err = tx.QueryRow(ctx, `SELECT version FROM accounts WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL FOR UPDATE`, workspace, anchor).Scan(&anchorVersion); err != nil {
			return commands.Mutation{}, err
		}
		if v == math.MaxInt64 || anchorVersion == math.MaxInt64 {
			return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
		}
		if _, err = tx.Exec(ctx, `INSERT INTO account_orders(workspace_id,version,account_ids) VALUES($1,$2,$3::uuid[])
 ON CONFLICT(workspace_id) DO UPDATE SET version=excluded.version,account_ids=excluded.account_ids,updated_at=clock_timestamp()`, workspace, v+1, input.AccountIDs); err != nil {
			return commands.Mutation{}, err
		}
		// An ordinary account metadata event invalidates existing mirrors without adding a new
		// financial entity type or changing the strict v1 Account DTO. Balances stay untouched.
		if _, err = tx.Exec(ctx, `UPDATE accounts SET version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, anchor); err != nil {
			return commands.Mutation{}, err
		}
		record, err := ReadAccount(ctx, tx, workspace, anchor)
		if err != nil {
			return commands.Mutation{}, err
		}
		return commands.Mutation{Status: 200, Changes: []commands.Change{{EntityType: "account", ID: anchor, Version: strconv.FormatInt(anchorVersion+1, 10), Operation: "upsert", Data: record}}}, nil
	}
}
