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

type transferMovement struct{ ID, AccountID, Amount string }
type transferState struct {
	Version               int64
	Date                  time.Time
	Status                string
	Source, Target        transferMovement
	FeeID                 string
	FeeVersion            int64
	FeeAccount, FeeAmount string
	FeeDate               time.Time
}

func readTransferState(ctx context.Context, tx pgx.Tx, workspace, id string) (transferState, error) {
	var state transferState
	var kind string
	e := tx.QueryRow(ctx, `SELECT kind,version,occurred_at,status FROM transactions WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL`, workspace, id).Scan(&kind, &state.Version, &state.Date, &state.Status)
	if errors.Is(e, pgx.ErrNoRows) {
		return state, &identity.Error{Status: 404, Code: "not_found"}
	}
	if e != nil {
		return state, e
	}
	if kind != "transfer" {
		return state, domainReject(422, "validation_error")
	}
	rows, e := tx.Query(ctx, `SELECT id::text,account_id::text,amount_minor::text FROM entries WHERE workspace_id=$1 AND transaction_id=$2`, workspace, id)
	if e != nil {
		return state, e
	}
	count := 0
	for rows.Next() {
		var entry transferMovement
		if e = rows.Scan(&entry.ID, &entry.AccountID, &entry.Amount); e != nil {
			rows.Close()
			return state, e
		}
		count++
		if strings.HasPrefix(entry.Amount, "-") {
			state.Source = entry
		} else {
			state.Target = entry
		}
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return state, e
	}
	if count != 2 || state.Source.ID == "" || state.Target.ID == "" {
		return state, &identity.Error{Status: 500, Code: "internal_error"}
	}
	e = tx.QueryRow(ctx, `SELECT t.id::text,t.version,t.occurred_at,e.account_id::text,e.amount_minor::text FROM transactions t JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id) WHERE t.workspace_id=$1 AND t.parent_transaction_id=$2 AND t.kind='expense' AND t.deleted_at IS NULL`, workspace, id).Scan(&state.FeeID, &state.FeeVersion, &state.FeeDate, &state.FeeAccount, &state.FeeAmount)
	if errors.Is(e, pgx.ErrNoRows) {
		return state, nil
	}
	return state, e
}

