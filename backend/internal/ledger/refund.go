package ledger

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"context"
	"encoding/json"
	"errors"
	"math"
	"math/big"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

type RefundAllocationInput struct {
	ID         string `json:"id"`
	OriginalID string `json:"original_allocation_id"`
	Amount     string `json:"amount_minor"`
}
type RefundInput struct {
	ID                      string                  `json:"id,omitempty"`
	ExpectedVersion         string                  `json:"expected_version,omitempty"`
	Kind                    string                  `json:"kind"`
	AccountID               string                  `json:"account_id"`
	Amount                  string                  `json:"amount_minor"`
	ParentID                string                  `json:"parent_transaction_id"`
	ExpectedParentVersion   string                  `json:"expected_parent_version"`
	ExpectedTransferVersion *string                 `json:"expected_transfer_version"`
	OccurredAt              string                  `json:"occurred_at"`
	Timezone                string                  `json:"occurred_timezone"`
	Note                    string                  `json:"note"`
	Payee                   string                  `json:"payee"`
	TagIDs                  []string                `json:"tag_ids"`
	Allocations             []RefundAllocationInput `json:"allocations"`
}

func DecodeRefundCreate(body []byte) (RefundInput, error) {
	return decodeRefund(body, false)
}

func DecodeRefundReplace(body []byte) (RefundInput, error) {
	return decodeRefund(body, true)
}

func decodeRefund(body []byte, update bool) (RefundInput, error) {
	var input RefundInput
	fields := []string{"kind", "account_id", "amount_minor", "parent_transaction_id", "expected_parent_version", "expected_transfer_version", "occurred_at", "occurred_timezone", "note", "payee", "tag_ids", "allocations"}
	if update {
		fields = append(fields, "expected_version")
	} else {
		fields = append(fields, "id")
	}
	if e := decodeObject(body, &input, fields, []string{"expected_transfer_version"}); e != nil {
		return input, e
	}
	if input.Kind != "refund" || (!update && !uuid(input.ID)) || !uuid(input.AccountID) || !uuid(input.ParentID) || !validZone(input.Timezone) || !validText(input.Note, 2000) || !validText(input.Payee, 200) {
		return input, invalid()
	}
	if _, e := ParsePositiveMoney(input.Amount); e != nil {
		return input, invalid()
	}
	if _, e := ParseInstant(input.OccurredAt); e != nil {
		return input, e
	}
	if _, e := version(input.ExpectedParentVersion); e != nil {
		return input, e
	}
	if update {
		if _, e := version(input.ExpectedVersion); e != nil {
			return input, e
		}
	}
	if input.ExpectedTransferVersion != nil {
		if _, e := version(*input.ExpectedTransferVersion); e != nil {
			return input, e
		}
	}
	input.ID = strings.ToLower(input.ID)
	input.AccountID = strings.ToLower(input.AccountID)
	input.ParentID = strings.ToLower(input.ParentID)
	if input.ID == input.ParentID || len(input.TagIDs) > 50 || len(input.Allocations) < 1 || len(input.Allocations) > 100 {
		return input, invalid()
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
		Allocations []json.RawMessage `json:"allocations"`
	}
	if e := json.Unmarshal(body, &raw); e != nil {
		return input, invalid()
	}
	seen = map[string]bool{}
	originals := map[string]bool{}
	for i, body := range raw.Allocations {
		var part RefundAllocationInput
		if e := decodeFields(body, &part, "id", "original_allocation_id", "amount_minor"); e != nil {
			return input, e
		}
		part.ID = strings.ToLower(part.ID)
		part.OriginalID = strings.ToLower(part.OriginalID)
		if !uuid(part.ID) || !uuid(part.OriginalID) || part.ID == part.OriginalID || seen[part.ID] || originals[part.OriginalID] {
			return input, invalid()
		}
		if _, e := ParsePositiveMoney(part.Amount); e != nil {
			return input, invalid()
		}
		seen[part.ID] = true
		originals[part.OriginalID] = true
		input.Allocations[i] = part
	}
	return input, nil
}
func refundSum(input RefundInput) error {
	total := new(big.Int)
	for _, part := range input.Allocations {
		amount, _ := ParsePositiveMoney(part.Amount)
		total.Add(total, big.NewInt(amount.Minor()))
	}
	amount, _ := ParsePositiveMoney(input.Amount)
	if total.Cmp(big.NewInt(amount.Minor())) != 0 {
		return domainReject(422, "validation_error")
	}
	return nil
}
func transactionVersionConflict(id string, current int64) error {
	return &commands.Rejection{Status: 409, Code: "version_conflict", Message: "Операция изменена.", CurrentVersions: []any{map[string]any{"entity_type": "transaction", "id": id, "version": strconv.FormatInt(current, 10), "balance_version": nil}}}
}
func bumpRefundParent(ctx context.Context, tx pgx.Tx, workspace, id string, current int64) (commands.Change, error) {
	if current == math.MaxInt64 {
		return commands.Change{}, &identity.Error{Status: 503, Code: "service_unavailable"}
	}
	if _, e := tx.Exec(ctx, `UPDATE transactions SET version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, id); e != nil {
		return commands.Change{}, e
	}
	data, e := ReadTransaction(ctx, tx, workspace, id)
	return commands.Change{EntityType: "transaction", ID: id, Version: strconv.FormatInt(current+1, 10), Operation: "upsert", Data: data}, e
}

// Protect returned expense allocations by UUID, amount and category, independent
// of array order. Pending and posted active refunds both create dependencies.
func guardRefundedExpenseEdit(ctx context.Context, tx pgx.Tx, workspace, id, oldAccount, oldAmount string, oldDate time.Time, oldParts map[string]*string, oldAmounts map[string]string, input BasicInput, occurred time.Time) error {
	if input.Kind != "expense" {
		return nil
	}
	var dependent bool
	if e := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM transactions WHERE workspace_id=$1 AND parent_transaction_id=$2 AND kind='refund' AND deleted_at IS NULL AND status IN ('posted','pending'))`, workspace, id).Scan(&dependent); e != nil {
		return e
	}
	if !dependent {
		return nil
	}
	changed := oldAccount != input.AccountID || oldAmount != "-"+input.Amount || !oldDate.Equal(occurred) || len(oldParts) != len(input.Allocations)
	for _, part := range input.Allocations {
		category, exists := oldParts[part.ID]
		if !exists || oldAmounts[part.ID] != part.Amount || !sameReference(category, part.CategoryID) {
			changed = true
		}
	}
	if changed {
		return domainReject(409, "dependent_transactions")
	}
	return nil
}

