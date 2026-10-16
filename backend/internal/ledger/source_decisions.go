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

type SourceDecision struct {
	Version            string  `json:"expected_version"`
	Decision           string  `json:"decision"`
	TransactionID      *string `json:"transaction_id"`
	TransactionVersion *string `json:"expected_transaction_version"`
}
type sourceJob struct {
	SessionID       string `json:"session_id"`
	GenerationID    string `json:"generation_id"`
	ActionID        string `json:"action_id"`
	Decision        string `json:"decision"`
	TransactionID   string `json:"transaction_id"`
	ExpectedVersion string `json:"expected_version"`
}

func DecodeSourceDecision(body []byte) (SourceDecision, error) {
	var v SourceDecision
	if err := decodeObject(body, &v, []string{"expected_version", "decision", "transaction_id", "expected_transaction_version"}, []string{"transaction_id", "expected_transaction_version"}); err != nil {
		return v, err
	}
	if _, err := version(v.Version); err != nil {
		return v, err
	}
	switch v.Decision {
	case "create_pending", "dismiss":
		if v.TransactionID != nil || v.TransactionVersion != nil {
			return v, invalid()
		}
	case "link", "confirm_posted", "cancel_pending":
		if v.TransactionID == nil || !uuid(*v.TransactionID) || v.TransactionVersion == nil {
			return v, invalid()
		}
		if _, err := version(*v.TransactionVersion); err != nil {
			return v, err
		}
	default:
		return v, invalid()
	}
	if v.TransactionID != nil {
		normalized := strings.ToLower(*v.TransactionID)
		v.TransactionID = &normalized
	}
	return v, nil
}
func ConfirmSourceEvent(workspace, id, generation string, input SourceDecision) importSetting {
	return func(ctx context.Context, tx pgx.Tx, p identity.Principal) (any, int, error) {
		event, err := ReadSourceEvent(ctx, tx, workspace, id, p.Profile.ID)
		if err != nil {
			return nil, 0, err
		}
		if event.Version != input.Version {
			return nil, 0, &identity.Error{Status: 409, Code: "version_conflict"}
		}
		current, _ := version(event.Version)
		if current == math.MaxInt64 {
			return nil, 0, &identity.Error{Status: 503, Code: "service_unavailable"}
		}
		relink := false
		if input.Decision == "link" && event.State == "applied" && event.TransactionID != nil {
			if err = tx.QueryRow(ctx, `SELECT deleted_at IS NOT NULL FROM transactions WHERE workspace_id=$1 AND id=$2`, workspace, *event.TransactionID).Scan(&relink); err != nil {
				return nil, 0, err
			}
		}
		retryable := false
		if event.State == "failed" {
			if event.ErrorCode == nil || *event.ErrorCode == "action_result_expired" || *event.ErrorCode == "idempotency_conflict" || *event.ErrorCode == "sync_generation_conflict" {
				return nil, 0, &identity.Error{Status: 409, Code: "source_event_not_ready"}
			}
			var action, state string
			var attempts int
			if err = tx.QueryRow(ctx, `SELECT job->>'action_id',jsonb_array_length(job_history) FROM source_events WHERE workspace_id=$1 AND id=$2`, workspace, id).Scan(&action, &attempts); err != nil {
				return nil, 0, err
			}
			if attempts >= 100 {
				return nil, 0, &identity.Error{Status: 422, Code: "source_event_limit"}
			}
			err = tx.QueryRow(ctx, `SELECT state FROM actions WHERE workspace_id=$1 AND actor_user_id=$2 AND action_id=$3`, workspace, p.Profile.ID, action).Scan(&state)
			if err != nil && !errors.Is(err, pgx.ErrNoRows) {
				return nil, 0, err
			}
			if state == "applied" {
				return nil, 0, &identity.Error{Status: 409, Code: "source_event_not_ready"}
			}
			retryable = true
			if _, err = tx.Exec(ctx, `UPDATE source_events SET job_history=job_history||jsonb_build_array(jsonb_build_object('job',job,'error_code',error_code)) WHERE workspace_id=$1 AND id=$2`, workspace, id); err != nil {
				return nil, 0, err
			}
		}
		if input.Decision == "dismiss" {
			if event.State != "review" && !retryable {
				return nil, 0, &identity.Error{Status: 409, Code: "source_event_not_ready"}
			}
			if event.TransactionID != nil {
				return nil, 0, &identity.Error{Status: 409, Code: "source_event_not_ready"}
			}
			_, err = tx.Exec(ctx, `UPDATE source_events SET state='dismissed',job=NULL,error_code=NULL,version=version+1 WHERE workspace_id=$1 AND id=$2`, workspace, id)
		} else {
			if input.Decision == "create_pending" || input.Decision == "link" {
				if !relink && (event.State != "review" && !retryable || event.TransactionID != nil) {
					return nil, 0, &identity.Error{Status: 409, Code: "source_event_not_ready"}
				}
			} else if event.State != "applied" && !retryable || event.TransactionID == nil || *event.TransactionID != *input.TransactionID {
				return nil, 0, &identity.Error{Status: 409, Code: "source_event_not_ready"}
			}
			if input.Decision == "link" {
				// Explicit attachment is metadata only. Current amount/account/currency/kind/version must agree.
				var valid bool
				err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM transactions t JOIN entries e ON (e.workspace_id,e.transaction_id)=(t.workspace_id,t.id) WHERE t.workspace_id=$1 AND t.id=$2 AND t.version=$3::bigint AND t.deleted_at IS NULL AND t.kind IN ('expense','income','transfer','refund') AND t.status IN ('pending','posted') AND e.account_id=$5 AND e.amount_minor=$6::bigint*CASE WHEN $4='expense' THEN -1 ELSE 1 END)`, workspace, *input.TransactionID, *input.TransactionVersion, event.Kind, event.AccountID, event.Amount).Scan(&valid)
				if err != nil {
					return nil, 0, err
				}
				if !valid {
					return nil, 0, &identity.Error{Status: 409, Code: "version_conflict"}
				}
				if relink {
					err = relinkDeletedSource(ctx, tx, workspace, event, *input.TransactionID, *input.TransactionVersion, p)
				} else {
					_, err = tx.Exec(ctx, `INSERT INTO transaction_sources(workspace_id,event_id,transaction_id) VALUES($1,$2,$3)`, workspace, id, *input.TransactionID)
				}
				if err == nil {
					_, err = tx.Exec(ctx, `UPDATE source_events SET state='applied',version=version+1 WHERE workspace_id=$1 AND id=$2`, workspace, id)
				}
			} else {
				if current >= math.MaxInt64-1 {
					return nil, 0, &identity.Error{Status: 503, Code: "service_unavailable"}
				}
				if event.State == "applied" {
					var attempts int
					if err = tx.QueryRow(ctx, `SELECT jsonb_array_length(job_history) FROM source_events WHERE workspace_id=$1 AND id=$2`, workspace, id).Scan(&attempts); err != nil {
						return nil, 0, err
					}
					if attempts >= 100 {
						return nil, 0, &identity.Error{Status: 422, Code: "source_event_limit"}
					}
					if _, err = tx.Exec(ctx, `UPDATE source_events SET job_history=CASE WHEN job IS NULL THEN job_history ELSE job_history||jsonb_build_array(jsonb_build_object('job',job,'error_code',error_code)) END WHERE workspace_id=$1 AND id=$2`, workspace, id); err != nil {
						return nil, 0, err
					}
				}
				job := sourceJob{SessionID: p.Session.ID, GenerationID: generation, ActionID: newID(), Decision: input.Decision, TransactionID: newID()}
				if input.TransactionID != nil {
					job.TransactionID = *input.TransactionID
					job.ExpectedVersion = *input.TransactionVersion
				}
				body, _ := json.Marshal(job)
				_, err = tx.Exec(ctx, `UPDATE source_events SET state='queued',queued_at=clock_timestamp(),job=$3,session_id=$4,generation_id=$5,error_code=NULL,version=version+1 WHERE workspace_id=$1 AND id=$2`, workspace, id, body, p.Session.ID, generation)
			}
		}
		if err != nil {
			return nil, 0, err
		}
		value, err := ReadSourceEvent(ctx, tx, workspace, id, p.Profile.ID)
		return value, 200, err
	}
}

// Source jobs preserve their original action ID and source observation. Only the recorded session
// can apply them; financial data, source link, journal and receipt commit in one transaction.
func ProcessSourceEvents(ctx context.Context, auth *identity.Service, limit int) (int, error) {
	count := 0
	for count < limit {
		var workspace, id, owner, session, generation string
		var raw []byte
		err := auth.DB.QueryRow(ctx, `SELECT workspace_id::text,id::text,actor_user_id::text,session_id::text,generation_id::text,job FROM source_events WHERE state='queued' ORDER BY workspace_id,id LIMIT 1`).Scan(&workspace, &id, &owner, &session, &generation, &raw)
		if errors.Is(err, pgx.ErrNoRows) {
			return count, nil
		}
		if err != nil {
			return count, err
		}
		var job sourceJob
		if err = json.Unmarshal(raw, &job); err != nil {
			return count, err
		}
		if job.SessionID != session || job.GenerationID != generation {
			return count, errors.New("invalid source job binding")
		}
		request := commands.Request{WorkspaceID: workspace, ActionID: job.ActionID, GenerationID: generation, RequestID: newID(), Method: "POST", Path: "/api/v1/workspaces/" + workspace + "/source-events/" + id + "/apply", Body: raw, Authorize: func(p identity.Principal) error {
			if p.Profile.ID != owner || p.Session.ID != session {
				return &identity.Error{Status: 401, Code: "unauthenticated"}
			}
			return nil
		}}
		result, err := (&commands.Runner{Identity: auth}).ExecuteForWorker(ctx, request, session, func(ctx context.Context, tx pgx.Tx, p identity.Principal) (commands.Mutation, error) {
			event, err := ReadSourceEvent(ctx, tx, workspace, id, owner)
			if err != nil {
				return commands.Mutation{}, err
			}
			if event.State != "queued" {
				return commands.Mutation{}, &identity.Error{Status: 409, Code: "source_event_not_ready"}
			}
			var actual []byte
			if err = tx.QueryRow(ctx, `SELECT job FROM source_events WHERE workspace_id=$1 AND id=$2 FOR UPDATE`, workspace, id).Scan(&actual); err != nil {
				return commands.Mutation{}, err
			}
			var original sourceJob
			if json.Unmarshal(actual, &original) != nil || original != job {
				return commands.Mutation{}, &identity.Error{Status: 409, Code: "source_event_not_ready"}
			}
			var mutation commands.Mutation
			if job.Decision == "create_pending" {
				var currency string
				if err = tx.QueryRow(ctx, `SELECT currency_code FROM accounts WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL`, workspace, event.AccountID).Scan(&currency); err != nil {
					return commands.Mutation{}, err
				}
				if currency != event.Currency {
					return commands.Mutation{}, domainReject(422, "source_event_conflict")
				}
				basic := BasicInput{ID: job.TransactionID, Kind: event.Kind, AccountID: event.AccountID, Amount: event.Amount, OccurredAt: event.ObservedAt, Timezone: event.Timezone, Payee: event.Merchant, TagIDs: []string{}, Allocations: []AllocationInput{{ID: newID(), Amount: event.Amount}}}
				mutation, err = createBasicStatus(workspace, basic, "pending")(ctx, tx, p)
				if err == nil {
					_, err = tx.Exec(ctx, `INSERT INTO transaction_sources(workspace_id,event_id,transaction_id) VALUES($1,$2,$3)`, workspace, id, job.TransactionID)
				}
			} else {
				mutation, err = transitionSourceTransaction(ctx, tx, workspace, event, job)
			}
			if err != nil {
				return commands.Mutation{}, err
			}
			_, err = tx.Exec(ctx, `UPDATE source_events SET state='applied',error_code=NULL,version=version+1 WHERE workspace_id=$1 AND id=$2`, workspace, id)
			return mutation, err
		})
		code := ""
		if err != nil {
			var failure *identity.Error
			if !errors.As(err, &failure) || failure.Status >= 500 {
				return count, err
			}
			code = failure.Code
		}
		if result.Status >= 400 {
			var failure struct {
				Code string `json:"code"`
			}
			json.Unmarshal(result.Body, &failure)
			code = failure.Code
		}
		if code != "" {
			// Never mint another financial action after an unknown/expired receipt.
			if _, err = auth.DB.Exec(ctx, `UPDATE source_events SET state='failed',error_code=$3,version=version+1 WHERE workspace_id=$1 AND id=$2 AND state='queued' AND job->>'action_id'=$4`, workspace, id, code, job.ActionID); err != nil {
				return count, err
			}
		}
		count++
	}
	return count, nil
}
func transitionSourceTransaction(ctx context.Context, tx pgx.Tx, workspace string, event SourceEvent, job sourceJob) (commands.Mutation, error) {
	var currentAccount, currency string
	err := tx.QueryRow(ctx, `SELECT e.account_id::text,a.currency_code FROM entries e JOIN accounts a ON (a.workspace_id,a.id)=(e.workspace_id,e.account_id) WHERE e.workspace_id=$1 AND e.transaction_id=$2 ORDER BY e.account_id LIMIT 1`, workspace, job.TransactionID).Scan(&currentAccount, &currency)
	if errors.Is(err, pgx.ErrNoRows) {
		return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
	}
	if err != nil {
		return commands.Mutation{}, err
	}
	if currency != event.Currency {
		return commands.Mutation{}, domainReject(422, "source_event_conflict")
	}
	accounts, err := lockAccounts(ctx, tx, workspace, currentAccount)
	if err != nil {
		return commands.Mutation{}, err
	}
	var current int64
	var status, kind string
	var deleted *time.Time
	var standalone bool
	err = tx.QueryRow(ctx, `SELECT version,status,kind,deleted_at,parent_transaction_id IS NULL FROM transactions WHERE workspace_id=$1 AND id=$2 FOR UPDATE`, workspace, job.TransactionID).Scan(&current, &status, &kind, &deleted, &standalone)
	if errors.Is(err, pgx.ErrNoRows) {
		return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
	}
	if err != nil {
		return commands.Mutation{}, err
	}
	expected, _ := version(job.ExpectedVersion)
	if current != expected {
		return commands.Mutation{}, transactionVersionConflict(job.TransactionID, current)
	}
	if !standalone || deleted != nil || status != "pending" || kind != event.Kind || event.TransactionID == nil || *event.TransactionID != job.TransactionID {
		return commands.Mutation{}, domainReject(422, "source_event_not_ready")
	}
	if current == math.MaxInt64 {
		return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
	}
	var sameAccount bool
	if err = tx.QueryRow(ctx, `SELECT count(*)=1 AND bool_and(account_id=$3::uuid) FROM entries WHERE workspace_id=$1 AND transaction_id=$2`, workspace, job.TransactionID, currentAccount).Scan(&sameAccount); err != nil {
		return commands.Mutation{}, err
	}
	if !sameAccount {
		return commands.Mutation{}, domainReject(422, "source_event_conflict")
	}
	if job.Decision == "cancel_pending" {
		return DeleteTransaction(workspace, job.TransactionID, TransactionDeleteInput{ExpectedVersion: job.ExpectedVersion, Related: []RelatedVersion{}})(ctx, tx, identity.Principal{})
	}
	if job.Decision != "confirm_posted" {
		return commands.Mutation{}, invalid()
	}
	target := "posted"
	_, err = tx.Exec(ctx, `UPDATE transactions SET status=$3,version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, job.TransactionID, target)
	if err != nil {
		return commands.Mutation{}, err
	}
	account, err := bumpAccount(ctx, tx, workspace, accounts[currentAccount])
	if err != nil {
		return commands.Mutation{}, err
	}
	data, err := ReadTransaction(ctx, tx, workspace, job.TransactionID)
	if err != nil {
		return commands.Mutation{}, err
	}
	return commands.Mutation{Status: 200, Changes: []commands.Change{account, {EntityType: "transaction", ID: job.TransactionID, Version: strconv.FormatInt(current+1, 10), Operation: "upsert", Data: data}}}, nil
}
