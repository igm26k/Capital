package ledger

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"math"
	"math/big"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata"

	"github.com/jackc/pgx/v5"
)

type BudgetInput struct {
	ID              string `json:"id,omitempty"`
	ExpectedVersion string `json:"expected_version,omitempty"`
	Name            string `json:"name"`
	CategoryID      string `json:"category_id"`
	Currency        string `json:"currency"`
	Month           string `json:"month"`
	Timezone        string `json:"timezone"`
	Limit           string `json:"limit_minor"`
}
type RuleInput struct {
	ID              string `json:"id,omitempty"`
	ExpectedVersion string `json:"expected_version,omitempty"`
	Name            string `json:"name"`
	CategoryID      string `json:"category_id"`
	Priority        int    `json:"priority"`
	Enabled         bool   `json:"enabled"`
	Kind            string `json:"transaction_kind"`
	Payee           string `json:"payee_contains"`
	Note            string `json:"note_contains"`
}

func BudgetPeriod(month, zone string) (time.Time, time.Time, error) {
	loc, err := time.LoadLocation(zone)
	if err != nil {
		return time.Time{}, time.Time{}, invalid()
	}
	from, err := time.ParseInLocation("2006-01", month, loc)
	if err != nil || from.Year() < 1900 || from.Year() > 9998 || from.Format("2006-01") != month {
		return time.Time{}, time.Time{}, invalid()
	}
	return from.UTC(), from.AddDate(0, 1, 0).UTC(), nil
}

func DecodePlanning(body []byte, kind, method string) (map[string]any, error) {
	fields := []string{"expected_version"}
	if method == "DELETE" {
		var v struct {
			Version string `json:"expected_version"`
		}
		if err := decodeObject(body, &v, fields, nil); err != nil {
			return nil, err
		}
		if _, err := version(v.Version); err != nil {
			return nil, err
		}
		return map[string]any{"expected_version": v.Version}, nil
	}
	if kind == "budget" {
		fields = []string{"name", "category_id", "currency", "month", "timezone", "limit_minor"}
	} else if kind == "rule" {
		fields = []string{"name", "category_id", "priority", "enabled", "transaction_kind", "payee_contains", "note_contains"}
	} else {
		return nil, invalid()
	}
	if method == "POST" {
		fields = append(fields, "id")
	} else if method == "PUT" {
		fields = append(fields, "expected_version")
	} else {
		return nil, invalid()
	}
	var result map[string]any
	if kind == "budget" {
		var v BudgetInput
		if err := decodeObject(body, &v, fields, nil); err != nil {
			return nil, err
		}
		if !validText(v.Name, 100) || strings.TrimSpace(v.Name) == "" || !uuid(v.CategoryID) {
			return nil, invalid()
		}
		if _, err := ParsePositiveMoney(v.Limit); err != nil {
			return nil, invalid()
		}
		if _, _, err := BudgetPeriod(v.Month, v.Timezone); err != nil {
			return nil, err
		}
	} else {
		var v RuleInput
		if err := decodeObject(body, &v, fields, nil); err != nil {
			return nil, err
		}
		if !validText(v.Name, 100) || strings.TrimSpace(v.Name) == "" || !uuid(v.CategoryID) || v.Priority < 0 || v.Priority > 1000000 || v.Kind != "expense" && v.Kind != "income" || !validText(v.Payee, 200) || !validText(v.Note, 200) || strings.TrimSpace(v.Payee) == "" && strings.TrimSpace(v.Note) == "" {
			return nil, invalid()
		}
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, invalid()
	}
	if method == "POST" {
		id, _ := result["id"].(string)
		if !uuid(id) {
			return nil, invalid()
		}
		result["id"] = strings.ToLower(id)
	} else {
		v, _ := result["expected_version"].(string)
		if _, err := version(v); err != nil {
			return nil, err
		}
	}
	result["category_id"] = strings.ToLower(result["category_id"].(string))
	return result, nil
}

