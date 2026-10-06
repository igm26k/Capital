package ledger

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"context"
	"encoding/json"
	"math/big"
	"sort"
	"strings"

	"github.com/jackc/pgx/v5"
)

// Rate expresses target major units per source major unit; no decimal floats.
type Rate struct {
	Numerator   string `json:"numerator"`
	Denominator string `json:"denominator"`
}
type FeeInput struct {
	ID          string            `json:"id"`
	AccountID   string            `json:"account_id"`
	Amount      string            `json:"amount_minor"`
	Allocations []AllocationInput `json:"allocations"`
	Note        string            `json:"note"`
	TagIDs      []string          `json:"tag_ids"`
}
type TransferInput struct {
	ID                 string    `json:"id,omitempty"`
	ExpectedVersion    string    `json:"expected_version,omitempty"`
	ExpectedFeeVersion *string   `json:"expected_fee_version,omitempty"`
	Kind               string    `json:"kind"`
	SourceAccountID    string    `json:"source_account_id"`
	TargetAccountID    string    `json:"target_account_id"`
	SourceAmount       string    `json:"source_amount_minor"`
	TargetAmount       string    `json:"target_amount_minor"`
	OccurredAt         string    `json:"occurred_at"`
	Timezone           string    `json:"occurred_timezone"`
	Note               string    `json:"note"`
	Payee              string    `json:"payee"`
	TagIDs             []string  `json:"tag_ids"`
	Rate               *Rate     `json:"rate"`
	Fee                *FeeInput `json:"fee"`
}

func positiveRateInteger(s string) bool {
	return len(s) <= 40 && canonicalInteger(s) && s != "0" && s[0] != '-'
}
func DecodeTransferCreate(body []byte) (TransferInput, error) {
	return decodeTransfer(body, false)
}
func DecodeTransferReplace(body []byte) (TransferInput, error) {
	return decodeTransfer(body, true)
}
func decodeTransfer(body []byte, update bool) (TransferInput, error) {
	var input TransferInput
	fields := []string{"kind", "source_account_id", "target_account_id", "source_amount_minor", "target_amount_minor", "occurred_at", "occurred_timezone", "note", "payee", "tag_ids", "rate", "fee"}
	nullable := []string{"rate", "fee"}
	if update {
		fields = append(fields, "expected_version", "expected_fee_version")
		nullable = append(nullable, "expected_fee_version")
	} else {
		fields = append(fields, "id")
	}
	if e := decodeObject(body, &input, fields, nullable); e != nil {
		return input, e
	}
	if input.Kind != "transfer" || (!update && !uuid(input.ID)) || !uuid(input.SourceAccountID) || !uuid(input.TargetAccountID) || !validZone(input.Timezone) || !validText(input.Note, 2000) || !validText(input.Payee, 200) {
		return input, invalid()
	}
	if _, e := ParseInstant(input.OccurredAt); e != nil {
		return input, e
	}
	if _, e := ParsePositiveMoney(input.SourceAmount); e != nil {
		return input, invalid()
	}
	if _, e := ParsePositiveMoney(input.TargetAmount); e != nil {
		return input, invalid()
	}
	if update {
		if _, e := version(input.ExpectedVersion); e != nil {
			return input, e
		}
		if input.ExpectedFeeVersion != nil {
			if _, e := version(*input.ExpectedFeeVersion); e != nil {
				return input, e
			}
		}
	}
	input.ID = strings.ToLower(input.ID)
	input.SourceAccountID = strings.ToLower(input.SourceAccountID)
	input.TargetAccountID = strings.ToLower(input.TargetAccountID)
	if len(input.TagIDs) > 50 {
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
		Rate json.RawMessage `json:"rate"`
		Fee  json.RawMessage `json:"fee"`
	}
	if e := json.Unmarshal(body, &raw); e != nil {
		return input, invalid()
	}
	if input.Rate != nil {
		var rate Rate
		if e := decodeFields(raw.Rate, &rate, "numerator", "denominator"); e != nil {
			return input, e
		}
		if !positiveRateInteger(rate.Numerator) || !positiveRateInteger(rate.Denominator) {
			return input, invalid()
		}
		input.Rate = &rate
	}
	if input.Fee != nil {
		var fee FeeInput
		if e := decodeFields(raw.Fee, &fee, "id", "account_id", "amount_minor", "allocations", "note", "tag_ids"); e != nil {
			return input, e
		}
		var feeFields map[string]json.RawMessage
		if e := json.Unmarshal(raw.Fee, &feeFields); e != nil {
			return input, invalid()
		}
		// Preserve original nested allocation objects for exact-shape validation.
		wire := map[string]any{"id": fee.ID, "kind": "expense", "account_id": fee.AccountID, "amount_minor": fee.Amount, "occurred_at": input.OccurredAt, "occurred_timezone": input.Timezone, "note": fee.Note, "payee": input.Payee, "tag_ids": feeFields["tag_ids"], "allocations": feeFields["allocations"]}
		b, e := json.Marshal(wire)
		if e != nil {
			return input, e
		}
		basic, e := DecodeBasic(b, false)
		if e != nil {
			return input, e
		}
		if basic.ID == input.ID {
			return input, invalid()
		}
		input.Fee = &FeeInput{ID: basic.ID, AccountID: basic.AccountID, Amount: basic.Amount, Allocations: basic.Allocations, Note: basic.Note, TagIDs: basic.TagIDs}
	}
	return input, nil
}
func feeBasic(input TransferInput, fee FeeInput) BasicInput {
	return BasicInput{ID: fee.ID, Kind: "expense", AccountID: fee.AccountID, Amount: fee.Amount, OccurredAt: input.OccurredAt, Timezone: input.Timezone, Note: fee.Note, Payee: input.Payee, TagIDs: fee.TagIDs, Allocations: fee.Allocations}
}

