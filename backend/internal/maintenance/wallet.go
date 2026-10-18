// Package maintenance contains offline, operator-authorized maintenance commands.
package maintenance

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	commands "accounting/backend/internal/sync"
	"context"
	"encoding/json"
	"fmt"
	"github.com/jackc/pgx/v5"
	"strconv"
	"time"
)

type WalletAccount struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	RenameTo string `json:"rename_to,omitempty"`
	Currency string `json:"currency"`
	Type     string `json:"type"`
	// nil requires the account not to exist; otherwise compare the as-of balance.
	Expected     *string `json:"expected_balance_minor"`
	Target       string  `json:"target_balance_minor"`
	AdjustmentID string  `json:"adjustment_id"`
}
type WalletPlan struct {
	Workspace            string          `json:"workspace"`
	Actor                string          `json:"actor"`
	Generation           string          `json:"generation"`
	ActionID             string          `json:"action_id"`
	AsOf                 string          `json:"as_of"`
	OpenedAt             string          `json:"opened_at"`
	ExpectedTransactions int             `json:"expected_transactions"`
	Accounts             []WalletAccount `json:"accounts"`
}

func ReconcileWallet(plan WalletPlan) commands.Command {
	return func(ctx context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
		result := commands.Mutation{Status: 200}
		asOf, err := time.Parse(time.RFC3339Nano, plan.AsOf)
		if err != nil {
			return result, err
		}
		opened, err := time.Parse(time.RFC3339Nano, plan.OpenedAt)
		if err != nil || opened.After(asOf) {
			return result, fmt.Errorf("invalid opening date")
		}
		if len(plan.Accounts) == 0 {
			return result, fmt.Errorf("empty plan")
		}
		rows, err := tx.Query(ctx, `SELECT id FROM accounts WHERE workspace_id=$1 AND deleted_at IS NULL ORDER BY id FOR UPDATE`, plan.Workspace)
		if err != nil {
			return result, err
		}
		count := 0
		for rows.Next() {
			count++
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return result, err
		}
		expectedCount := 0
		seen := map[string]bool{}
		for _, a := range plan.Accounts {
			if seen[a.ID] {
				return result, fmt.Errorf("duplicate account")
			}
			seen[a.ID] = true
			if a.Expected != nil {
				expectedCount++
			}
		}
		if count != expectedCount {
			return result, fmt.Errorf("account count changed: %d != %d", count, expectedCount)
		}
		var transactions int
		if err = tx.QueryRow(ctx, `SELECT count(*) FROM transactions WHERE workspace_id=$1 AND deleted_at IS NULL`, plan.Workspace).Scan(&transactions); err != nil {
			return result, err
		}
		if transactions != plan.ExpectedTransactions {
			return result, fmt.Errorf("transaction count changed: %d != %d", transactions, plan.ExpectedTransactions)
		}
		// Domain commands can update an account more than once. Journal its final
		// aggregate once, retaining every distinct transaction change.
		positions := map[string]int{}
		appendMutation := func(m commands.Mutation) {
			for _, c := range m.Changes {
				key := c.EntityType + ":" + c.ID
				if i, ok := positions[key]; ok {
					result.Changes[i] = c
				} else {
					positions[key] = len(result.Changes)
					result.Changes = append(result.Changes, c)
				}
			}
		}
		balance := func(id string) (string, error) {
			var value string
			err := tx.QueryRow(ctx, `SELECT coalesce(sum(e.amount_minor),0)::text FROM entries e JOIN transactions t ON (t.workspace_id,t.id)=(e.workspace_id,e.transaction_id) WHERE e.workspace_id=$1 AND e.account_id=$2 AND t.deleted_at IS NULL AND t.status='posted' AND t.occurred_at<=$3`, plan.Workspace, id, asOf).Scan(&value)
			return value, err
		}
		for _, a := range plan.Accounts {
			target, err := ledger.ParseMoney(a.Target)
			if err != nil {
				return result, err
			}
			if a.Expected == nil {
				m, err := ledger.CreateAccount(plan.Workspace, ledger.AccountCreate{ID: a.ID, Name: a.Name, Type: a.Type, Currency: a.Currency, OpenedAt: plan.OpenedAt, Timezone: "UTC", OpeningBalance: a.Target})(ctx, tx, p)
				if err != nil {
					return result, err
				}
				appendMutation(m)
			} else {
				var name, currency, kind string
				var version int64
				var date time.Time
				err = tx.QueryRow(ctx, `SELECT name,currency_code,type,version,opened_at FROM accounts WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL AND archived_at IS NULL`, plan.Workspace, a.ID).Scan(&name, &currency, &kind, &version, &date)
				if err != nil {
					return result, err
				}
				if name != a.Name || currency != a.Currency || kind != a.Type || !date.Equal(opened) {
					return result, fmt.Errorf("account metadata changed: %s", a.ID)
				}
				actual, err := balance(a.ID)
				if err != nil {
					return result, err
				}
				if actual != *a.Expected {
					return result, fmt.Errorf("balance changed: %s", a.ID)
				}
				before, err := ledger.ParseMoney(actual)
				if err != nil {
					return result, err
				}
				delta := target.Minor() - before.Minor()
				if a.RenameTo != "" && a.RenameTo != a.Name {
					m, err := ledger.UpdateAccount(plan.Workspace, a.ID, ledger.AccountUpdate{ExpectedVersion: strconv.FormatInt(version, 10), Name: a.RenameTo, Type: kind})(ctx, tx, p)
					if err != nil {
						return result, err
					}
					appendMutation(m)
				}
				if delta != 0 {
					m, err := ledger.CreateHistoricalAdjustment(plan.Workspace, ledger.HistoricalAdjustmentInput{ID: a.AdjustmentID, Kind: "adjustment", AccountID: a.ID, Amount: strconv.FormatInt(delta, 10), OccurredAt: plan.OpenedAt, Timezone: "UTC", Reason: "Начальный остаток Wallet", Note: "Восстановлен по подтвержденному владельцем остатку на " + plan.AsOf})(ctx, tx, p)
					if err != nil {
						return result, err
					}
					appendMutation(m)
				}
			}
			actual, err := balance(a.ID)
			if err != nil {
				return result, err
			}
			if actual != a.Target {
				return result, fmt.Errorf("target balance mismatch: %s", a.ID)
			}
		}
		if len(result.Changes) == 0 {
			return result, fmt.Errorf("no changes")
		}
		return result, nil
	}
}

func Request(plan WalletPlan) (commands.Request, error) {
	body, err := json.Marshal(plan)
	return commands.Request{WorkspaceID: plan.Workspace, ActionID: plan.ActionID, GenerationID: plan.Generation, RequestID: plan.ActionID, Method: "POST", Path: "/api/v1/workspaces/" + plan.Workspace + "/maintenance/wallet-balances", Body: body}, err
}
