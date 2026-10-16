package ledger

import (
	"context"
	"encoding/base64"
	"encoding/csv"
	"strings"

	"github.com/jackc/pgx/v5"
)

type CSVExport struct {
	Format   string `json:"format"`
	Content  string `json:"content_base64"`
	Filename string `json:"filename"`
	Count    int    `json:"row_count"`
}

func ExportCSV(ctx context.Context, tx pgx.Tx, workspace string, q ReportQuery) (CSVExport, error) {
	result := CSVExport{Format: "csv", Filename: "accounting.csv"}
	if _, err := ReadReport(ctx, tx, workspace, q); err != nil {
		return result, err
	}
	rows, err := tx.Query(ctx, `SELECT t.id::text,t.kind,t.status,to_char(t.occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),t.payee,t.note,a.id::text,a.currency_code,c.scale,e.amount_minor::text,
 coalesce((SELECT jsonb_agg(jsonb_build_object('category_id',p.category_id,'amount_minor',p.amount_minor::text))::text FROM allocations p WHERE p.workspace_id=t.workspace_id AND p.transaction_id=t.id),'[]'),
 coalesce((SELECT jsonb_agg(tt.tag_id)::text FROM transaction_tags tt WHERE tt.workspace_id=t.workspace_id AND tt.transaction_id=t.id),'[]')
 FROM transactions t JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id)
 JOIN accounts a ON (a.workspace_id,a.id)=(e.workspace_id,e.account_id) JOIN currencies c ON c.code=a.currency_code
 WHERE t.workspace_id=$1 AND t.deleted_at IS NULL AND t.occurred_at>=$2 AND t.occurred_at<$3
 AND ($4='' OR e.account_id=NULLIF($4,'')::uuid) AND ($5='' OR EXISTS(SELECT 1 FROM allocations p WHERE p.workspace_id=t.workspace_id AND p.transaction_id=t.id AND p.category_id=NULLIF($5,'')::uuid))
 AND ($6='' OR EXISTS(SELECT 1 FROM transaction_tags tt WHERE tt.workspace_id=t.workspace_id AND tt.transaction_id=t.id AND tt.tag_id=NULLIF($6,'')::uuid))
 AND ($7='all' OR t.status=$7) ORDER BY t.occurred_at,t.id,a.id LIMIT 10001`, workspace, q.From, q.To, q.AccountID, q.CategoryID, q.TagID, q.Status)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	var output strings.Builder
	writer := csv.NewWriter(&output)
	writer.Write([]string{"date", "amount", "currency", "payee", "note", "kind", "status", "account_id", "source_transaction_id", "allocations_json", "tag_ids_json", "text_escaped"})
	for rows.Next() {
		var id, kind, status, date, payee, note, account, currency, amount, allocations, tags string
		var scale int
		if err := rows.Scan(&id, &kind, &status, &date, &payee, &note, &account, &currency, &scale, &amount, &allocations, &tags); err != nil {
			return result, err
		}
		if output.Len() > 10*1024*1024 {
			return result, invalid()
		}
		result.Count++
		if result.Count > 10000 {
			return result, invalid()
		}
		writer.Write([]string{date, minorDecimal(amount, scale), currency, csvSafeText(payee), csvSafeText(note), kind, status, account, id, allocations, tags, "1"})
	}
	if err := rows.Err(); err != nil {
		return result, err
	}
	writer.Flush()
	if err := writer.Error(); err != nil {
		return result, err
	}
	result.Content = base64.StdEncoding.EncodeToString([]byte(output.String()))
	return result, nil
}
func csvSafeText(value string) string {
	if len(value) > 0 && strings.ContainsRune("'=+-@\t\r", rune(value[0])) {
		return "'" + value
	}
	return value
}