// PlanningExecute never publishes an unknown entity type into an older financial mirror.
// Auth, generation, replay, version and write are serialized by the workspace head lock.
func PlanningExecute(ctx context.Context, auth *identity.Service, request commands.Request, kind, id string) (commands.Result, error) {
	var result commands.Result
	if !uuid(request.WorkspaceID) || !uuid(request.ActionID) || !uuid(request.GenerationID) {
		return result, &identity.Error{Status: 400, Code: "bad_request"}
	}
	input, err := DecodePlanning(request.Body, kind, request.Method)
	if err != nil {
		return result, err
	}
	if request.Method == "POST" {
		id = input["id"].(string)
	}
	if !uuid(id) {
		return result, &identity.Error{Status: 400, Code: "bad_request"}
	}
	id = strings.ToLower(id)
	hash, err := commands.RequestHash(request.Method, request.Path, request.GenerationID, request.Body)
	if err != nil {
		return result, err
	}
	err = auth.WithSession(ctx, request.Credential, false, func(tx pgx.Tx, p identity.Principal) error {
		if request.Authorize != nil {
			if err := request.Authorize(p); err != nil {
				return err
			}
		}
		if err := identity.RequireMembership(ctx, tx, p.Profile.ID, request.WorkspaceID); err != nil {
			return err
		}
		var generation string
		if err := tx.QueryRow(ctx, `SELECT generation_id::text FROM sync_heads WHERE workspace_id=$1 FOR UPDATE`, request.WorkspaceID).Scan(&generation); err != nil {
			return err
		}
		var oldHash []byte
		var completed time.Time
		err := tx.QueryRow(ctx, `SELECT request_hash,response,status,completed_at FROM planning_actions WHERE workspace_id=$1 AND actor_user_id=$2 AND action_id=$3`, request.WorkspaceID, p.Profile.ID, request.ActionID).Scan(&oldHash, &result.Body, &result.Status, &completed)
		if err == nil {
			if subtle.ConstantTimeCompare(hash[:], oldHash) != 1 {
				return &identity.Error{Status: 409, Code: "idempotency_conflict"}
			}
			if time.Since(completed) > 30*24*time.Hour {
				return &identity.Error{Status: 409, Code: "action_result_expired"}
			}
			result.Replayed = true
			return nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		if generation != request.GenerationID {
			return &identity.Error{Status: 409, Code: "sync_generation_conflict"}
		}
		current := int64(0)
		var old json.RawMessage
		var deleted *time.Time
		err = tx.QueryRow(ctx, `SELECT version,data,deleted_at FROM planning_resources WHERE workspace_id=$1 AND kind=$2 AND id=$3 FOR UPDATE`, request.WorkspaceID, kind, id).Scan(&current, &old, &deleted)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		saveRejection := func(status int, code string) error {
			result.Status = status
			result.Body, _ = json.Marshal(map[string]any{"request_id": request.RequestID, "code": code, "message": "Настройки изменились. Обновите данные и сравните значения.", "field_errors": []any{}, "current_versions": []any{}, "retry_after_seconds": nil})
			return savePlanningReceipt(ctx, tx, p.Profile.ID, request, hash[:], result)
		}
		if request.Method == "POST" && current != 0 {
			return saveRejection(409, "resource_conflict")
		}
		if request.Method != "POST" {
			if current == 0 || deleted != nil {
				return &identity.Error{Status: 404, Code: "not_found"}
			}
			expected, _ := strconv.ParseInt(input["expected_version"].(string), 10, 64)
			if current != expected {
				return saveRejection(409, "version_conflict")
			}
		}
		if current == math.MaxInt64 {
			return &identity.Error{Status: 503, Code: "service_unavailable"}
		}
		if request.Method == "POST" {
			var count int
			if err := tx.QueryRow(ctx, `SELECT count(*) FROM planning_resources WHERE workspace_id=$1 AND kind=$2 AND deleted_at IS NULL`, request.WorkspaceID, kind).Scan(&count); err != nil {
				return err
			}
			if count >= 100 {
				return saveRejection(422, "planning_limit_exceeded")
			}
		}
		current++
		operation := "update"
		result.Status = 200
		if request.Method == "POST" {
			operation = "create"
			result.Status = 201
		}
		if request.Method == "DELETE" {
			operation = "delete"
			if _, err := tx.Exec(ctx, `UPDATE planning_resources SET version=$4,deleted_at=clock_timestamp() WHERE workspace_id=$1 AND kind=$2 AND id=$3`, request.WorkspaceID, kind, id, current); err != nil {
				return err
			}
			result.Body, _ = json.Marshal(map[string]any{"id": id, "workspace_id": request.WorkspaceID, "version": strconv.FormatInt(current, 10), "deleted": true})
		} else {
			if _, err := ReadClassification(ctx, tx, request.WorkspaceID, input["category_id"].(string), "category"); err != nil {
				return err
			}
			var archived bool
			if err := tx.QueryRow(ctx, `SELECT archived_at IS NOT NULL FROM categories WHERE workspace_id=$1 AND id=$2`, request.WorkspaceID, input["category_id"]).Scan(&archived); err != nil {
				return err
			}
			if archived {
				return saveRejection(422, "validation_error")
			}
			if kind == "budget" {
				var exists bool
				if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM currencies WHERE code=$1)`, input["currency"]).Scan(&exists); err != nil {
					return err
				}
				if !exists {
					return saveRejection(422, "validation_error")
				}
			}
			delete(input, "expected_version")
			input["id"] = id
			input["workspace_id"] = request.WorkspaceID
			input["version"] = strconv.FormatInt(current, 10)
			result.Body, _ = json.Marshal(input)
			if _, err := tx.Exec(ctx, `INSERT INTO planning_resources(workspace_id,kind,id,version,data) VALUES($1,$2,$3,$4,$5) ON CONFLICT(workspace_id,kind,id) DO UPDATE SET version=excluded.version,data=excluded.data`, request.WorkspaceID, kind, id, current, []byte(result.Body)); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(ctx, `INSERT INTO planning_audit(workspace_id,actor_user_id,action_id,resource_kind,resource_id,resource_version,operation) VALUES($1,$2,$3,$4,$5,$6,$7)`, request.WorkspaceID, p.Profile.ID, request.ActionID, kind, id, current, operation); err != nil {
			return err
		}
		return savePlanningReceipt(ctx, tx, p.Profile.ID, request, hash[:], result)
	})
	if err == nil {
		result.Body, err = commands.CanonicalJSON(result.Body)
	}
	return result, err
}
func savePlanningReceipt(ctx context.Context, tx pgx.Tx, owner string, r commands.Request, hash []byte, result commands.Result) error {
	_, err := tx.Exec(ctx, `INSERT INTO planning_actions(workspace_id,actor_user_id,action_id,request_hash,response,status) VALUES($1,$2,$3,$4,$5,$6)`, r.WorkspaceID, owner, r.ActionID, hash, []byte(result.Body), result.Status)
	return err
}

func ListPlanning(ctx context.Context, tx pgx.Tx, workspace, kind string) ([]json.RawMessage, error) {
	rows, err := tx.Query(ctx, `SELECT data FROM planning_resources WHERE workspace_id=$1 AND kind=$2 AND deleted_at IS NULL ORDER BY id LIMIT 101`, workspace, kind)
	if err != nil {
		return nil, err
	}
	items, err := reportRows(rows)
	if len(items) > 100 {
		return nil, &identity.Error{Status: 422, Code: "planning_limit_exceeded"}
	}
	return items, err
}

type BudgetView struct {
	Budget    json.RawMessage `json:"budget"`
	From      string          `json:"from"`
	To        string          `json:"to"`
	Spent     string          `json:"spent_minor"`
	Posted    string          `json:"posted_spent_minor"`
	Pending   string          `json:"pending_spent_minor"`
	Remaining string          `json:"remaining_minor"`
	Overspent string          `json:"overspent_minor"`
	Warning   string          `json:"warning"`
}

func ReadBudgets(ctx context.Context, tx pgx.Tx, workspace string) ([]BudgetView, error) {
	items, err := ListPlanning(ctx, tx, workspace, "budget")
	if err != nil {
		return nil, err
	}
	out := []BudgetView{}
	for _, raw := range items {
		var budget BudgetInput
		if err := json.Unmarshal(raw, &budget); err != nil {
			return nil, err
		}
		from, to, err := BudgetPeriod(budget.Month, budget.Timezone)
		if err != nil {
			return nil, err
		}
		view := BudgetView{Budget: raw, From: from.Format(time.RFC3339), To: to.Format(time.RFC3339)}
		err = tx.QueryRow(ctx, `WITH RECURSIVE subtree AS (
   SELECT id FROM categories WHERE workspace_id=$1 AND id=$2 UNION ALL
   SELECT c.id FROM categories c JOIN subtree s ON c.parent_id=s.id WHERE c.workspace_id=$1 AND c.deleted_at IS NULL)
   SELECT coalesce(sum(p.amount_minor::numeric*CASE WHEN t.kind='refund' THEN -1 ELSE 1 END),0)::text,
   coalesce(sum(p.amount_minor::numeric*CASE WHEN t.kind='refund' THEN -1 ELSE 1 END) FILTER(WHERE t.status='posted'),0)::text,
   coalesce(sum(p.amount_minor::numeric*CASE WHEN t.kind='refund' THEN -1 ELSE 1 END) FILTER(WHERE t.status='pending'),0)::text
   FROM transactions t JOIN allocations p ON (p.workspace_id,p.transaction_id)=(t.workspace_id,t.id)
   JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id)
   JOIN accounts a ON (a.workspace_id,a.id)=(e.workspace_id,e.account_id)
   WHERE t.workspace_id=$1 AND t.deleted_at IS NULL AND t.kind IN ('expense','refund')
   AND p.category_id IN (SELECT id FROM subtree) AND a.currency_code=$3 AND t.occurred_at>=$4 AND t.occurred_at<$5`, workspace, budget.CategoryID, budget.Currency, from, to).Scan(&view.Spent, &view.Posted, &view.Pending)
		if err != nil {
			return nil, err
		}
		view.Remaining, view.Overspent, view.Warning = BudgetProgress(budget.Limit, view.Spent)
		out = append(out, view)
	}
	return out, nil
}

// BudgetProgress uses unbounded integers for aggregate sums and exact threshold comparison.
func BudgetProgress(limit, spent string) (string, string, string) {
	l, _ := new(big.Int).SetString(limit, 10)
	s, _ := new(big.Int).SetString(spent, 10)
	remaining := new(big.Int).Sub(l, s)
	over := new(big.Int).Sub(s, l)
	if over.Sign() < 0 {
		over.SetInt64(0)
	}
	warning := "normal"
	if s.Cmp(l) >= 0 {
		warning = "exceeded"
	} else if new(big.Int).Mul(s, big.NewInt(100)).Cmp(new(big.Int).Mul(l, big.NewInt(80))) >= 0 {
		warning = "approaching"
	}
	return remaining.String(), over.String(), warning
}
