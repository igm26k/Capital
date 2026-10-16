package ledger

import (
	"context"
	"encoding/json"
	"time"

	"accounting/backend/internal/identity"
	"github.com/jackc/pgx/v5"
)

// ReportQuery uses the same exact-category/tag/account semantics as history.
// Balances are account-scoped; classification filters apply only to cash flow.
type ReportQuery struct {
	From, To                             time.Time
	AccountID, CategoryID, TagID, Status string
}

type Report struct {
	From          string            `json:"from"`
	To            string            `json:"to"`
	Status        string            `json:"status"`
	Currencies    []json.RawMessage `json:"currencies"`
	Categories    []json.RawMessage `json:"categories"`
	Balances      []json.RawMessage `json:"balances"`
	DailyBalances []json.RawMessage `json:"daily_balances"`
}

const reportCash = `WITH cash AS (
 SELECT t.id,t.kind,t.status,a.currency_code currency,p.category_id,
 p.amount_minor::numeric * CASE WHEN t.kind='refund' THEN -1 ELSE 1 END amount
 FROM transactions t
 JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id)
 JOIN accounts a ON (a.workspace_id,a.id)=(e.workspace_id,e.account_id)
 JOIN allocations p ON (p.workspace_id,p.transaction_id)=(t.workspace_id,t.id)
 WHERE t.workspace_id=$1 AND t.deleted_at IS NULL AND t.kind IN ('income','expense','refund')
 AND t.occurred_at >= $2 AND t.occurred_at < $3
 AND ($4='' OR e.account_id=NULLIF($4,'')::uuid)
 AND ($5='' OR p.category_id=NULLIF($5,'')::uuid)
 AND ($6='' OR EXISTS(SELECT 1 FROM transaction_tags tt WHERE tt.workspace_id=t.workspace_id AND tt.transaction_id=t.id AND tt.tag_id=NULLIF($6,'')::uuid))
 AND ($7='all' OR t.status=$7)) `

