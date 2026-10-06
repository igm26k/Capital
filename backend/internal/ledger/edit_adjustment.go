package ledger

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"context"
	"encoding/json"
	"errors"
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

type AdjustmentCreateInput struct {
	ID                     string `json:"id"`
	ExpectedBalanceVersion string `json:"expected_balance_version"`
	Target                 string `json:"target_balance_minor"`
	Reason                 string `json:"reason"`
	Note                   string `json:"note"`
	Timezone               string `json:"occurred_timezone"`
}
type AdjustmentReplaceInput struct {
	Kind            string `json:"kind"`
	ExpectedVersion string `json:"expected_version"`
	Note            string `json:"note"`
}

func DecodeAdjustmentCreate(body []byte) (AdjustmentCreateInput, error) {
	var input AdjustmentCreateInput
	if e := decodeFields(body, &input, "id", "expected_balance_version", "target_balance_minor", "reason", "note", "occurred_timezone"); e != nil {
		return input, e
	}
	if !uuid(input.ID) || !validText(input.Reason, 500) || input.Reason == "" || !validText(input.Note, 2000) || !validZone(input.Timezone) {
		return input, invalid()
	}
	if _, e := version(input.ExpectedBalanceVersion); e != nil {
		return input, e
	}
	if _, e := ParseMoney(input.Target); e != nil {
		return input, invalid()
	}
	input.ID = strings.ToLower(input.ID)
	return input, nil
}
func DecodeAdjustmentReplace(body []byte) (AdjustmentReplaceInput, error) {
	var input AdjustmentReplaceInput
	if e := decodeFields(body, &input, "kind", "expected_version", "note"); e != nil {
		return input, e
	}
	if input.Kind != "adjustment" || !validText(input.Note, 2000) {
		return input, invalid()
	}
	if _, e := version(input.ExpectedVersion); e != nil {
		return input, e
	}
	return input, nil
}

func CreateAdjustment(workspace, accountID string, input AdjustmentCreateInput) commands.Command {
	accountID = strings.ToLower(accountID)
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		if !uuid(accountID) {
			return commands.Mutation{}, &identity.Error{Status: 400, Code: "bad_request"}
		}
		body, e := json.Marshal(input)
		if e != nil {
			return commands.Mutation{}, e
		}
		desired, e := DecodeAdjustmentCreate(body)
		if e != nil {
			return commands.Mutation{}, e
		}
		accounts, e := lockAccounts(ctx, tx, workspace, accountID)
		if e != nil {
			return commands.Mutation{}, e
		}
		account := accounts[accountID]
		expected, _ := version(desired.ExpectedBalanceVersion)
		if expected != account.BalanceVersion {
			return commands.Mutation{}, &commands.Rejection{Status: 409, Code: "version_conflict", Message: "Остаток счета изменен.", CurrentVersions: []any{map[string]any{"entity_type": "account", "id": accountID, "version": strconv.FormatInt(account.Version, 10), "balance_version": strconv.FormatInt(account.BalanceVersion, 10)}}}
		}
		var now time.Time
		if e = tx.QueryRow(ctx, `SELECT clock_timestamp()`).Scan(&now); e != nil {
			return commands.Mutation{}, e
		}
		if e = validateAccountDate(ctx, tx, account, now); e != nil {
			return commands.Mutation{}, e
		}
		var posted string
		if e = tx.QueryRow(ctx, `SELECT coalesce(sum(e.amount_minor),0)::text FROM entries e JOIN transactions t ON (t.workspace_id,t.id)=(e.workspace_id,e.transaction_id) WHERE e.workspace_id=$1 AND e.account_id=$2 AND t.deleted_at IS NULL AND t.status='posted'`, workspace, accountID).Scan(&posted); e != nil {
			return commands.Mutation{}, e
		}
		current, e := ParseMoney(posted)
		if e != nil {
			return commands.Mutation{}, domainReject(422, "amount_out_of_range")
		}
		target, _ := ParseMoney(desired.Target)
		delta, e := SumMoney(target, current.Negate())
		if e != nil {
			return commands.Mutation{}, domainReject(422, "amount_out_of_range")
		}
		if delta.Minor() == 0 {
			return commands.Mutation{}, domainReject(422, "no_change")
		}
		inserted, e := tx.Exec(ctx, `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,reason,note) VALUES($1,$2,'adjustment','posted',$3,$4,$5,$6) ON CONFLICT(id) DO NOTHING`, desired.ID, workspace, now, desired.Timezone, desired.Reason, desired.Note)
		if e != nil {
			return commands.Mutation{}, e
		}
		if inserted.RowsAffected() != 1 {
			return commands.Mutation{}, domainReject(409, "validation_error")
		}
		if _, e = tx.Exec(ctx, `INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) VALUES($1,$2,$3,$4,$5)`, newID(), workspace, desired.ID, accountID, delta.Minor()); e != nil {
			return commands.Mutation{}, e
		}
		accountChange, e := bumpAccount(ctx, tx, workspace, account)
		if e != nil {
			return commands.Mutation{}, e
		}
		data, e := ReadTransaction(ctx, tx, workspace, desired.ID)
		if e != nil {
			return commands.Mutation{}, e
		}
		return commands.Mutation{Status: 201, Changes: []commands.Change{accountChange, {EntityType: "transaction", ID: desired.ID, Version: "1", Operation: "upsert", Data: data}}}, nil
	}
}

func ReplaceAdjustment(workspace, id string, input AdjustmentReplaceInput) commands.Command {
	id = strings.ToLower(id)
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		if !uuid(id) {
			return commands.Mutation{}, &identity.Error{Status: 400, Code: "bad_request"}
		}
		body, e := json.Marshal(input)
		if e != nil {
			return commands.Mutation{}, e
		}
		desired, e := DecodeAdjustmentReplace(body)
		if e != nil {
			return commands.Mutation{}, e
		}
		var kind, accountID string
		e = tx.QueryRow(ctx, `SELECT t.kind,coalesce(e.account_id::text,'') FROM transactions t LEFT JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id) WHERE t.workspace_id=$1 AND t.id=$2 AND t.deleted_at IS NULL`, workspace, id).Scan(&kind, &accountID)
		if errors.Is(e, pgx.ErrNoRows) {
			return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
		}
		if e != nil {
			return commands.Mutation{}, e
		}
		if kind != "adjustment" {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		accounts, e := lockAccounts(ctx, tx, workspace, accountID)
		if e != nil {
			return commands.Mutation{}, e
		}
		var current int64
		if e = tx.QueryRow(ctx, `SELECT version FROM transactions WHERE workspace_id=$1 AND id=$2 FOR UPDATE`, workspace, id).Scan(&current); e != nil {
			return commands.Mutation{}, e
		}
		expected, _ := version(desired.ExpectedVersion)
		if expected != current {
			return commands.Mutation{}, transactionVersionConflict(id, current)
		}
		if current == math.MaxInt64 {
			return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
		}
		if accounts[accountID].Archived {
			return commands.Mutation{}, domainReject(422, "account_archived")
		}
		if _, e = tx.Exec(ctx, `UPDATE transactions SET note=$3,version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, id, desired.Note); e != nil {
			return commands.Mutation{}, e
		}
		data, e := ReadTransaction(ctx, tx, workspace, id)
		if e != nil {
			return commands.Mutation{}, e
		}
		return commands.Mutation{Status: 200, Changes: []commands.Change{{EntityType: "transaction", ID: id, Version: strconv.FormatInt(current+1, 10), Operation: "upsert", Data: data}}}, nil
	}
}