// ExactTransferRate computes and reduces (D * 10^s)/(S * 10^d), then
// verifies an optional client fraction by exact cross multiplication.
func ExactTransferRate(source, target string, sourceScale, targetScale int, provided *Rate) (Rate, error) {
	var result Rate
	s, e := ParsePositiveMoney(source)
	if e != nil {
		return result, invalid()
	}
	d, e := ParsePositiveMoney(target)
	if e != nil {
		return result, invalid()
	}
	if sourceScale < 0 || sourceScale > 3 || targetScale < 0 || targetScale > 3 {
		return result, invalid()
	}
	pow := func(scale int) *big.Int { return new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(scale)), nil) }
	n := new(big.Int).Mul(big.NewInt(d.Minor()), pow(sourceScale))
	den := new(big.Int).Mul(big.NewInt(s.Minor()), pow(targetScale))
	gcd := new(big.Int).GCD(nil, nil, n, den)
	n.Quo(n, gcd)
	den.Quo(den, gcd)
	if provided != nil {
		if !positiveRateInteger(provided.Numerator) || !positiveRateInteger(provided.Denominator) {
			return result, invalid()
		}
		pn, _ := new(big.Int).SetString(provided.Numerator, 10)
		pd, _ := new(big.Int).SetString(provided.Denominator, 10)
		if new(big.Int).Mul(pn, den).Cmp(new(big.Int).Mul(pd, n)) != 0 {
			return result, domainReject(422, "validation_error")
		}
	}
	return Rate{Numerator: n.String(), Denominator: den.String()}, nil
}

