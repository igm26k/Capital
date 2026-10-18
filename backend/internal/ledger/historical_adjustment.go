package ledger

import (
	"context"
	"encoding/json"
	"strings"

	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"github.com/jackc/pgx/v5"
)

// HistoricalAdjustmentInput records a known signed movement from an imported
// ledger. Unlike a balance reconciliation, it does not set today's balance.
type HistoricalAdjustmentInput struct {
	ID         string `json:"id"`
	Kind       string `json:"kind"`
	AccountID  string `json:"account_id"`
	Amount     string `json:"amount_minor"`
	OccurredAt string `json:"occurred_at"`
	Timezone   string `json:"occurred_timezone"`
	Reason     string `json:"reason"`
	Note       string `json:"note"`
	Payee      string `json:"payee"`
}

func DecodeHistoricalAdjustment(body []byte) (HistoricalAdjustmentInput, error) {
	var input HistoricalAdjustmentInput
	if err := decodeFields(body, &input, "id", "kind", "account_id", "amount_minor", "occurred_at", "occurred_timezone", "reason", "note", "payee"); err != nil {
		return input, err
	}
	if input.Kind != "adjustment" || !uuid(input.ID) || !uuid(input.AccountID) || !validZone(input.Timezone) || strings.TrimSpace(input.Reason) == "" || !validText(input.Reason, 500) || !validText(input.Note, 2000) || !validText(input.Payee, 200) {
		return input, invalid()
	}
	if _, err := ParseInstant(input.OccurredAt); err != nil {
		return input, err
	}
	amount, err := ParseMoney(input.Amount)
	if err != nil || amount.Minor() == 0 {
		return input, invalid()
	}
	input.ID = strings.ToLower(input.ID)
	input.AccountID = strings.ToLower(input.AccountID)
	return input, nil
}

func CreateHistoricalAdjustment(workspace string, input HistoricalAdjustmentInput) commands.Command {
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		body, err := json.Marshal(input)
		if err != nil {
			return commands.Mutation{}, err
		}
		desired, err := DecodeHistoricalAdjustment(body)
		if err != nil {
			return commands.Mutation{}, err
		}
		accounts, err := lockAccounts(ctx, tx, workspace, desired.AccountID)
		if err != nil {
			return commands.Mutation{}, err
		}
		account := accounts[desired.AccountID]
		occurred, _ := ParseInstant(desired.OccurredAt)
		if err = validateAccountDate(ctx, tx, account, occurred); err != nil {
			return commands.Mutation{}, err
		}
		inserted, err := tx.Exec(ctx, `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,reason,note,payee) VALUES($1,$2,'adjustment','posted',$3,$4,$5,$6,$7) ON CONFLICT(id) DO NOTHING`, desired.ID, workspace, occurred, desired.Timezone, desired.Reason, desired.Note, desired.Payee)
		if err != nil {
			return commands.Mutation{}, err
		}
		if inserted.RowsAffected() != 1 {
			return commands.Mutation{}, domainReject(409, "validation_error")
		}
		amount, _ := ParseMoney(desired.Amount)
		if _, err = tx.Exec(ctx, `INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) VALUES($1,$2,$3,$4,$5)`, newID(), workspace, desired.ID, desired.AccountID, amount.Minor()); err != nil {
			return commands.Mutation{}, err
		}
		accountChange, err := bumpAccount(ctx, tx, workspace, account)
		if err != nil {
			return commands.Mutation{}, err
		}
		data, err := ReadTransaction(ctx, tx, workspace, desired.ID)
		if err != nil {
			return commands.Mutation{}, err
		}
		return commands.Mutation{Status: 201, Changes: []commands.Change{accountChange, {EntityType: "transaction", ID: desired.ID, Version: "1", Operation: "upsert", Data: data}}}, nil
	}
}
