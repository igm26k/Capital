package ledger

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math"
	"regexp"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
)

type AccountCreate struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	Type           string `json:"type"`
	Currency       string `json:"currency"`
	OpenedAt       string `json:"opened_at"`
	Timezone       string `json:"occurred_timezone"`
	OpeningBalance string `json:"opening_balance_minor"`
}
type AccountUpdate struct {
	ExpectedVersion string `json:"expected_version"`
	Name            string `json:"name"`
	Type            string `json:"type"`
	Archived        bool   `json:"archived"`
}

// DecodeAccountCreate validates the complete wire shape before action registration.
func DecodeAccountCreate(body []byte) (AccountCreate, error) {
	var v AccountCreate
	err := decodeFields(body, &v, "id", "name", "type", "currency", "opened_at", "occurred_timezone", "opening_balance_minor")
	if err != nil {
		return v, err
	}
	if !uuid(v.ID) || !accountMetadata(v.Name, v.Type) || len(v.Currency) != 3 || v.Currency[0] < 'A' || v.Currency[0] > 'Z' || v.Currency[1] < 'A' || v.Currency[1] > 'Z' || v.Currency[2] < 'A' || v.Currency[2] > 'Z' {
		return v, invalid()
	}
	if _, err = ParseMoney(v.OpeningBalance); err != nil {
		return v, invalid()
	}
	if _, err = ParseInstant(v.OpenedAt); err != nil {
		return v, invalid()
	}
	if !validZone(v.Timezone) {
		return v, invalid()
	}
	v.ID = strings.ToLower(v.ID)
	return v, nil
}
func DecodeAccountUpdate(body []byte) (AccountUpdate, error) {
	var v AccountUpdate
	if err := decodeFields(body, &v, "expected_version", "name", "type", "archived"); err != nil {
		return v, err
	}
	if _, err := version(v.ExpectedVersion); err != nil || !accountMetadata(v.Name, v.Type) {
		return v, invalid()
	}
	return v, nil
}
func decodeFields(body []byte, out any, names ...string) error {
	return decodeObject(body, out, names, nil)
}
func invalid() error { return &identity.Error{Status: 422, Code: "validation_error"} }
func domainReject(status int, code string) error {
	return &commands.Rejection{Status: status, Code: code, Message: "Не удалось применить команду учета."}
}
func accountMetadata(name, kind string) bool {
	n := utf8.RuneCountInString(name)
	return n >= 1 && validText(name, 100) && strings.TrimSpace(name) != "" && (kind == "cash" || kind == "bank" || kind == "card")
}
func uuid(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i, c := range s {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if c != '-' {
				return false
			}
		} else if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F') {
			return false
		}
	}
	return true
}
func version(s string) (int64, error) {
	if !canonicalInteger(s) || s == "0" || s[0] == '-' {
		return 0, invalid()
	}
	n, e := strconv.ParseInt(s, 10, 64)
	if e != nil {
		return 0, invalid()
	}
	return n, nil
}

var instantPattern = regexp.MustCompile(`^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?Z$`)

// ParseInstant accepts the canonical UTC RFC3339 shape used by the API.
func ParseInstant(s string) (time.Time, error) {
	if !instantPattern.MatchString(s) {
		return time.Time{}, invalid()
	}
	t, e := time.Parse(time.RFC3339Nano, s)
	if e != nil || t.Year() < 1 {
		return time.Time{}, invalid()
	}
	return t, nil
}
func validZone(s string) bool {
	if s == "" || s == "Local" || len(s) > 100 {
		return false
	}
	_, e := time.LoadLocation(s)
	return e == nil
}
func newID() string {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		panic("identifier unavailable")
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	s := hex.EncodeToString(b[:])
	return s[:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:]
}

// CreateAccount must run inside CommandRunner after membership and head locks.
func CreateAccount(workspace string, input AccountCreate) commands.Command {
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		// Validate service callers as well as HTTP callers.
		b, e := json.Marshal(input)
		if e != nil {
			return commands.Mutation{}, e
		}
		input, e = DecodeAccountCreate(b)
		if e != nil {
			return commands.Mutation{}, e
		}
		opened, _ := ParseInstant(input.OpenedAt)
		amount, _ := ParseMoney(input.OpeningBalance)
		var now time.Time
		var supported bool
		if e = tx.QueryRow(ctx, `SELECT clock_timestamp(),EXISTS(SELECT 1 FROM currencies WHERE code=$1)`, input.Currency).Scan(&now, &supported); e != nil {
			return commands.Mutation{}, e
		}
		if opened.After(now) || !supported {
			return commands.Mutation{}, domainReject(422, "validation_error")
		}
		tag, e := tx.Exec(ctx, `INSERT INTO accounts(id,workspace_id,name,type,currency_code,opened_at) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(id) DO NOTHING`, input.ID, workspace, strings.TrimSpace(input.Name), input.Type, input.Currency, opened)
		if e != nil {
			return commands.Mutation{}, e
		}
		if tag.RowsAffected() != 1 {
			return commands.Mutation{}, domainReject(409, "validation_error")
		}
		openingID := newID()
		if _, e = tx.Exec(ctx, `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,opening_account_id) VALUES($1,$2,'opening','posted',$3,$4,$5)`, openingID, workspace, opened, input.Timezone, input.ID); e != nil {
			return commands.Mutation{}, e
		}
		if amount.Minor() != 0 {
			if _, e = tx.Exec(ctx, `INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor) VALUES($1,$2,$3,$4,$5)`, newID(), workspace, openingID, input.ID, amount.Minor()); e != nil {
				return commands.Mutation{}, e
			}
		}
		account, e := ReadAccount(ctx, tx, workspace, input.ID)
		if e != nil {
			return commands.Mutation{}, e
		}
		opening, e := readOpening(ctx, tx, workspace, openingID)
		if e != nil {
			return commands.Mutation{}, e
		}
		return commands.Mutation{Status: 201, Changes: []commands.Change{{EntityType: "account", ID: input.ID, Version: "1", Operation: "upsert", Data: account}, {EntityType: "transaction", ID: openingID, Version: "1", Operation: "upsert", Data: opening}}}, nil
	}
}