// CreateTransfer is one CommandRunner command: transfer, optional fee, balances,
// versions, journal and receipt commit together. Shared accounts bump only once.
func CreateTransfer(workspace string, input TransferInput) commands.Command {
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		input := input
		b, e := json.Marshal(input)
		if e != nil {
			return commands.Mutation{}, e
		}
		input, e = DecodeTransferCreate(b)
		if e != nil {
			return commands.Mutation{}, e
		}
		ids := []string{input.SourceAccountID, input.TargetAccountID}
		if input.Fee != nil {
			ids = append(ids, input.Fee.AccountID)
		}
		accounts, e := lockAccounts(ctx, tx, workspace, ids...)
		if e != nil {
			return commands.Mutation{}, e
		}
		if input.SourceAccountID == input.TargetAccountID {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		source, target := accounts[input.SourceAccountID], accounts[input.TargetAccountID]
		if source.Currency == target.Currency && input.SourceAmount != input.TargetAmount {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		ordered := make([]string, 0, len(accounts))
		for id := range accounts {
			ordered = append(ordered, id)
		}
		sort.Strings(ordered)
		occurred, _ := ParseInstant(input.OccurredAt)
		for _, id := range ordered {
			if e = validateAccountDate(ctx, tx, accounts[id], occurred); e != nil {
				return commands.Mutation{}, e
			}
		}
		if e = checkReferences(ctx, tx, workspace, BasicInput{TagIDs: input.TagIDs}, nil, nil); e != nil {
			return commands.Mutation{}, e
		}
		if input.Fee != nil {
			fee := feeBasic(input, *input.Fee)
			if e = checkBasicAmount(fee); e != nil {
				return commands.Mutation{}, e
			}
			if e = checkReferences(ctx, tx, workspace, fee, nil, nil); e != nil {
				return commands.Mutation{}, e
			}
		}
		var sourceScale, targetScale int
		if e = tx.QueryRow(ctx, `SELECT (SELECT scale FROM currencies WHERE code=$1),(SELECT scale FROM currencies WHERE code=$2)`, source.Currency, target.Currency).Scan(&sourceScale, &targetScale); e != nil {
			return commands.Mutation{}, e
		}
		rate, e := ExactTransferRate(input.SourceAmount, input.TargetAmount, sourceScale, targetScale, input.Rate)
		if e != nil {
			return commands.Mutation{}, e
		}
		inserted, e := tx.Exec(ctx, `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,note,payee,rate_numerator,rate_denominator) VALUES($1,$2,'transfer','posted',$3,$4,$5,$6,$7::numeric,$8::numeric) ON CONFLICT(id) DO NOTHING`, input.ID, workspace, occurred, input.Timezone, input.Note, input.Payee, rate.Numerator, rate.Denominator)
		if e != nil {
			return commands.Mutation{}, e
		}
		if inserted.RowsAffected() != 1 {
			return commands.Mutation{}, domainReject(409, "validation_error")
		}
		sourceAmount, _ := ParsePositiveMoney(input.SourceAmount)
		targetAmount, _ := ParsePositiveMoney(input.TargetAmount)
		if _, e = tx.Exec(ctx, `INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) VALUES($1,$2,$3,$4,$5),($6,$2,$3,$7,$8)`, newID(), workspace, input.ID, source.ID, -sourceAmount.Minor(), newID(), target.ID, targetAmount.Minor()); e != nil {
			return commands.Mutation{}, e
		}
		for _, tag := range input.TagIDs {
			if _, e = tx.Exec(ctx, `INSERT INTO transaction_tags(workspace_id,transaction_id,tag_id) VALUES($1,$2,$3)`, workspace, input.ID, tag); e != nil {
				return commands.Mutation{}, e
			}
		}
		if input.Fee != nil {
			fee := feeBasic(input, *input.Fee)
			inserted, e = tx.Exec(ctx, `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,note,payee,parent_transaction_id) VALUES($1,$2,'expense','posted',$3,$4,$5,$6,$7) ON CONFLICT(id) DO NOTHING`, fee.ID, workspace, occurred, fee.Timezone, fee.Note, fee.Payee, input.ID)
			if e != nil {
				return commands.Mutation{}, e
			}
			if inserted.RowsAffected() != 1 {
				return commands.Mutation{}, domainReject(409, "validation_error")
			}
			amount, _ := ParsePositiveMoney(fee.Amount)
			if _, e = tx.Exec(ctx, `INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) VALUES($1,$2,$3,$4,$5)`, newID(), workspace, fee.ID, fee.AccountID, -amount.Minor()); e != nil {
				return commands.Mutation{}, e
			}
			if e = writeBasicParts(ctx, tx, workspace, fee.ID, fee); e != nil {
				return commands.Mutation{}, e
			}
		}
		changes := []commands.Change{}
		for _, id := range ordered {
			change, e := bumpAccount(ctx, tx, workspace, accounts[id])
			if e != nil {
				return commands.Mutation{}, e
			}
			changes = append(changes, change)
		}
		transactionIDs := []string{input.ID}
		if input.Fee != nil {
			transactionIDs = append(transactionIDs, input.Fee.ID)
		}
		for _, id := range transactionIDs {
			data, e := ReadTransaction(ctx, tx, workspace, id)
			if e != nil {
				return commands.Mutation{}, e
			}
			changes = append(changes, commands.Change{EntityType: "transaction", ID: id, Version: "1", Operation: "upsert", Data: data})
		}
		return commands.Mutation{Status: 201, Changes: changes}, nil
	}
}
