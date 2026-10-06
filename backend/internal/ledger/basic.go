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

type AllocationInput struct {
	ID         string  `json:"id"`
	CategoryID *string `json:"category_id"`
	Amount     string  `json:"amount_minor"`
}
type BasicInput struct {
	ID                    string            `json:"id,omitempty"`
	Kind                  string            `json:"kind"`
	AccountID             string            `json:"account_id"`
	Amount                string            `json:"amount_minor"`
	OccurredAt            string            `json:"occurred_at"`
	Timezone              string            `json:"occurred_timezone"`
	Note                  string            `json:"note"`
	Payee                 string            `json:"payee"`
	TagIDs                []string          `json:"tag_ids"`
	Allocations           []AllocationInput `json:"allocations"`
	ExpectedVersion       string            `json:"expected_version,omitempty"`
	ExpectedParentVersion *string           `json:"expected_parent_version,omitempty"`
}

func DecodeBasic(body []byte, update bool) (BasicInput, error) {
	var input BasicInput
	// Read the discriminant only; exact shape is validated below.
	var discriminator struct {
		Kind string `json:"kind"`
	}
	if e := json.Unmarshal(body, &discriminator); e != nil {
		return input, invalid()
	}
	if discriminator.Kind != "expense" && discriminator.Kind != "income" {
		return input, invalid()
	}
	fields := []string{"kind", "account_id", "amount_minor", "occurred_at", "occurred_timezone", "note", "payee", "tag_ids", "allocations"}
	nullable := []string{}
	if update {
		fields = append(fields, "expected_version")
		if discriminator.Kind == "expense" {
			fields = append(fields, "expected_parent_version")
			nullable = append(nullable, "expected_parent_version")
		}
	} else {
		fields = append(fields, "id")
	}
	if e := decodeObject(body, &input, fields, nullable); e != nil {
		return input, e
	}
	if !uuid(input.AccountID) || !validText(input.Note, 2000) || !validText(input.Payee, 200) || !validZone(input.Timezone) {
		return input, invalid()
	}
	input.AccountID = strings.ToLower(input.AccountID)
	if _, e := ParseInstant(input.OccurredAt); e != nil {
		return input, e
	}
	if _, e := ParsePositiveMoney(input.Amount); e != nil {
		return input, invalid()
	}
	if update {
		if _, e := version(input.ExpectedVersion); e != nil {
			return input, e
		}
		if input.ExpectedParentVersion != nil {
			if _, e := version(*input.ExpectedParentVersion); e != nil {
				return input, e
			}
		}
	} else {
		if !uuid(input.ID) {
			return input, invalid()
		}
		input.ID = strings.ToLower(input.ID)
	}
	if len(input.TagIDs) > 50 || len(input.Allocations) < 1 || len(input.Allocations) > 100 {
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
	for i, b := range raw.Allocations {
		var part AllocationInput
		if e := decodeObject(b, &part, []string{"id", "category_id", "amount_minor"}, []string{"category_id"}); e != nil {
			return input, e
		}
		part.ID = strings.ToLower(part.ID)
		if !uuid(part.ID) || seen[part.ID] {
			return input, invalid()
		}
		seen[part.ID] = true
		if _, e := ParsePositiveMoney(part.Amount); e != nil {
			return input, invalid()
		}
		if part.CategoryID != nil {
			id := strings.ToLower(*part.CategoryID)
			if !uuid(id) {
				return input, invalid()
			}
			part.CategoryID = &id
		}
		input.Allocations[i] = part
	}
	return input, nil
}

type lockedAccount struct {
	ID, Currency            string
	Opened                  time.Time
	Archived                bool
	Version, BalanceVersion int64
}

func lockAccounts(ctx context.Context, tx pgx.Tx, workspace string, ids ...string) (map[string]lockedAccount, error) {
	sort.Strings(ids)
	accounts := map[string]lockedAccount{}
	for _, id := range ids {
		if _, exists := accounts[id]; exists {
			continue
		}
		var a lockedAccount
		a.ID = id
		e := tx.QueryRow(ctx, `SELECT currency_code,opened_at,archived_at IS NOT NULL,version,balance_version FROM accounts WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL FOR UPDATE`, workspace, id).Scan(&a.Currency, &a.Opened, &a.Archived, &a.Version, &a.BalanceVersion)
		if errors.Is(e, pgx.ErrNoRows) {
			return nil, &identity.Error{Status: 404, Code: "not_found"}
		}
		if e != nil {
			return nil, e
		}
		accounts[id] = a
	}
	return accounts, nil
}
func checkBasicAmount(input BasicInput) error {
	var total, term big.Int
	for _, part := range input.Allocations {
		amount, e := ParsePositiveMoney(part.Amount)
		if e != nil {
			return invalid()
		}
		term.SetInt64(amount.Minor())
		total.Add(&total, &term)
	}
	amount, e := ParsePositiveMoney(input.Amount)
	if e != nil {
		return invalid()
	}
	term.SetInt64(amount.Minor())
	if total.Cmp(&term) != 0 {
		return domainReject(422, "validation_error")
	}
	return nil
}

// Existing category assignments are compared per allocation UUID, not just by set membership.
func checkReferences(ctx context.Context, tx pgx.Tx, workspace string, input BasicInput, oldParts map[string]*string, oldTags map[string]bool) error {
	for _, part := range input.Allocations {
		if part.CategoryID == nil {
			continue
		}
		var archived bool
		e := tx.QueryRow(ctx, `SELECT archived_at IS NOT NULL FROM categories WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL`, workspace, *part.CategoryID).Scan(&archived)
		if errors.Is(e, pgx.ErrNoRows) {
			return &identity.Error{Status: 404, Code: "not_found"}
		}
		if e != nil {
			return e
		}
		previous, exists := oldParts[part.ID]
		if archived && (!exists || previous == nil || *previous != *part.CategoryID) {
			return domainReject(422, "validation_error")
		}
	}
	for _, id := range input.TagIDs {
		var archived bool
		e := tx.QueryRow(ctx, `SELECT archived_at IS NOT NULL FROM tags WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL`, workspace, id).Scan(&archived)
		if errors.Is(e, pgx.ErrNoRows) {
			return &identity.Error{Status: 404, Code: "not_found"}
		}
		if e != nil {
			return e
		}
		if archived && !oldTags[id] {
			return domainReject(422, "validation_error")
		}
	}
	return nil
}
func validateAccountDate(ctx context.Context, tx pgx.Tx, a lockedAccount, occurred time.Time) error {
	if a.Archived {
		return domainReject(422, "account_archived")
	}
	var now time.Time
	if e := tx.QueryRow(ctx, `SELECT clock_timestamp()`).Scan(&now); e != nil {
		return e
	}
	if occurred.Before(a.Opened) || occurred.After(now) {
		return domainReject(422, "validation_error")
	}
	return nil
}
func validateBalances(ctx context.Context, tx pgx.Tx, workspace, id string) error {
	var posted, pending string
	e := tx.QueryRow(ctx, `SELECT coalesce(sum(e.amount_minor) FILTER(WHERE t.status='posted'),0)::text,coalesce(sum(e.amount_minor) FILTER(WHERE t.status='pending'),0)::text FROM entries e JOIN transactions t ON t.workspace_id=e.workspace_id AND t.id=e.transaction_id WHERE e.workspace_id=$1 AND e.account_id=$2 AND t.deleted_at IS NULL`, workspace, id).Scan(&posted, &pending)
	if e != nil {
		return e
	}
	p, e := ParseMoney(posted)
	if e != nil {
		return domainReject(422, "amount_out_of_range")
	}
	d, e := ParseMoney(pending)
	if e != nil {
		return domainReject(422, "amount_out_of_range")
	}
	if _, e = SumMoney(p, d); e != nil {
		return domainReject(422, "amount_out_of_range")
	}
	return nil
}
func bumpAccount(ctx context.Context, tx pgx.Tx, workspace string, a lockedAccount) (commands.Change, error) {
	if a.Version == math.MaxInt64 || a.BalanceVersion == math.MaxInt64 {
		return commands.Change{}, &identity.Error{Status: 503, Code: "service_unavailable"}
	}
	if e := validateBalances(ctx, tx, workspace, a.ID); e != nil {
		return commands.Change{}, e
	}
	if _, e := tx.Exec(ctx, `UPDATE accounts SET version=version+1,balance_version=balance_version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, a.ID); e != nil {
		return commands.Change{}, e
	}
	data, e := ReadAccount(ctx, tx, workspace, a.ID)
	return commands.Change{EntityType: "account", ID: a.ID, Version: strconv.FormatInt(a.Version+1, 10), Operation: "upsert", Data: data}, e
}
func writeBasicParts(ctx context.Context, tx pgx.Tx, workspace, id string, input BasicInput) error {
	for _, part := range input.Allocations {
		amount, _ := ParsePositiveMoney(part.Amount)
		tag, e := tx.Exec(ctx, `INSERT INTO allocations(id,workspace_id,transaction_id,category_id,amount_minor) VALUES($1,$2,$3,$4,$5) ON CONFLICT(id) DO NOTHING`, part.ID, workspace, id, part.CategoryID, amount.Minor())
		if e != nil {
			return e
		}
		if tag.RowsAffected() != 1 {
			return domainReject(422, "validation_error")
		}
	}
	for _, tag := range input.TagIDs {
		if _, e := tx.Exec(ctx, `INSERT INTO transaction_tags(workspace_id,transaction_id,tag_id) VALUES($1,$2,$3)`, workspace, id, tag); e != nil {
			return e
		}
	}
	return nil
}
func CreateBasic(workspace string, input BasicInput) commands.Command {
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		// Serialize explicit fields so nil optional replacement fields cannot affect creation validation.
		b, e := json.Marshal(input)
		if e != nil {
			return commands.Mutation{}, e
		}
		input, e = DecodeBasic(b, false)
		if e != nil {
			return commands.Mutation{}, e
		}
		accounts, e := lockAccounts(ctx, tx, workspace, input.AccountID)
		if e != nil {
			return commands.Mutation{}, e
		}
		a := accounts[input.AccountID]
		occurred, _ := ParseInstant(input.OccurredAt)
		if e = validateAccountDate(ctx, tx, a, occurred); e != nil {
			return commands.Mutation{}, e
		}
		if e = checkBasicAmount(input); e != nil {
			return commands.Mutation{}, e
		}
		if e = checkReferences(ctx, tx, workspace, input, nil, nil); e != nil {
			return commands.Mutation{}, e
		}
		tag, e := tx.Exec(ctx, `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,note,payee) VALUES($1,$2,$3,'posted',$4,$5,$6,$7) ON CONFLICT(id) DO NOTHING`, input.ID, workspace, input.Kind, occurred, input.Timezone, input.Note, input.Payee)
		if e != nil {
			return commands.Mutation{}, e
		}
		if tag.RowsAffected() != 1 {
			return commands.Mutation{}, domainReject(409, "validation_error")
		}
		amount, _ := ParsePositiveMoney(input.Amount)
		if input.Kind == "expense" {
			amount = amount.Negate()
		}
		if _, e = tx.Exec(ctx, `INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) VALUES($1,$2,$3,$4,$5)`, newID(), workspace, input.ID, input.AccountID, amount.Minor()); e != nil {
			return commands.Mutation{}, e
		}
		if e = writeBasicParts(ctx, tx, workspace, input.ID, input); e != nil {
			return commands.Mutation{}, e
		}
		change, e := bumpAccount(ctx, tx, workspace, a)
		if e != nil {
			return commands.Mutation{}, e
		}
		data, e := ReadTransaction(ctx, tx, workspace, input.ID)
		if e != nil {
			return commands.Mutation{}, e
		}
		return commands.Mutation{Status: 201, Changes: []commands.Change{change, {EntityType: "transaction", ID: input.ID, Version: "1", Operation: "upsert", Data: data}}}, nil
	}
}

func ReplaceBasic(workspace, id string, input BasicInput) commands.Command {
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		input := input
		wire := map[string]any{"kind": input.Kind, "account_id": input.AccountID, "amount_minor": input.Amount, "occurred_at": input.OccurredAt, "occurred_timezone": input.Timezone, "note": input.Note, "payee": input.Payee, "tag_ids": input.TagIDs, "allocations": input.Allocations, "expected_version": input.ExpectedVersion}
		if input.Kind == "expense" {
			wire["expected_parent_version"] = input.ExpectedParentVersion
		}
		b, e := json.Marshal(wire)
		if e != nil {
			return commands.Mutation{}, e
		}
		input, e = DecodeBasic(b, true)
		if e != nil {
			return commands.Mutation{}, e
		}
		id = strings.ToLower(id)
		if !uuid(id) {
			return commands.Mutation{}, &identity.Error{Status: 400, Code: "bad_request"}
		}
		// Head mutex prevents other ledger writers between discovery and ordered account locks.
		var oldAccount, oldKind, oldAmount string
		var oldDate time.Time
		var parent *string
		e = tx.QueryRow(ctx, `SELECT coalesce(e.account_id::text,''),t.kind,coalesce(e.amount_minor::text,''),t.occurred_at,t.parent_transaction_id::text FROM transactions t LEFT JOIN entries e ON e.workspace_id=t.workspace_id AND e.transaction_id=t.id WHERE t.workspace_id=$1 AND t.id=$2 AND t.deleted_at IS NULL`, workspace, id).Scan(&oldAccount, &oldKind, &oldAmount, &oldDate, &parent)
		if errors.Is(e, pgx.ErrNoRows) {
			return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
		}
		if e != nil {
			return commands.Mutation{}, e
		}
		if (oldKind != "expense" && oldKind != "income") || input.Kind != oldKind {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		if parent == nil && input.ExpectedParentVersion != nil || parent != nil && input.ExpectedParentVersion == nil {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		accounts, e := lockAccounts(ctx, tx, workspace, oldAccount, input.AccountID)
		if e != nil {
			return commands.Mutation{}, e
		}
		transactionIDs := []string{id}
		if parent != nil {
			transactionIDs = append(transactionIDs, *parent)
		}
		sort.Strings(transactionIDs)
		versions := map[string]int64{}
		var parentDate time.Time
		for _, transactionID := range transactionIDs {
			var v int64
			var kind string
			var date time.Time
			e = tx.QueryRow(ctx, `SELECT version,kind,occurred_at FROM transactions WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL FOR UPDATE`, workspace, transactionID).Scan(&v, &kind, &date)
			if errors.Is(e, pgx.ErrNoRows) {
				return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
			}
			if e != nil {
				return commands.Mutation{}, e
			}
			if transactionID != id {
				if kind != "transfer" {
					return commands.Mutation{}, domainReject(422, "validation_error")
				}
				parentDate = date
				expected, _ := version(*input.ExpectedParentVersion)
				if expected != v {
					return commands.Mutation{}, transactionVersionConflict(transactionID, v)
				}
				if v == math.MaxInt64 {
					return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
				}
			}
			versions[transactionID] = v
		}
		current := versions[id]
		expected, e := version(input.ExpectedVersion)
		if e != nil {
			return commands.Mutation{}, e
		}
		if expected != current {
			return commands.Mutation{}, &commands.Rejection{Status: 409, Code: "version_conflict", Message: "Операция изменена.", CurrentVersions: []any{map[string]any{"entity_type": "transaction", "id": id, "version": strconv.FormatInt(current, 10), "balance_version": nil}}}
		}
		if current == math.MaxInt64 {
			return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
		}
		if accounts[oldAccount].Archived {
			return commands.Mutation{}, domainReject(422, "account_archived")
		}
		occurred, e := ParseInstant(input.OccurredAt)
		if e != nil {
			return commands.Mutation{}, e
		}
		occurred = occurred.Truncate(time.Microsecond)
		if parent != nil && !occurred.Equal(parentDate) {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		if e = validateAccountDate(ctx, tx, accounts[input.AccountID], occurred); e != nil {
			return commands.Mutation{}, e
		}
		if accounts[oldAccount].Currency != accounts[input.AccountID].Currency {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		history, e := readBasicHistory(ctx, tx, workspace, id)
		if e != nil {
			return commands.Mutation{}, e
		}
		if e = validateBasicReplacement(ctx, tx, workspace, id, oldAccount, oldAmount, oldDate, history, input, occurred); e != nil {
			return commands.Mutation{}, e
		}
		amount, e := ParsePositiveMoney(input.Amount)
		if e != nil {
			return commands.Mutation{}, invalid()
		}
		if input.Kind == "expense" {
			amount = amount.Negate()
		}
		movementChanged := oldAccount != input.AccountID || oldAmount != amount.String() || !oldDate.Equal(occurred)
		if _, e = tx.Exec(ctx, `UPDATE transactions SET occurred_at=$3,occurred_timezone=$4,note=$5,payee=$6,version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, id, occurred, input.Timezone, input.Note, input.Payee); e != nil {
			return commands.Mutation{}, e
		}
		if _, e = tx.Exec(ctx, `UPDATE entries SET account_id=$3,amount_minor=$4 WHERE workspace_id=$1 AND transaction_id=$2`, workspace, id, input.AccountID, amount.Minor()); e != nil {
			return commands.Mutation{}, e
		}
		if e = writeBasicReplacement(ctx, tx, workspace, id, history, input); e != nil {
			return commands.Mutation{}, e
		}
		changes := []commands.Change{}
		if movementChanged {
			ids := []string{}
			for accountID := range accounts {
				ids = append(ids, accountID)
			}
			sort.Strings(ids)
			for _, accountID := range ids {
				change, e := bumpAccount(ctx, tx, workspace, accounts[accountID])
				if e != nil {
					return commands.Mutation{}, e
				}
				changes = append(changes, change)
			}
		}
		if parent != nil {
			change, e := bumpRefundParent(ctx, tx, workspace, *parent, versions[*parent])
			if e != nil {
				return commands.Mutation{}, e
			}
			changes = append(changes, change)
		}
		data, e := ReadTransaction(ctx, tx, workspace, id)
		if e != nil {
			return commands.Mutation{}, e
		}
		changes = append(changes, commands.Change{EntityType: "transaction", ID: id, Version: strconv.FormatInt(current+1, 10), Operation: "upsert", Data: data})
		return commands.Mutation{Status: 200, Changes: changes}, nil
	}
}
func sameReference(a, b *string) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}