// ReplaceTransfer validates and writes the full transfer/fee aggregate under the
// workspace head. Balances are checked only after both movements and fee reach
// their final state; a shared account is published and bumped once.
func ReplaceTransfer(workspace, id string, input TransferInput) commands.Command {
	id = strings.ToLower(id)
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		if !uuid(id) {
			return commands.Mutation{}, &identity.Error{Status: 400, Code: "bad_request"}
		}
		encoded, e := json.Marshal(input)
		if e != nil {
			return commands.Mutation{}, e
		}
		var wire map[string]json.RawMessage
		if e = json.Unmarshal(encoded, &wire); e != nil {
			return commands.Mutation{}, e
		}
		delete(wire, "id")
		wire["expected_fee_version"], e = json.Marshal(input.ExpectedFeeVersion)
		if e != nil {
			return commands.Mutation{}, e
		}
		encoded, e = json.Marshal(wire)
		if e != nil {
			return commands.Mutation{}, e
		}
		desired, e := DecodeTransferReplace(encoded)
		if e != nil {
			return commands.Mutation{}, e
		}
		if desired.Fee != nil && desired.Fee.ID == id {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		state, e := readTransferState(ctx, tx, workspace, id)
		if e != nil {
			return commands.Mutation{}, e
		}
		if (state.FeeID == "") != (desired.ExpectedFeeVersion == nil) {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		if state.FeeID != "" && desired.Fee != nil && desired.Fee.ID != state.FeeID {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		accountIDs := []string{state.Source.AccountID, state.Target.AccountID, desired.SourceAccountID, desired.TargetAccountID}
		if state.FeeID != "" {
			accountIDs = append(accountIDs, state.FeeAccount)
		}
		if desired.Fee != nil {
			accountIDs = append(accountIDs, desired.Fee.AccountID)
		}
		accounts, e := lockAccounts(ctx, tx, workspace, accountIDs...)
		if e != nil {
			return commands.Mutation{}, e
		}
		transactionIDs := []string{id}
		expected := map[string]string{id: desired.ExpectedVersion}
		if state.FeeID != "" {
			transactionIDs = append(transactionIDs, state.FeeID)
			expected[state.FeeID] = *desired.ExpectedFeeVersion
		}
		sort.Strings(transactionIDs)
		for _, transactionID := range transactionIDs {
			var current int64
			e = tx.QueryRow(ctx, `SELECT version FROM transactions WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL FOR UPDATE`, workspace, transactionID).Scan(&current)
			if errors.Is(e, pgx.ErrNoRows) {
				return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
			}
			if e != nil {
				return commands.Mutation{}, e
			}
			requested, _ := version(expected[transactionID])
			if requested != current {
				return commands.Mutation{}, transactionVersionConflict(transactionID, current)
			}
			if current == math.MaxInt64 {
				return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
			}
		}
		for _, account := range accounts {
			if account.Archived {
				return commands.Mutation{}, domainReject(422, "account_archived")
			}
		}
		source, target := accounts[desired.SourceAccountID], accounts[desired.TargetAccountID]
		if source.ID == target.ID || source.Currency != accounts[state.Source.AccountID].Currency || target.Currency != accounts[state.Target.AccountID].Currency {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		if source.Currency == target.Currency && desired.SourceAmount != desired.TargetAmount {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		occurred, _ := ParseInstant(desired.OccurredAt)
		occurred = occurred.Truncate(time.Microsecond)
		desired.OccurredAt = occurred.UTC().Format(time.RFC3339Nano)
		for _, accountID := range []string{source.ID, target.ID} {
			if e = validateAccountDate(ctx, tx, accounts[accountID], occurred); e != nil {
				return commands.Mutation{}, e
			}
		}
		history, e := readBasicHistory(ctx, tx, workspace, id)
		if e != nil {
			return commands.Mutation{}, e
		}
		if e = checkReferences(ctx, tx, workspace, BasicInput{TagIDs: desired.TagIDs}, nil, history.Tags); e != nil {
			return commands.Mutation{}, e
		}
		var sourceScale, targetScale int
		if e = tx.QueryRow(ctx, `SELECT (SELECT scale FROM currencies WHERE code=$1),(SELECT scale FROM currencies WHERE code=$2)`, source.Currency, target.Currency).Scan(&sourceScale, &targetScale); e != nil {
			return commands.Mutation{}, e
		}
		rate, e := ExactTransferRate(desired.SourceAmount, desired.TargetAmount, sourceScale, targetScale, desired.Rate)
		if e != nil {
			return commands.Mutation{}, e
		}
		feeHistory := basicHistory{}
		if state.FeeID != "" {
			feeHistory, e = readBasicHistory(ctx, tx, workspace, state.FeeID)
			if e != nil {
				return commands.Mutation{}, e
			}
		}
		if desired.Fee != nil {
			if e = validateAccountDate(ctx, tx, accounts[desired.Fee.AccountID], occurred); e != nil {
				return commands.Mutation{}, e
			}
			fee := feeBasic(desired, *desired.Fee)
			if state.FeeID != "" {
				if accounts[desired.Fee.AccountID].Currency != accounts[state.FeeAccount].Currency {
					return commands.Mutation{}, domainReject(422, "validation_error")
				}
				if e = validateBasicReplacement(ctx, tx, workspace, state.FeeID, state.FeeAccount, state.FeeAmount, state.FeeDate, feeHistory, fee, occurred); e != nil {
					return commands.Mutation{}, e
				}
			} else {
				if e = checkBasicAmount(fee); e != nil {
					return commands.Mutation{}, e
				}
				if e = checkReferences(ctx, tx, workspace, fee, nil, nil); e != nil {
					return commands.Mutation{}, e
				}
			}
		} else if state.FeeID != "" {
			var refunds bool
			if e = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM transactions WHERE workspace_id=$1 AND parent_transaction_id=$2 AND kind='refund' AND deleted_at IS NULL)`, workspace, state.FeeID).Scan(&refunds); e != nil {
				return commands.Mutation{}, e
			}
			if refunds {
				return commands.Mutation{}, domainReject(409, "dependent_transactions")
			}
		}
		affected := map[string]bool{}
		affect := func(ids ...string) {
			for _, accountID := range ids {
				affected[accountID] = true
			}
		}
		if state.Source.AccountID != source.ID || state.Source.Amount != "-"+desired.SourceAmount || !state.Date.Equal(occurred) {
			affect(state.Source.AccountID, source.ID)
		}
		if state.Target.AccountID != target.ID || state.Target.Amount != desired.TargetAmount || !state.Date.Equal(occurred) {
			affect(state.Target.AccountID, target.ID)
		}
		if state.FeeID != "" && (desired.Fee == nil || state.FeeAccount != desired.Fee.AccountID || state.FeeAmount != "-"+desired.Fee.Amount || !state.FeeDate.Equal(occurred)) {
			affect(state.FeeAccount)
			if desired.Fee != nil {
				affect(desired.Fee.AccountID)
			}
		}
		if state.FeeID == "" && desired.Fee != nil {
			affect(desired.Fee.AccountID)
		}
		if _, e = tx.Exec(ctx, `UPDATE transactions SET occurred_at=$3,occurred_timezone=$4,note=$5,payee=$6,rate_numerator=$7::numeric,rate_denominator=$8::numeric,version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, id, occurred, desired.Timezone, desired.Note, desired.Payee, rate.Numerator, rate.Denominator); e != nil {
			return commands.Mutation{}, e
		}
		if state.Source.AccountID != source.ID || state.Target.AccountID != target.ID || state.Source.Amount != "-"+desired.SourceAmount || state.Target.Amount != desired.TargetAmount {
			// Keep entry UUIDs even when the source and target accounts swap. Deleting
			// then inserting avoids immediate uniqueness violations for the account pair.
			if _, e = tx.Exec(ctx, `DELETE FROM entries WHERE workspace_id=$1 AND transaction_id=$2`, workspace, id); e != nil {
				return commands.Mutation{}, e
			}
			sourceAmount, _ := ParsePositiveMoney(desired.SourceAmount)
			targetAmount, _ := ParsePositiveMoney(desired.TargetAmount)
			if _, e = tx.Exec(ctx, `INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) VALUES($1,$2,$3,$4,$5),($6,$2,$3,$7,$8)`, state.Source.ID, workspace, id, source.ID, -sourceAmount.Minor(), state.Target.ID, target.ID, targetAmount.Minor()); e != nil {
				return commands.Mutation{}, e
			}
		}
		if e = replaceTransactionTags(ctx, tx, workspace, id, desired.TagIDs); e != nil {
			return commands.Mutation{}, e
		}
		changes := []commands.Change{}
		if desired.Fee == nil && state.FeeID != "" {
			change, e := softDeleteTransaction(ctx, tx, workspace, state.FeeID, state.FeeVersion)
			if e != nil {
				return commands.Mutation{}, e
			}
			changes = append(changes, change)
		} else if desired.Fee != nil {
			fee := feeBasic(desired, *desired.Fee)
			amount, _ := ParsePositiveMoney(fee.Amount)
			feeVersion := int64(1)
			if state.FeeID == "" {
				inserted, e := tx.Exec(ctx, `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,note,payee,parent_transaction_id) VALUES($1,$2,'expense',$3,$4,$5,$6,$7,$8) ON CONFLICT(id) DO NOTHING`, fee.ID, workspace, state.Status, occurred, fee.Timezone, fee.Note, fee.Payee, id)
				if e != nil {
					return commands.Mutation{}, e
				}
				if inserted.RowsAffected() != 1 {
					return commands.Mutation{}, domainReject(409, "validation_error")
				}
				if _, e = tx.Exec(ctx, `INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) VALUES($1,$2,$3,$4,$5)`, newID(), workspace, fee.ID, fee.AccountID, -amount.Minor()); e != nil {
					return commands.Mutation{}, e
				}
				if e = writeBasicParts(ctx, tx, workspace, fee.ID, fee); e != nil {
					return commands.Mutation{}, e
				}
			} else {
				feeVersion = state.FeeVersion + 1
				if _, e = tx.Exec(ctx, `UPDATE transactions SET occurred_at=$3,occurred_timezone=$4,note=$5,payee=$6,version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, fee.ID, occurred, fee.Timezone, fee.Note, fee.Payee); e != nil {
					return commands.Mutation{}, e
				}
				if _, e = tx.Exec(ctx, `UPDATE entries SET account_id=$3,amount_minor=$4 WHERE workspace_id=$1 AND transaction_id=$2`, workspace, fee.ID, fee.AccountID, -amount.Minor()); e != nil {
					return commands.Mutation{}, e
				}
				if e = writeBasicReplacement(ctx, tx, workspace, fee.ID, feeHistory, fee); e != nil {
					return commands.Mutation{}, e
				}
			}
			data, e := ReadTransaction(ctx, tx, workspace, fee.ID)
			if e != nil {
				return commands.Mutation{}, e
			}
			changes = append(changes, commands.Change{EntityType: "transaction", ID: fee.ID, Version: strconv.FormatInt(feeVersion, 10), Operation: "upsert", Data: data})
		}
		ordered := []string{}
		for accountID := range affected {
			ordered = append(ordered, accountID)
		}
		sort.Strings(ordered)
		for _, accountID := range ordered {
			change, e := bumpAccount(ctx, tx, workspace, accounts[accountID])
			if e != nil {
				return commands.Mutation{}, e
			}
			changes = append(changes, change)
		}
		data, e := ReadTransaction(ctx, tx, workspace, id)
		if e != nil {
			return commands.Mutation{}, e
		}
		changes = append(changes, commands.Change{EntityType: "transaction", ID: id, Version: strconv.FormatInt(state.Version+1, 10), Operation: "upsert", Data: data})
		return commands.Mutation{Status: 200, Changes: changes}, nil
	}
}
