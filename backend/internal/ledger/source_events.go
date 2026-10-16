package ledger

import (
	"accounting/backend/internal/identity"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"regexp"
	"strings"
	"time"
	"unicode"

	"github.com/jackc/pgx/v5"
)

type SourceEventInput struct {
	NotificationKeyHash *string `json:"notification_key_hash,omitempty"`
	ID                  string  `json:"id"`
	ConnectionID        string  `json:"connection_id"`
	ExternalID          string  `json:"external_id"`
	AccountID           string  `json:"account_id"`
	PackageName         string  `json:"package_name"`
	ProfileID           string  `json:"profile_id"`
	ParserVersion       int     `json:"parser_version"`
	Kind                string  `json:"kind"`
	Amount              string  `json:"amount_minor"`
	Currency            string  `json:"currency"`
	ObservedAt          string  `json:"observed_at"`
	Timezone            string  `json:"observed_timezone"`
	Merchant            string  `json:"merchant"`
	Card                *string `json:"card_last_four"`
}
type SourceEvent struct {
	TransactionID *string `json:"transaction_id"`
	ErrorCode     *string `json:"error_code"`
	SourceEventInput
	Workspace  string    `json:"workspace_id"`
	Version    string    `json:"version"`
	State      string    `json:"state"`
	ReceivedAt time.Time `json:"received_at"`
}

// The slot digest is private intake provenance, omitted from public responses for old strict clients.
func (event SourceEvent) MarshalJSON() ([]byte, error) {
	type publicEvent SourceEvent
	event.SourceEventInput.NotificationKeyHash = nil
	return json.Marshal(publicEvent(event))
}