func UpdateAccount(workspace, id string, input AccountUpdate) commands.Command {
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		if !uuid(id) {
			return commands.Mutation{}, &identity.Error{Status: 400, Code: "bad_request"}
		}
		b, e := json.Marshal(input)
		if e != nil {
			return commands.Mutation{}, e
		}
		input, e = DecodeAccountUpdate(b)
		if e != nil {
			return commands.Mutation{}, e
		}
		var current, balance int64
		var archived *time.Time
		e = tx.QueryRow(ctx, `SELECT version,balance_version,archived_at FROM accounts WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL FOR UPDATE`, workspace, id).Scan(&current, &balance, &archived)
		if errors.Is(e, pgx.ErrNoRows) {
			return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
		}
		if e != nil {
			return commands.Mutation{}, e
		}
		expected, _ := version(input.ExpectedVersion)
		if expected != current {
			return commands.Mutation{}, &commands.Rejection{Status: 409, Code: "version_conflict", Message: "Счет изменен.", CurrentVersions: []any{map[string]any{"entity_type": "account", "id": strings.ToLower(id), "version": strconv.FormatInt(current, 10), "balance_version": strconv.FormatInt(balance, 10)}}}
		}
		if current == math.MaxInt64 {
			return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
		}
		if _, e = tx.Exec(ctx, `UPDATE accounts SET name=$3,type=$4,archived_at=CASE WHEN $5 THEN coalesce(archived_at,clock_timestamp()) ELSE NULL END,version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, id, strings.TrimSpace(input.Name), input.Type, input.Archived); e != nil {
			return commands.Mutation{}, e
		}
		data, e := ReadAccount(ctx, tx, workspace, id)
		if e != nil {
			return commands.Mutation{}, e
		}
		return commands.Mutation{Status: 200, Changes: []commands.Change{{EntityType: "account", ID: strings.ToLower(id), Version: strconv.FormatInt(current+1, 10), Operation: "upsert", Data: data}}}, nil
	}
}

// Each aggregate and all balance components are materialized in one MVCC statement.
const accountSelect = `SELECT jsonb_build_object('id',a.id,'workspace_id',a.workspace_id,'version',a.version::text,'balance_version',a.balance_version::text,'name',a.name,'type',a.type,'currency',a.currency_code,'opened_at',to_char(a.opened_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),'created_at',to_char(a.created_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),'updated_at',to_char(a.updated_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),'deleted_at',NULL,'archived_at',CASE WHEN a.archived_at IS NULL THEN NULL ELSE to_char(a.archived_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"') END,'posted_balance_minor',b.posted::text,'pending_delta_minor',b.pending::text,'projected_balance_minor',(b.posted+b.pending)::text)
 FROM accounts a CROSS JOIN LATERAL (SELECT coalesce(sum(e.amount_minor) FILTER(WHERE t.status='posted'),0) AS posted,coalesce(sum(e.amount_minor) FILTER(WHERE t.status='pending'),0) AS pending FROM entries e JOIN transactions t ON t.workspace_id=e.workspace_id AND t.id=e.transaction_id WHERE e.workspace_id=a.workspace_id AND e.account_id=a.id AND t.deleted_at IS NULL) b`

func ReadAccount(ctx context.Context, tx pgx.Tx, workspace, id string) (json.RawMessage, error) {
	if !uuid(id) || !uuid(workspace) {
		return nil, &identity.Error{Status: 400, Code: "bad_request"}
	}
	var data json.RawMessage
	e := tx.QueryRow(ctx, accountSelect+` WHERE a.workspace_id=$1 AND a.id=$2 AND a.deleted_at IS NULL`, workspace, id).Scan(&data)
	if errors.Is(e, pgx.ErrNoRows) {
		return nil, &identity.Error{Status: 404, Code: "not_found"}
	}
	return data, e
}
func ListAccounts(ctx context.Context, tx pgx.Tx, workspace, after, archived string, limit int) ([]json.RawMessage, []string, error) {
	rows, e := tx.Query(ctx, accountSelect+` WHERE a.workspace_id=$1 AND a.deleted_at IS NULL AND ($2='' OR a.id>NULLIF($2,'')::uuid) AND ($3='include' OR ($3='only' AND a.archived_at IS NOT NULL) OR ($3='exclude' AND a.archived_at IS NULL)) ORDER BY a.id LIMIT $4`, workspace, after, archived, limit)
	if e != nil {
		return nil, nil, e
	}
	defer rows.Close()
	items := []json.RawMessage{}
	ids := []string{}
	for rows.Next() {
		var data json.RawMessage
		if e = rows.Scan(&data); e != nil {
			return nil, nil, e
		}
		var item struct {
			ID string `json:"id"`
		}
		if e = json.Unmarshal(data, &item); e != nil {
			return nil, nil, e
		}
		items = append(items, data)
		ids = append(ids, item.ID)
	}
	return items, ids, rows.Err()
}
func readOpening(ctx context.Context, tx pgx.Tx, workspace, id string) (json.RawMessage, error) {
	return ReadTransaction(ctx, tx, workspace, id)
}
