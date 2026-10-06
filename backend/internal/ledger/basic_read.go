package ledger

import (
	"accounting/backend/internal/identity"
	"context"
	"encoding/json"
	"errors"
	"github.com/jackc/pgx/v5"
	"time"
)

// Transaction children and refund reservations are read from the same MVCC statement.
const transactionSelect = `SELECT jsonb_build_object(
 'id',t.id,'workspace_id',t.workspace_id,'version',t.version::text,
 'created_at',to_char(t.created_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
 'updated_at',to_char(t.updated_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
 'deleted_at',NULL,'kind',t.kind,'status',t.status,
 'occurred_at',to_char(t.occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
 'occurred_timezone',t.occurred_timezone,'note',t.note,'payee',t.payee,'reason',t.reason,
 'parent_transaction_id',t.parent_transaction_id,
 'fee_transaction_id',(SELECT f.id FROM transactions f WHERE f.workspace_id=t.workspace_id AND f.parent_transaction_id=t.id AND f.kind='expense' AND f.deleted_at IS NULL),
 'rate',CASE WHEN t.rate_numerator IS NULL THEN NULL ELSE jsonb_build_object('numerator',t.rate_numerator::text,'denominator',t.rate_denominator::text) END,
 'entries',coalesce((SELECT jsonb_agg(jsonb_build_object('id',e.id,'account_id',e.account_id,'currency',a.currency_code,'amount_minor',e.amount_minor::text) ORDER BY e.id) FROM entries e JOIN accounts a ON a.workspace_id=e.workspace_id AND a.id=e.account_id WHERE e.workspace_id=t.workspace_id AND e.transaction_id=t.id),'[]'::jsonb),
 'allocations',coalesce((SELECT jsonb_agg(jsonb_build_object('id',p.id,'category_id',p.category_id,'amount_minor',p.amount_minor::text,'original_allocation_id',p.original_allocation_id,'remaining_refundable_minor',CASE WHEN t.kind='expense' THEN (p.amount_minor-coalesce((SELECT sum(r.amount_minor) FROM allocations r JOIN transactions rt ON rt.workspace_id=r.workspace_id AND rt.id=r.transaction_id WHERE r.workspace_id=p.workspace_id AND r.original_allocation_id=p.id AND rt.kind='refund' AND rt.deleted_at IS NULL),0))::text ELSE '0' END) ORDER BY p.id) FROM allocations p WHERE p.workspace_id=t.workspace_id AND p.transaction_id=t.id),'[]'::jsonb),
 'tag_ids',coalesce((SELECT jsonb_agg(tt.tag_id ORDER BY tt.tag_id) FROM transaction_tags tt WHERE tt.workspace_id=t.workspace_id AND tt.transaction_id=t.id),'[]'::jsonb)) FROM transactions t`

func ReadTransaction(ctx context.Context, tx pgx.Tx, workspace, id string) (json.RawMessage, error) {
	if !uuid(workspace) || !uuid(id) {
		return nil, &identity.Error{Status: 400, Code: "bad_request"}
	}
	var data json.RawMessage
	e := tx.QueryRow(ctx, transactionSelect+` WHERE t.workspace_id=$1 AND t.id=$2 AND t.deleted_at IS NULL`, workspace, id).Scan(&data)
	if errors.Is(e, pgx.ErrNoRows) {
		return nil, &identity.Error{Status: 404, Code: "not_found"}
	}
	return data, e
}

type TransactionQuery struct {
	AccountID, CategoryID, TagID, Kind, Status, Search string
	From, To, AfterDate                                *time.Time
	AfterID                                            string
	Limit                                              int
}

func ListTransactions(ctx context.Context, tx pgx.Tx, workspace string, q TransactionQuery) ([]json.RawMessage, error) {
	// Resource filters must be authorized within this workspace, including archived history.
	if q.AccountID != "" {
		if _, e := ReadAccount(ctx, tx, workspace, q.AccountID); e != nil {
			return nil, e
		}
	}
	if q.CategoryID != "" {
		if _, e := ReadClassification(ctx, tx, workspace, q.CategoryID, "category"); e != nil {
			return nil, e
		}
	}
	if q.TagID != "" {
		if _, e := ReadClassification(ctx, tx, workspace, q.TagID, "tag"); e != nil {
			return nil, e
		}
	}
	rows, e := tx.Query(ctx, transactionSelect+` WHERE t.workspace_id=$1 AND t.deleted_at IS NULL
 AND ($2='' OR t.opening_account_id=NULLIF($2,'')::uuid OR EXISTS(SELECT 1 FROM entries e WHERE e.workspace_id=t.workspace_id AND e.transaction_id=t.id AND e.account_id=NULLIF($2,'')::uuid))
 AND ($3='' OR EXISTS(SELECT 1 FROM allocations p WHERE p.workspace_id=t.workspace_id AND p.transaction_id=t.id AND p.category_id=NULLIF($3,'')::uuid))
 AND ($4='' OR EXISTS(SELECT 1 FROM transaction_tags tt WHERE tt.workspace_id=t.workspace_id AND tt.transaction_id=t.id AND tt.tag_id=NULLIF($4,'')::uuid))
 AND ($5::timestamptz IS NULL OR t.occurred_at>=$5) AND ($6::timestamptz IS NULL OR t.occurred_at<$6)
 AND ($7='' OR t.kind=$7) AND ($8='' OR t.status=$8)
 AND (strpos(lower(t.note),lower($9))>0 OR strpos(lower(t.payee),lower($9))>0)
 AND ($10::timestamptz IS NULL OR (t.occurred_at,t.id)<($10::timestamptz,NULLIF($11,'')::uuid))
 ORDER BY t.occurred_at DESC,t.id DESC LIMIT $12`, workspace, q.AccountID, q.CategoryID, q.TagID, q.From, q.To, q.Kind, q.Status, q.Search, q.AfterDate, q.AfterID, q.Limit)
	if e != nil {
		return nil, e
	}
	items, _, e := scanAggregates(rows)
	return items, e
}