var sourceDigest = regexp.MustCompile(`^[0-9a-f]{64}$`)
var sourcePackage = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`)
var lastFour = regexp.MustCompile(`^[0-9]{4}$`)

func DecodeSourceEvent(body []byte) (SourceEventInput, error) {
	var v SourceEventInput
	fields := []string{"id", "connection_id", "external_id", "account_id", "package_name", "profile_id", "parser_version", "kind", "amount_minor", "currency", "observed_at", "observed_timezone", "merchant", "card_last_four"}
	var present map[string]json.RawMessage
	if json.Unmarshal(body, &present) == nil {
		if _, ok := present["notification_key_hash"]; ok {
			fields = append(fields, "notification_key_hash")
		}
	}
	if err := decodeObject(body, &v, fields, []string{"card_last_four", "notification_key_hash"}); err != nil {
		return v, err
	}
	if !uuid(v.ID) || !uuid(v.ConnectionID) || !uuid(v.AccountID) || !sourceDigest.MatchString(v.ExternalID) || len(v.PackageName) > 200 || !sourcePackage.MatchString(v.PackageName) || !validText(v.ProfileID, 64) || strings.TrimSpace(v.ProfileID) == "" || v.ParserVersion != 1 || (v.Kind != "expense" && v.Kind != "income") || !validZone(v.Timezone) || !validText(v.Merchant, 120) {
		return v, invalid()
	}
	if v.NotificationKeyHash != nil && !sourceDigest.MatchString(*v.NotificationKeyHash) {
		return v, invalid()
	}
	if v.Card != nil && !lastFour.MatchString(*v.Card) {
		return v, invalid()
	}
	if _, err := ParsePositiveMoney(v.Amount); err != nil {
		return v, invalid()
	}
	if _, err := ParseInstant(v.ObservedAt); err != nil {
		return v, invalid()
	}
	if len(v.Currency) != 3 || strings.IndexFunc(v.Currency, func(r rune) bool { return r < 'A' || r > 'Z' }) >= 0 || strings.IndexFunc(v.Merchant, unicode.IsControl) >= 0 {
		return v, invalid()
	}
	v.ID = strings.ToLower(v.ID)
	v.AccountID = strings.ToLower(v.AccountID)
	v.ConnectionID = strings.ToLower(v.ConnectionID)
	return v, nil
}

// Intake shares auxiliary durable receipts and the head lock with CSV. It never creates money entries.
func CreateSourceEvent(workspace, generation string, input SourceEventInput) importSetting {
	return func(ctx context.Context, tx pgx.Tx, p identity.Principal) (any, int, error) {
		var currency string
		var archived bool
		err := tx.QueryRow(ctx, `SELECT currency_code,archived_at IS NOT NULL FROM accounts WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL`, workspace, input.AccountID).Scan(&currency, &archived)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, 0, &identity.Error{Status: 404, Code: "not_found"}
		}
		if err != nil {
			return nil, 0, err
		}
		if archived || currency != input.Currency {
			return nil, 0, &identity.Error{Status: 422, Code: "validation_error"}
		}
		// Android notification identity is independent of parser/profile/account assignment.
		// Preserve ambiguous legacy observations; never merge their financial records in migration.
		hash := sourceFactsHash(input)
		rows, err := tx.Query(ctx, `SELECT id::text,payload_hash,payload_hash_format,payload FROM source_events WHERE workspace_id=$1 AND actor_user_id=$2 AND external_id=$3 AND payload->>'package_name'=$4 ORDER BY received_at,id LIMIT 2`, workspace, p.Profile.ID, input.ExternalID, input.PackageName)
		if err != nil {
			return nil, 0, err
		}
		var oldID string
		var oldHash, oldPayload []byte
		var format, countOld int
		for rows.Next() {
			countOld++
			if err = rows.Scan(&oldID, &oldHash, &format, &oldPayload); err != nil {
				rows.Close()
				return nil, 0, err
			}
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return nil, 0, err
		}
		if countOld > 1 {
			return nil, 0, &identity.Error{Status: 409, Code: "source_event_conflict"}
		}
		if countOld == 1 {
			var old SourceEventInput
			if err = json.Unmarshal(oldPayload, &old); err != nil {
				return nil, 0, err
			}
			oldFacts := sourceFactsHash(old)
			expected := oldFacts
			if format == 1 {
				old.ID = ""
				raw, _ := json.Marshal(old)
				expected = sha256.Sum256(raw)
			}
			if subtle.ConstantTimeCompare(oldHash, expected[:]) != 1 {
				return nil, 0, &identity.Error{Status: 503, Code: "service_unavailable"}
			}
			if subtle.ConstantTimeCompare(oldFacts[:], hash[:]) != 1 {
				return nil, 0, &identity.Error{Status: 409, Code: "source_event_conflict"}
			}
			value, err := ReadSourceEvent(ctx, tx, workspace, oldID, p.Profile.ID)
			return value, 200, err
		}
		var count int
		if err = tx.QueryRow(ctx, `SELECT count(*) FROM source_events WHERE workspace_id=$1 AND actor_user_id=$2`, workspace, p.Profile.ID).Scan(&count); err != nil {
			return nil, 0, err
		}
		if count >= 10000 {
			return nil, 0, &identity.Error{Status: 422, Code: "source_event_limit"}
		}
		raw, err := json.Marshal(input)
		if err != nil {
			return nil, 0, err
		}
		tag, err := tx.Exec(ctx, `INSERT INTO source_events(workspace_id,id,actor_user_id,session_id,generation_id,connection_id,external_id,account_id,payload_hash,payload,notification_identity,payload_hash_format) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,2) ON CONFLICT(workspace_id,id) DO NOTHING`, workspace, input.ID, p.Profile.ID, p.Session.ID, generation, input.ConnectionID, input.ExternalID, input.AccountID, hash[:], raw, sourceNotificationIdentity(input))
		if err != nil {
			return nil, 0, err
		}
		if tag.RowsAffected() != 1 {
			return nil, 0, &identity.Error{Status: 409, Code: "resource_conflict"}
		}
		value, err := ReadSourceEvent(ctx, tx, workspace, input.ID, p.Profile.ID)
		return value, 201, err
	}
}
func ReadSourceEvent(ctx context.Context, tx pgx.Tx, workspace, id, owner string) (SourceEvent, error) {
	var value SourceEvent
	var raw []byte
	if !uuid(id) {
		return value, &identity.Error{Status: 400, Code: "bad_request"}
	}
	err := tx.QueryRow(ctx, `SELECT payload,workspace_id::text,version::text,state,received_at,(SELECT transaction_id::text FROM transaction_sources s WHERE s.workspace_id=source_events.workspace_id AND s.event_id=source_events.id),error_code FROM source_events WHERE workspace_id=$1 AND id=$2 AND actor_user_id=$3`, workspace, id, owner).Scan(&raw, &value.Workspace, &value.Version, &value.State, &value.ReceivedAt, &value.TransactionID, &value.ErrorCode)
	if errors.Is(err, pgx.ErrNoRows) {
		return value, &identity.Error{Status: 404, Code: "not_found"}
	}
	if err != nil {
		return value, err
	}
	err = json.Unmarshal(raw, &value.SourceEventInput)
	return value, err
}

// Provenance stays immutable on the original row, but changing a parser identifier cannot mint money.
func sourceFactsHash(input SourceEventInput) [32]byte {
	input.NotificationKeyHash = nil
	input.ID = ""
	input.ConnectionID = ""
	input.ProfileID = ""
	input.ParserVersion = 0
	input.Timezone = ""
	if instant, err := ParseInstant(input.ObservedAt); err == nil {
		input.ObservedAt = instant.UTC().Format(time.RFC3339Nano)
	}
	raw, _ := json.Marshal(input)
	return sha256.Sum256(raw)
}
func sourceNotificationIdentity(input SourceEventInput) []byte {
	hash := sha256.Sum256([]byte(input.PackageName + "\x00" + input.ExternalID))
	return hash[:]
}