func CreateRefund(workspace string, input RefundInput) commands.Command {
	return refundCommand(workspace, "", input)
}

func ReplaceRefund(workspace, id string, input RefundInput) commands.Command {
	return refundCommand(workspace, strings.ToLower(id), input)
}

func refundCommand(workspace, id string, input RefundInput) commands.Command {
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		input := input // Each runner retry starts with the original wire input.
		body, e := json.Marshal(input)
		if e != nil {
			return commands.Mutation{}, e
		}
		input, e = decodeRefund(body, id != "")
		if e != nil {
			return commands.Mutation{}, e
		}
		var oldAccount, oldAmount string
		var oldDate time.Time
		oldParts := map[string]string{}
		oldAmounts := map[string]string{}
		oldTags := map[string]bool{}
		if id != "" {
			if !uuid(id) {
				return commands.Mutation{}, &identity.Error{Status: 400, Code: "bad_request"}
			}
			input.ID = id
			var oldParent, oldKind string
			e = tx.QueryRow(ctx, `SELECT coalesce(e.account_id::text,''),coalesce(e.amount_minor::text,''),t.occurred_at,coalesce(t.parent_transaction_id::text,''),t.kind FROM transactions t LEFT JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id) WHERE t.workspace_id=$1 AND t.id=$2 AND t.deleted_at IS NULL`, workspace, id).Scan(&oldAccount, &oldAmount, &oldDate, &oldParent, &oldKind)
			if errors.Is(e, pgx.ErrNoRows) {
				return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
			}
			if e != nil {
				return commands.Mutation{}, e
			}
			if oldKind != "refund" || oldParent != input.ParentID {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
			rows, e := tx.Query(ctx, `SELECT id::text,original_allocation_id::text,amount_minor::text FROM allocations WHERE workspace_id=$1 AND transaction_id=$2`, workspace, id)
			if e != nil {
				return commands.Mutation{}, e
			}
			for rows.Next() {
				var part, original, amount string
				if e = rows.Scan(&part, &original, &amount); e != nil {
					rows.Close()
					return commands.Mutation{}, e
				}
				oldParts[part] = original
				oldAmounts[part] = amount
			}
			e = rows.Err()
			rows.Close()
			if e != nil {
				return commands.Mutation{}, e
			}
			rows, e = tx.Query(ctx, `SELECT tag_id::text FROM transaction_tags WHERE workspace_id=$1 AND transaction_id=$2`, workspace, id)
			if e != nil {
				return commands.Mutation{}, e
			}
			for rows.Next() {
				var tag string
				if e = rows.Scan(&tag); e != nil {
					rows.Close()
					return commands.Mutation{}, e
				}
				oldTags[tag] = true
			}
			e = rows.Err()
			rows.Close()
			if e != nil {
				return commands.Mutation{}, e
			}
		}
		var accountID, kind, status string
		var parentDate time.Time
		var transferID *string
		e = tx.QueryRow(ctx, `SELECT coalesce(e.account_id::text,''),t.kind,t.status,t.occurred_at,t.parent_transaction_id::text FROM transactions t LEFT JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id) WHERE t.workspace_id=$1 AND t.id=$2 AND t.deleted_at IS NULL`, workspace, input.ParentID).Scan(&accountID, &kind, &status, &parentDate, &transferID)
		if errors.Is(e, pgx.ErrNoRows) {
			return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
		}
		if e != nil {
			return commands.Mutation{}, e
		}
		if kind != "expense" || status != "posted" {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		accounts, e := lockAccounts(ctx, tx, workspace, append([]string{accountID, input.AccountID}, nonemptyAccount(oldAccount)...)...)
		if e != nil {
			return commands.Mutation{}, e
		}
		// Accounts precede existing transaction locks; head serializes discovery.
		ids := []string{input.ParentID}
		if transferID != nil {
			ids = append(ids, *transferID)
		}
		if id != "" {
			ids = append(ids, id)
		}
		sort.Strings(ids)
		versions := map[string]int64{}
		for _, id := range ids {
			var v int64
			var k, s string
			e = tx.QueryRow(ctx, `SELECT version,kind,status FROM transactions WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL FOR UPDATE`, workspace, id).Scan(&v, &k, &s)
			if errors.Is(e, pgx.ErrNoRows) {
				return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
			}
			if e != nil {
				return commands.Mutation{}, e
			}
			if (id == input.ID && input.ExpectedVersion != "" && k != "refund") || (id != input.ParentID && id != input.ID && (k != "transfer" || s != "posted")) {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
			versions[id] = v
		}
		if id != "" {
			expected, _ := version(input.ExpectedVersion)
			if expected != versions[id] {
				return commands.Mutation{}, transactionVersionConflict(id, versions[id])
			}
		}
		expected, _ := version(input.ExpectedParentVersion)
		if expected != versions[input.ParentID] {
			return commands.Mutation{}, transactionVersionConflict(input.ParentID, versions[input.ParentID])
		}
		if transferID == nil {
			if input.ExpectedTransferVersion != nil {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
		} else {
			if input.ExpectedTransferVersion == nil {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
			expected, _ = version(*input.ExpectedTransferVersion)
			if expected != versions[*transferID] {
				return commands.Mutation{}, transactionVersionConflict(*transferID, versions[*transferID])
			}
		}
		for _, v := range versions {
			if v == math.MaxInt64 {
				return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
			}
		}
		if oldAccount != "" && (accounts[oldAccount].Archived || accounts[oldAccount].Currency != accounts[input.AccountID].Currency) {
			if accounts[oldAccount].Archived {
				return commands.Mutation{}, domainReject(422, "account_archived")
			}
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		if accounts[accountID].Archived {
			return commands.Mutation{}, domainReject(422, "account_archived")
		}
		if accounts[accountID].Currency != accounts[input.AccountID].Currency {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		occurred, _ := ParseInstant(input.OccurredAt)
		occurred = occurred.Truncate(time.Microsecond)
		if occurred.Before(parentDate) {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		if e = validateAccountDate(ctx, tx, accounts[input.AccountID], occurred); e != nil {
			return commands.Mutation{}, e
		}
		if e = refundSum(input); e != nil {
			return commands.Mutation{}, e
		}
		if e = checkReferences(ctx, tx, workspace, BasicInput{TagIDs: input.TagIDs}, nil, oldTags); e != nil {
			return commands.Mutation{}, e
		}
		type original struct {
			category         *string
			amount, reserved string
		}
		originals := map[string]original{}
		rows, e := tx.Query(ctx, `SELECT p.id::text,p.category_id::text,p.amount_minor::text,coalesce((SELECT sum(r.amount_minor) FROM allocations r JOIN transactions t ON (t.workspace_id,t.id)=(r.workspace_id,r.transaction_id) WHERE r.workspace_id=p.workspace_id AND r.original_allocation_id=p.id AND t.kind='refund' AND t.deleted_at IS NULL AND t.status IN ('posted','pending') AND ($3::uuid IS NULL OR t.id<>$3::uuid)),0)::text FROM allocations p WHERE p.workspace_id=$1 AND p.transaction_id=$2`, workspace, input.ParentID, nullableID(id))
		if e != nil {
			return commands.Mutation{}, e
		}
		for rows.Next() {
			var id string
			var part original
			if e = rows.Scan(&id, &part.category, &part.amount, &part.reserved); e != nil {
				rows.Close()
				return commands.Mutation{}, e
			}
			originals[id] = part
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return commands.Mutation{}, e
		}
		for _, part := range input.Allocations {
			original, ok := originals[part.OriginalID]
			if !ok {
				return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
			}
			amount, _ := new(big.Int).SetString(original.amount, 10)
			reserved, ok := new(big.Int).SetString(original.reserved, 10)
			if !ok {
				return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
			}
			remaining := new(big.Int).Sub(amount, reserved)
			requested, _ := ParsePositiveMoney(part.Amount)
			if remaining.Cmp(big.NewInt(requested.Minor())) < 0 {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
		}
		movementChanged := id == "" || oldAccount != input.AccountID || oldAmount != input.Amount || !oldDate.Equal(occurred)
		financialChanged := movementChanged || len(oldParts) != len(input.Allocations)
		partIDs := []string{}
		for _, part := range input.Allocations {
			partIDs = append(partIDs, part.ID)
			if original, exists := oldParts[part.ID]; exists && original != part.OriginalID {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
			for oldID, original := range oldParts {
				if original == part.OriginalID && oldID != part.ID {
					return commands.Mutation{}, domainReject(422, "validation_error")
				}
			}
			if oldParts[part.ID] != part.OriginalID || oldAmounts[part.ID] != part.Amount {
				financialChanged = true
			}
		}
		amount, _ := ParsePositiveMoney(input.Amount)
		if id == "" {
			inserted, e := tx.Exec(ctx, `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,note,payee,parent_transaction_id) VALUES($1,$2,'refund','posted',$3,$4,$5,$6,$7) ON CONFLICT(id) DO NOTHING`, input.ID, workspace, occurred, input.Timezone, input.Note, input.Payee, input.ParentID)
			if e != nil {
				return commands.Mutation{}, e
			}
			if inserted.RowsAffected() != 1 {
				return commands.Mutation{}, domainReject(409, "validation_error")
			}
			if _, e = tx.Exec(ctx, `INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) VALUES($1,$2,$3,$4,$5)`, newID(), workspace, input.ID, input.AccountID, amount.Minor()); e != nil {
				return commands.Mutation{}, e
			}
		} else {
			if _, e = tx.Exec(ctx, `UPDATE transactions SET occurred_at=$3,occurred_timezone=$4,note=$5,payee=$6,version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, id, occurred, input.Timezone, input.Note, input.Payee); e != nil {
				return commands.Mutation{}, e
			}
			if _, e = tx.Exec(ctx, `UPDATE entries SET account_id=$3,amount_minor=$4 WHERE workspace_id=$1 AND transaction_id=$2`, workspace, id, input.AccountID, amount.Minor()); e != nil {
				return commands.Mutation{}, e
			}
			if _, e = tx.Exec(ctx, `DELETE FROM allocations WHERE workspace_id=$1 AND transaction_id=$2 AND NOT(id=ANY($3::uuid[]))`, workspace, id, partIDs); e != nil {
				return commands.Mutation{}, e
			}
			if _, e = tx.Exec(ctx, `DELETE FROM transaction_tags WHERE workspace_id=$1 AND transaction_id=$2`, workspace, id); e != nil {
				return commands.Mutation{}, e
			}
		}
		for _, part := range input.Allocations {
			amount, _ := ParsePositiveMoney(part.Amount)
			original := originals[part.OriginalID]
			if _, exists := oldParts[part.ID]; exists {
				if _, e = tx.Exec(ctx, `UPDATE allocations SET amount_minor=$3,category_id=$4 WHERE workspace_id=$1 AND id=$2 AND transaction_id=$5`, workspace, part.ID, amount.Minor(), original.category, id); e != nil {
					return commands.Mutation{}, e
				}
			} else {
				inserted, e := tx.Exec(ctx, `INSERT INTO allocations(id,workspace_id,transaction_id,category_id,amount_minor,original_allocation_id) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(id) DO NOTHING`, part.ID, workspace, input.ID, original.category, amount.Minor(), part.OriginalID)
				if e != nil {
					return commands.Mutation{}, e
				}
				if inserted.RowsAffected() != 1 {
					return commands.Mutation{}, domainReject(422, "validation_error")
				}
			}
		}
		for _, tag := range input.TagIDs {
			if _, e = tx.Exec(ctx, `INSERT INTO transaction_tags(workspace_id,transaction_id,tag_id) VALUES($1,$2,$3)`, workspace, input.ID, tag); e != nil {
				return commands.Mutation{}, e
			}
		}
		changes := []commands.Change{}
		if movementChanged {
			accountIDs := []string{input.AccountID}
			if oldAccount != "" && oldAccount != input.AccountID {
				accountIDs = append(accountIDs, oldAccount)
			}
			sort.Strings(accountIDs)
			for _, accountID := range accountIDs {
				change, e := bumpAccount(ctx, tx, workspace, accounts[accountID])
				if e != nil {
					return commands.Mutation{}, e
				}
				changes = append(changes, change)
			}
		}
		if financialChanged {
			for _, parentID := range ids {
				if parentID == id {
					continue
				}
				change, e := bumpRefundParent(ctx, tx, workspace, parentID, versions[parentID])
				if e != nil {
					return commands.Mutation{}, e
				}
				changes = append(changes, change)
			}
		}
		data, e := ReadTransaction(ctx, tx, workspace, input.ID)
		if e != nil {
			return commands.Mutation{}, e
		}
		responseStatus, resultVersion := 201, "1"
		if id != "" {
			responseStatus = 200
			resultVersion = strconv.FormatInt(versions[id]+1, 10)
		}
		changes = append(changes, commands.Change{EntityType: "transaction", ID: input.ID, Version: resultVersion, Operation: "upsert", Data: data})
		return commands.Mutation{Status: responseStatus, Changes: changes}, nil
	}
}

func nonemptyAccount(id string) []string {
	if id == "" {
		return nil
	}
	return []string{id}
}
func nullableID(id string) any {
	if id == "" {
		return nil
	}
	return id
}