func ReadReport(ctx context.Context, tx pgx.Tx, workspace string, q ReportQuery) (Report, error) {
	result := Report{From: q.From.UTC().Format(time.RFC3339Nano), To: q.To.UTC().Format(time.RFC3339Nano), Status: q.Status,
		Currencies: []json.RawMessage{}, Categories: []json.RawMessage{}, Balances: []json.RawMessage{}, DailyBalances: []json.RawMessage{}}
	if !q.From.Before(q.To) || q.To.Sub(q.From) > 366*24*time.Hour || q.Status != "all" && q.Status != "posted" && q.Status != "pending" {
		return result, &identity.Error{Status: 400, Code: "bad_request"}
	}
	if q.AccountID != "" {
		if _, err := ReadAccount(ctx, tx, workspace, q.AccountID); err != nil {
			return result, err
		}
	}
	for _, v := range []struct{ id, kind string }{{q.CategoryID, "category"}, {q.TagID, "tag"}} {
		if v.id != "" {
			if _, err := ReadClassification(ctx, tx, workspace, v.id, v.kind); err != nil {
				return result, err
			}
		}
	}
	args := []any{workspace, q.From, q.To, q.AccountID, q.CategoryID, q.TagID, q.Status}
	rows, err := tx.Query(ctx, reportCash+`SELECT jsonb_build_object('currency',currency,
 'income_minor',coalesce(sum(amount) FILTER(WHERE kind='income'),0)::text,
 'expense_minor',coalesce(sum(amount) FILTER(WHERE kind<>'income'),0)::text,
 'posted_income_minor',coalesce(sum(amount) FILTER(WHERE kind='income' AND status='posted'),0)::text,
 'pending_income_minor',coalesce(sum(amount) FILTER(WHERE kind='income' AND status='pending'),0)::text,
 'posted_expense_minor',coalesce(sum(amount) FILTER(WHERE kind<>'income' AND status='posted'),0)::text,
 'pending_expense_minor',coalesce(sum(amount) FILTER(WHERE kind<>'income' AND status='pending'),0)::text)
 FROM cash GROUP BY currency ORDER BY currency`, args...)
	if err != nil {
		return result, err
	}
	result.Currencies, err = reportRows(rows)
	if err != nil {
		return result, err
	}
	rows, err = tx.Query(ctx, reportCash+`SELECT jsonb_build_object('currency',currency,'category_id',category_id,
 'expense_minor',sum(amount)::text,
 'posted_expense_minor',coalesce(sum(amount) FILTER(WHERE status='posted'),0)::text,
 'pending_expense_minor',coalesce(sum(amount) FILTER(WHERE status='pending'),0)::text)
 FROM cash WHERE kind<>'income' GROUP BY currency,category_id ORDER BY currency,category_id NULLS FIRST`, args...)
	if err != nil {
		return result, err
	}
	result.Categories, err = reportRows(rows)
	if err != nil {
		return result, err
	}
	// Accounts with no movements still appear with zero balances. Numeric SUM avoids bigint overflow.
	rows, err = tx.Query(ctx, `SELECT jsonb_build_object('currency',a.currency_code,
 'opening_minor',coalesce(sum(e.amount_minor) FILTER(WHERE t.status='posted' AND t.occurred_at<$2),0)::text,
 'closing_minor',coalesce(sum(e.amount_minor) FILTER(WHERE t.status='posted'),0)::text,
 'projected_closing_minor',coalesce(sum(e.amount_minor),0)::text)
 FROM accounts a LEFT JOIN (entries e JOIN transactions t ON (t.workspace_id,t.id)=(e.workspace_id,e.transaction_id) AND t.deleted_at IS NULL AND t.occurred_at<$3)
 ON (a.workspace_id,a.id)=(e.workspace_id,e.account_id)
 WHERE a.workspace_id=$1 AND a.deleted_at IS NULL AND ($4='' OR a.id=NULLIF($4,'')::uuid)
 GROUP BY a.currency_code ORDER BY a.currency_code`, workspace, q.From, q.To, q.AccountID)
	if err != nil {
		return result, err
	}
	result.Balances, err = reportRows(rows)
	if err != nil {
		return result, err
	}
	rows, err = tx.Query(ctx, `WITH movement AS (
 SELECT a.currency_code currency,(t.occurred_at AT TIME ZONE 'UTC')::date report_date,
 coalesce(sum(e.amount_minor) FILTER(WHERE t.status='posted'),0) posted,sum(e.amount_minor) projected
 FROM entries e JOIN accounts a ON (a.workspace_id,a.id)=(e.workspace_id,e.account_id)
 JOIN transactions t ON (t.workspace_id,t.id)=(e.workspace_id,e.transaction_id)
 WHERE t.workspace_id=$1 AND t.deleted_at IS NULL AND t.occurred_at<$3
 AND ($4='' OR e.account_id=NULLIF($4,'')::uuid)
 GROUP BY a.currency_code,(t.occurred_at AT TIME ZONE 'UTC')::date), running AS (
 SELECT currency,report_date,sum(posted) OVER(PARTITION BY currency ORDER BY report_date) posted,
 sum(projected) OVER(PARTITION BY currency ORDER BY report_date) projected FROM movement)
 SELECT jsonb_build_object('currency',currency,'date',report_date::text,'closing_minor',posted::text,'projected_closing_minor',projected::text)
 FROM running WHERE report_date>=($2::timestamptz AT TIME ZONE 'UTC')::date ORDER BY report_date,currency`, workspace, q.From, q.To, q.AccountID)
	if err != nil {
		return result, err
	}
	result.DailyBalances, err = reportRows(rows)
	return result, err
}

func reportRows(rows pgx.Rows) ([]json.RawMessage, error) {
	defer rows.Close()
	out := []json.RawMessage{}
	for rows.Next() {
		var raw json.RawMessage
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		out = append(out, raw)
	}
	return out, rows.Err()
}
