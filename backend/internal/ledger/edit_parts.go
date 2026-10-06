package ledger

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
)

type basicHistory struct {
	Parts   map[string]*string
	Amounts map[string]string
	Tags    map[string]bool
}

func readBasicHistory(ctx context.Context, tx pgx.Tx, workspace, id string) (basicHistory, error) {
	history := basicHistory{Parts: map[string]*string{}, Amounts: map[string]string{}, Tags: map[string]bool{}}
	rows, e := tx.Query(ctx, `SELECT id::text,category_id::text,amount_minor::text FROM allocations WHERE workspace_id=$1 AND transaction_id=$2`, workspace, id)
	if e != nil {
		return history, e
	}
	for rows.Next() {
		var part, amount string
		var category *string
		if e = rows.Scan(&part, &category, &amount); e != nil {
			rows.Close()
			return history, e
		}
		history.Parts[part], history.Amounts[part] = category, amount
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return history, e
	}
	rows, e = tx.Query(ctx, `SELECT tag_id::text FROM transaction_tags WHERE workspace_id=$1 AND transaction_id=$2`, workspace, id)
	if e != nil {
		return history, e
	}
	for rows.Next() {
		var tag string
		if e = rows.Scan(&tag); e != nil {
			rows.Close()
			return history, e
		}
		history.Tags[tag] = true
	}
	e = rows.Err()
	rows.Close()
	return history, e
}

// All expense paths share refund guards, historical assignment checks and
// protection of allocation IDs still referenced by soft-deleted refunds.
func validateBasicReplacement(ctx context.Context, tx pgx.Tx, workspace, id, oldAccount, oldAmount string, oldDate time.Time, history basicHistory, input BasicInput, occurred time.Time) error {
	if e := checkBasicAmount(input); e != nil {
		return e
	}
	if e := guardRefundedExpenseEdit(ctx, tx, workspace, id, oldAccount, oldAmount, oldDate, history.Parts, history.Amounts, input, occurred); e != nil {
		return e
	}
	if e := checkReferences(ctx, tx, workspace, input, history.Parts, history.Tags); e != nil {
		return e
	}
	partIDs := []string{}
	for _, part := range input.Allocations {
		partIDs = append(partIDs, part.ID)
	}
	var referencedRemoval bool
	if e := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM allocations r JOIN allocations p ON p.workspace_id=r.workspace_id AND p.id=r.original_allocation_id WHERE p.workspace_id=$1 AND p.transaction_id=$2 AND NOT(p.id=ANY($3::uuid[])))`, workspace, id, partIDs).Scan(&referencedRemoval); e != nil {
		return e
	}
	if referencedRemoval {
		return domainReject(409, "dependent_transactions")
	}
	return nil
}

func replaceTransactionTags(ctx context.Context, tx pgx.Tx, workspace, id string, tags []string) error {
	if _, e := tx.Exec(ctx, `DELETE FROM transaction_tags WHERE workspace_id=$1 AND transaction_id=$2`, workspace, id); e != nil {
		return e
	}
	for _, tag := range tags {
		if _, e := tx.Exec(ctx, `INSERT INTO transaction_tags(workspace_id,transaction_id,tag_id) VALUES($1,$2,$3)`, workspace, id, tag); e != nil {
			return e
		}
	}
	return nil
}

func writeBasicReplacement(ctx context.Context, tx pgx.Tx, workspace, id string, history basicHistory, input BasicInput) error {
	partIDs := []string{}
	for _, part := range input.Allocations {
		partIDs = append(partIDs, part.ID)
	}
	if _, e := tx.Exec(ctx, `DELETE FROM allocations WHERE workspace_id=$1 AND transaction_id=$2 AND NOT(id=ANY($3::uuid[]))`, workspace, id, partIDs); e != nil {
		return e
	}
	for _, part := range input.Allocations {
		value, _ := ParsePositiveMoney(part.Amount)
		if _, exists := history.Parts[part.ID]; exists {
			if _, e := tx.Exec(ctx, `UPDATE allocations SET category_id=$3,amount_minor=$4 WHERE workspace_id=$1 AND id=$2 AND transaction_id=$5`, workspace, part.ID, part.CategoryID, value.Minor(), id); e != nil {
				return e
			}
		} else {
			inserted, e := tx.Exec(ctx, `INSERT INTO allocations(id,workspace_id,transaction_id,category_id,amount_minor) VALUES($1,$2,$3,$4,$5) ON CONFLICT(id) DO NOTHING`, part.ID, workspace, id, part.CategoryID, value.Minor())
			if e != nil {
				return e
			}
			if inserted.RowsAffected() != 1 {
				return domainReject(422, "validation_error")
			}
		}
	}
	return replaceTransactionTags(ctx, tx, workspace, id, input.TagIDs)
}
