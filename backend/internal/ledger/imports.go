package ledger

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

type ImportPreview struct {
	Account    string           `json:"account_id"`
	Currency   string           `json:"currency"`
	ID         string           `json:"id"`
	Workspace  string           `json:"workspace_id"`
	Version    string           `json:"version"`
	State      string           `json:"state"`
	Rows       []ImportRow      `json:"rows"`
	Expires    time.Time        `json:"expires_at"`
	ErrorCode  *string          `json:"error_code"`
	Processing []ImportProgress `json:"processing"`
}
type ImportProgress struct {
	Number      int     `json:"row_number"`
	State       string  `json:"state"`
	Transaction *string `json:"transaction_id"`
}
type ImportDecision struct {
	Number      int     `json:"row_number"`
	Action      string  `json:"action"`
	Transaction *string `json:"transaction_id"`
	Version     *string `json:"expected_transaction_version"`
}
type ImportConfirm struct {
	Version   string           `json:"expected_version"`
	Decisions []ImportDecision `json:"decisions"`
}

type importSetting func(context.Context, pgx.Tx, identity.Principal) (any, int, error)

// Import control commands share the auxiliary receipt namespace, never the financial outbox.
func ExecuteImportSetting(ctx context.Context, auth *identity.Service, req commands.Request, action importSetting) (commands.Result, error) {
	result := commands.Result{}
	if !uuid(req.WorkspaceID) || !uuid(req.ActionID) || !uuid(req.GenerationID) {
		return result, &identity.Error{Status: 400, Code: "bad_request"}
	}
	hash, err := commands.RequestHashLimit(req.Method, req.Path, req.GenerationID, req.Body, 14*1024*1024)
	if err != nil {
		return result, err
	}
	err = auth.WithSession(ctx, req.Credential, false, func(tx pgx.Tx, p identity.Principal) error {
		if req.Authorize != nil {
			if err := req.Authorize(p); err != nil {
				return err
			}
		}
		if err := identity.RequireMembership(ctx, tx, p.Profile.ID, req.WorkspaceID); err != nil {
			return err
		}
		var generation string
		if err := tx.QueryRow(ctx, `SELECT generation_id::text FROM sync_heads WHERE workspace_id=$1 FOR UPDATE`, req.WorkspaceID).Scan(&generation); err != nil {
			return err
		}
		var old []byte
		var completed time.Time
		err := tx.QueryRow(ctx, `SELECT request_hash,response,status,completed_at FROM planning_actions WHERE workspace_id=$1 AND actor_user_id=$2 AND action_id=$3`, req.WorkspaceID, p.Profile.ID, req.ActionID).Scan(&old, &result.Body, &result.Status, &completed)
		if err == nil {
			if subtle.ConstantTimeCompare(old, hash[:]) != 1 {
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
		if generation != req.GenerationID {
			return &identity.Error{Status: 409, Code: "sync_generation_conflict"}
		}
		if _, err := tx.Exec(ctx, "SAVEPOINT import_control"); err != nil {
			return err
		}
		value, status, err := action(ctx, tx, p)
		if err != nil {
			var failure *identity.Error
			if !errors.As(err, &failure) || failure.Status != 409 && failure.Status != 422 {
				return err
			}
			if _, e := tx.Exec(ctx, "ROLLBACK TO SAVEPOINT import_control"); e != nil {
				return e
			}
			status = failure.Status
			value = map[string]any{"request_id": req.RequestID, "code": failure.Code, "message": "Не удалось выполнить импорт. Обновите предпросмотр и проверьте решения.", "field_errors": []any{}, "current_versions": []any{}, "retry_after_seconds": nil}
		}
		result.Status = status
		result.Body, err = json.Marshal(value)
		if err != nil {
			return err
		}
		return savePlanningReceipt(ctx, tx, p.Profile.ID, req, hash[:], result)
	})
	if err == nil {
		result.Body, err = commands.CanonicalJSON(result.Body)
	}
	return result, err
}

func CreateImport(workspace, generation string, input ImportInput) importSetting {
	return func(ctx context.Context, tx pgx.Tx, p identity.Principal) (any, int, error) {
		if _, err := ReadAccount(ctx, tx, workspace, input.Mapping.Account); err != nil {
			return nil, 0, err
		}
		var scale int
		var currency string
		var opened, now time.Time
		var archived bool
		if err := tx.QueryRow(ctx, `SELECT c.scale,a.currency_code,a.opened_at,clock_timestamp(),a.archived_at IS NOT NULL FROM accounts a JOIN currencies c ON c.code=a.currency_code WHERE a.workspace_id=$1 AND a.id=$2`, workspace, input.Mapping.Account).Scan(&scale, &currency, &opened, &now, &archived); err != nil {
			return nil, 0, err
		}
		if archived {
			return nil, 0, invalid()
		}
		rows, content, err := ParseCSV(input, scale)
		if err != nil {
			return nil, 0, err
		}
		mapping, _ := json.Marshal(input.Mapping)
		fileHash := sha256.Sum256(append(content, mapping...))
		preview := ImportPreview{Account: input.Mapping.Account, Currency: currency, ID: input.ID, Workspace: workspace, Version: "1", State: "ready", Rows: []ImportRow{}, Expires: now.UTC().Add(24 * time.Hour), Processing: []ImportProgress{}}
		var exists bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM csv_imports WHERE workspace_id=$1 AND id=$2)`, workspace, input.ID).Scan(&exists); err != nil {
			return nil, 0, err
		}
		if exists {
			return nil, 0, &identity.Error{Status: 409, Code: "resource_conflict"}
		}
		raw, _ := json.Marshal(preview)
		if _, err := tx.Exec(ctx, `INSERT INTO csv_imports(workspace_id,id,actor_user_id,session_id,generation_id,account_id,state,preview,expires_at) VALUES($1,$2,$3,$4,$5,$6,'ready',$7,$8)`, workspace, input.ID, p.Profile.ID, p.Session.ID, generation, input.Mapping.Account, raw, preview.Expires); err != nil {
			return nil, 0, err
		}
		for _, parsed := range rows {
			row := parsed.Row
			invalidRow := func(field string) {
				row.Status = "invalid"
				row.Errors = append(row.Errors, ImportError{Path: field, Code: "validation_error", Message: "Строку нельзя импортировать."})
			}
			if parsed.Currency != "" && parsed.Currency != currency {
				invalidRow("currency")
			}
			if row.Occurred != nil {
				occurred, _ := ParseInstant(*row.Occurred)
				if occurred.Before(opened) || occurred.After(now) {
					invalidRow("date")
				}
			}
			fingerprint := sha256.Sum256([]byte(fmt.Sprintf("%x:%d", fileHash, row.Number)))
			var previous *string
			err := tx.QueryRow(ctx, `SELECT transaction_id::text FROM csv_sources WHERE workspace_id=$1 AND account_id=$2 AND fingerprint=$3`, workspace, input.Mapping.Account, fingerprint[:]).Scan(&previous)
			if err != nil && !errors.Is(err, pgx.ErrNoRows) {
				return nil, 0, err
			}
			if previous != nil && row.Status != "invalid" {
				row.Candidates = append(row.Candidates, *previous)
				row.Status = "ambiguous"
			}
			if parsed.OriginalID != "" && row.Status != "invalid" {
				var allowed bool
				if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM entries e JOIN transactions t ON (t.workspace_id,t.id)=(e.workspace_id,e.transaction_id) WHERE t.workspace_id=$1 AND t.id=$2 AND e.account_id=$3 AND t.deleted_at IS NULL)`, workspace, parsed.OriginalID, input.Mapping.Account).Scan(&allowed); err != nil {
					return nil, 0, err
				}
				if allowed {
					row.Candidates = []string{parsed.OriginalID}
					row.Status = "ambiguous"
				} else {
					invalidRow("source_transaction_id")
				}
			}
			if row.Status == "valid" && row.Payee != "" {
				candidates, err := tx.Query(ctx, `SELECT t.id::text FROM transactions t JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id)
   WHERE t.workspace_id=$1 AND e.account_id=$2 AND t.deleted_at IS NULL AND t.kind IN ('expense','income') AND e.amount_minor=$3::bigint AND t.occurred_at=$4::timestamptz AND lower(t.payee)=lower($5) ORDER BY t.id LIMIT 20`, workspace, input.Mapping.Account, *row.Amount, *row.Occurred, row.Payee)
				if err != nil {
					return nil, 0, err
				}
				for candidates.Next() {
					var id string
					if err := candidates.Scan(&id); err != nil {
						candidates.Close()
						return nil, 0, err
					}
					row.Candidates = append(row.Candidates, id)
				}
				err = candidates.Err()
				candidates.Close()
				if err != nil {
					return nil, 0, err
				}
				if len(row.Candidates) > 0 {
					row.Status = "ambiguous"
				}
			}
			state := "prepared"
			var body []byte
			transaction := newID()
			if row.Status == "invalid" {
				state = "invalid"
			} else {
				amount, _ := ParseMoney(*row.Amount)
				kind := "income"
				if amount.Minor() < 0 {
					kind = "expense"
					amount = amount.Negate()
				}
				body, _ = json.Marshal(BasicInput{ID: transaction, Kind: kind, AccountID: input.Mapping.Account, Amount: amount.String(), OccurredAt: *row.Occurred, Timezone: input.Mapping.Timezone, Payee: row.Payee, Note: parsed.Note, TagIDs: []string{}, Allocations: []AllocationInput{{ID: newID(), Amount: amount.String()}}})
			}
			if _, err := tx.Exec(ctx, `INSERT INTO csv_rows(workspace_id,import_id,row_number,action_id,transaction_id,fingerprint,payload,state) VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, workspace, input.ID, row.Number, newID(), transaction, fingerprint[:], body, state); err != nil {
				return nil, 0, err
			}
			preview.Rows = append(preview.Rows, row)
			preview.Processing = append(preview.Processing, ImportProgress{Number: row.Number, State: state})
		}
		raw, _ = json.Marshal(preview)
		_, err = tx.Exec(ctx, `UPDATE csv_imports SET preview=$3 WHERE workspace_id=$1 AND id=$2`, workspace, input.ID, raw)
		return preview, 201, err
	}
}

func ReadImport(ctx context.Context, tx pgx.Tx, workspace, id, owner string) (ImportPreview, error) {
	var result ImportPreview
	var raw []byte
	if !uuid(id) {
		return result, &identity.Error{Status: 400, Code: "bad_request"}
	}
	err := tx.QueryRow(ctx, `SELECT preview,state,version::text,expires_at,error_code FROM csv_imports WHERE workspace_id=$1 AND id=$2 AND actor_user_id=$3`, workspace, id, owner).Scan(&raw, &result.State, &result.Version, &result.Expires, &result.ErrorCode)
	if errors.Is(err, pgx.ErrNoRows) {
		return result, &identity.Error{Status: 404, Code: "not_found"}
	}
	if err != nil {
		return result, err
	}
	state, version, expiry, code := result.State, result.Version, result.Expires, result.ErrorCode
	if err := json.Unmarshal(raw, &result); err != nil {
		return result, err
	}
	result.State = state
	result.Version = version
	result.Expires = expiry.UTC()
	result.ErrorCode = code
	result.Processing = []ImportProgress{}
	if result.State == "ready" && time.Now().After(expiry) {
		result.State = "expired"
	}
	rows, err := tx.Query(ctx, `SELECT row_number,state,resolved_id::text FROM csv_rows WHERE workspace_id=$1 AND import_id=$2 ORDER BY row_number`, workspace, id)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		var p ImportProgress
		if err := rows.Scan(&p.Number, &p.State, &p.Transaction); err != nil {
			return result, err
		}
		result.Processing = append(result.Processing, p)
	}
	return result, rows.Err()
}

func DecodeImportConfirm(body []byte) (ImportConfirm, error) {
	var v ImportConfirm
	if err := decodeObject(body, &v, []string{"expected_version", "decisions"}, nil); err != nil {
		return v, err
	}
	if _, err := version(v.Version); err != nil || len(v.Decisions) > 1000 {
		return v, invalid()
	}
	var raw struct {
		Rows []json.RawMessage `json:"decisions"`
	}
	json.Unmarshal(body, &raw)
	seen := map[int]bool{}
	for i, r := range raw.Rows {
		var d ImportDecision
		if err := decodeObject(r, &d, []string{"row_number", "action", "transaction_id", "expected_transaction_version"}, []string{"transaction_id", "expected_transaction_version"}); err != nil {
			return v, err
		}
		if d.Number < 2 || d.Number > 1001 || seen[d.Number] {
			return v, invalid()
		}
		seen[d.Number] = true
		if d.Action == "link" {
			if d.Transaction == nil || d.Version == nil || !uuid(*d.Transaction) {
				return v, invalid()
			}
			if _, err := version(*d.Version); err != nil {
				return v, invalid()
			}
			normalized := strings.ToLower(*d.Transaction)
			d.Transaction = &normalized
		} else if d.Action != "create" && d.Action != "skip" || d.Transaction != nil || d.Version != nil {
			return v, invalid()
		}
		v.Decisions[i] = d
	}
	return v, nil
}

func ConfirmImport(workspace, id, generation string, input ImportConfirm) importSetting {
	return func(ctx context.Context, tx pgx.Tx, p identity.Principal) (any, int, error) {
		preview, err := ReadImport(ctx, tx, workspace, id, p.Profile.ID)
		if err != nil {
			return nil, 0, err
		}
		if preview.Version != input.Version {
			return nil, 0, &identity.Error{Status: 409, Code: "version_conflict"}
		}
		if preview.State != "ready" && preview.State != "failed" {
			return nil, 0, &identity.Error{Status: 409, Code: "import_not_ready"}
		}
		if preview.State == "failed" {
			if len(input.Decisions) != 0 {
				return nil, 0, invalid()
			}
			// Explicit resume: applied rows remain untouched; only uncommitted row commands get new keys.
			if _, err := tx.Exec(ctx, `UPDATE csv_rows SET action_id=gen_random_uuid() WHERE workspace_id=$1 AND import_id=$2 AND state='queued'`, workspace, id); err != nil {
				return nil, 0, err
			}
		} else {
			if len(input.Decisions) != len(preview.Rows) {
				return nil, 0, invalid()
			}
			sourceIDs := map[int]*string{}
			for _, row := range preview.Rows {
				sourceIDs[row.Number] = row.SourceID
			}
			for _, decision := range input.Decisions {
				var state string
				var fingerprint []byte
				var body []byte
				err := tx.QueryRow(ctx, `SELECT state,fingerprint,payload FROM csv_rows WHERE workspace_id=$1 AND import_id=$2 AND row_number=$3 FOR UPDATE`, workspace, id, decision.Number).Scan(&state, &fingerprint, &body)
				if err != nil {
					return nil, 0, invalid()
				}
				targetState := "skipped"
				var target *string
				if decision.Action != "skip" {
					sourceID := sourceIDs[decision.Number]
					if sourceID != nil && (decision.Action != "link" || decision.Transaction == nil || *decision.Transaction != *sourceID) {
						return nil, 0, invalid()
					}
					if state == "invalid" {
						return nil, 0, invalid()
					}
					var original BasicInput
					if err := json.Unmarshal(body, &original); err != nil {
						return nil, 0, err
					}
					var sourceTarget *string
					err = tx.QueryRow(ctx, `SELECT transaction_id::text FROM csv_sources WHERE workspace_id=$1 AND account_id=$2 AND fingerprint=$3`, workspace, original.AccountID, fingerprint).Scan(&sourceTarget)
					if err == nil {
						if sourceTarget == nil {
							return nil, 0, &identity.Error{Status: 409, Code: "import_source_pending"}
						}
						targetState = "linked"
						target = sourceTarget
					} else if !errors.Is(err, pgx.ErrNoRows) {
						return nil, 0, err
					} else {
						if decision.Action == "link" {
							data, err := ReadTransaction(ctx, tx, workspace, *decision.Transaction)
							if err != nil {
								return nil, 0, err
							}
							var selected struct {
								Version string `json:"version"`
								Kind    string `json:"kind"`
								Entries []struct {
									Account string `json:"account_id"`
									Amount  string `json:"amount_minor"`
								} `json:"entries"`
							}
							json.Unmarshal(data, &selected)
							signed := original.Amount
							if original.Kind == "expense" {
								signed = "-" + signed
							}
							if selected.Version != *decision.Version {
								return nil, 0, &identity.Error{Status: 409, Code: "version_conflict"}
							}
							matches := false
							for _, entry := range selected.Entries {
								if entry.Account == original.AccountID && (sourceID != nil || entry.Amount == signed) {
									matches = true
								}
							}
							if !matches || sourceID == nil && (len(selected.Entries) != 1 || selected.Kind != original.Kind) {
								return nil, 0, invalid()
							}
							targetState = "linked"
							target = decision.Transaction
						} else {
							targetState = "queued"
						}
						if _, err := tx.Exec(ctx, `INSERT INTO csv_sources(workspace_id,account_id,fingerprint,import_id,row_number,transaction_id) VALUES($1,$2,$3,$4,$5,$6)`, workspace, original.AccountID, fingerprint, id, decision.Number, target); err != nil {
							return nil, 0, err
						}
					}
				}
				if _, err := tx.Exec(ctx, `UPDATE csv_rows SET state=$4,resolved_id=$5 WHERE workspace_id=$1 AND import_id=$2 AND row_number=$3`, workspace, id, decision.Number, targetState, target); err != nil {
					return nil, 0, err
				}
			}
		}
		_, err = tx.Exec(ctx, `UPDATE csv_imports SET version=version+1,state=CASE WHEN EXISTS(SELECT 1 FROM csv_rows WHERE workspace_id=$1 AND import_id=$2 AND state='queued') THEN 'queued' ELSE 'committed' END,session_id=$3,generation_id=$4,error_code=NULL WHERE workspace_id=$1 AND id=$2`, workspace, id, p.Session.ID, generation)
		if err != nil {
			return nil, 0, err
		}
		result, err := ReadImport(ctx, tx, workspace, id, p.Profile.ID)
		return result, 200, err
	}
}
