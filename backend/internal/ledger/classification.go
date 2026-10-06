package ledger

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"math"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
)

type ClassificationInput struct {
	ID              string  `json:"id,omitempty"`
	ExpectedVersion string  `json:"expected_version,omitempty"`
	Name            string  `json:"name"`
	ParentID        *string `json:"parent_id,omitempty"`
	Archived        bool    `json:"archived"`
}

// decodeObject enforces exact field spelling, presence and nullability, including nested inputs.
func decodeObject(body []byte, out any, required, nullable []string) error {
	if _, e := commands.CanonicalJSON(body); e != nil {
		return invalid()
	}
	var fields map[string]json.RawMessage
	if e := json.Unmarshal(body, &fields); e != nil || len(fields) != len(required) {
		return invalid()
	}
	for _, name := range required {
		value, ok := fields[name]
		if !ok {
			return invalid()
		}
		if bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			allowed := false
			for _, n := range nullable {
				if n == name {
					allowed = true
				}
			}
			if !allowed {
				return invalid()
			}
		}
	}
	d := json.NewDecoder(bytes.NewReader(body))
	d.DisallowUnknownFields()
	if e := d.Decode(out); e != nil {
		return invalid()
	}
	return nil
}

func DecodeClassification(body []byte, kind string, update bool) (ClassificationInput, error) {
	var input ClassificationInput
	if kind != "category" && kind != "tag" {
		return input, invalid()
	}
	fields := []string{"id", "name"}
	if update {
		fields = []string{"expected_version", "name", "archived"}
	}
	nullable := []string{}
	if kind == "category" {
		fields = append(fields, "parent_id")
		nullable = append(nullable, "parent_id")
	}
	if e := decodeObject(body, &input, fields, nullable); e != nil {
		return input, e
	}
	limit := 100
	if kind == "tag" {
		limit = 50
	}
	if n := utf8.RuneCountInString(input.Name); n < 1 || n > limit || strings.TrimSpace(input.Name) == "" || strings.ContainsRune(input.Name, 0) {
		return input, invalid()
	}
	if update {
		if _, e := version(input.ExpectedVersion); e != nil {
			return input, e
		}
	} else {
		if !uuid(input.ID) {
			return input, invalid()
		}
		input.ID = strings.ToLower(input.ID)
	}
	if input.ParentID != nil {
		if !uuid(*input.ParentID) {
			return input, invalid()
		}
		id := strings.ToLower(*input.ParentID)
		input.ParentID = &id
	}
	return input, nil
}

// ClassificationCommand is serialized by CommandRunner's workspace head, including cycle/name checks.
func ClassificationCommand(workspace, id, kind string, update bool, input ClassificationInput) commands.Command {
	return func(ctx context.Context, tx pgx.Tx, _ identity.Principal) (commands.Mutation, error) {
		if kind != "category" && kind != "tag" {
			return commands.Mutation{}, invalid()
		}
		if !update {
			id = input.ID
		}
		id = strings.ToLower(id)
		if !uuid(id) {
			return commands.Mutation{}, &identity.Error{Status: 400, Code: "bad_request"}
		}
		name := strings.TrimSpace(input.Name)
		limit := 100
		if kind == "tag" {
			limit = 50
		}
		if name == "" || !validText(name, limit) {
			return commands.Mutation{}, invalid()
		}
		current := int64(0)
		var previousParent *string
		if update {
			var e error
			if kind == "category" {
				e = tx.QueryRow(ctx, `SELECT version,parent_id::text FROM categories WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL FOR UPDATE`, workspace, id).Scan(&current, &previousParent)
			} else {
				e = tx.QueryRow(ctx, `SELECT version FROM tags WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL FOR UPDATE`, workspace, id).Scan(&current)
			}
			if errors.Is(e, pgx.ErrNoRows) {
				return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
			}
			if e != nil {
				return commands.Mutation{}, e
			}
			expected, e := version(input.ExpectedVersion)
			if e != nil {
				return commands.Mutation{}, e
			}
			if expected != current {
				return commands.Mutation{}, &commands.Rejection{Status: 409, Code: "version_conflict", Message: "Классификация изменена.", CurrentVersions: []any{map[string]any{"entity_type": kind, "id": id, "version": strconv.FormatInt(current, 10), "balance_version": nil}}}
			}
			if current == math.MaxInt64 {
				return commands.Mutation{}, &identity.Error{Status: 503, Code: "service_unavailable"}
			}
		}
		if kind == "category" && input.ParentID != nil {
			parent := strings.ToLower(*input.ParentID)
			if !uuid(parent) {
				return commands.Mutation{}, invalid()
			}
			input.ParentID = &parent
			if parent == id {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
			var archived bool
			e := tx.QueryRow(ctx, `SELECT archived_at IS NOT NULL FROM categories WHERE workspace_id=$1 AND id=$2 AND deleted_at IS NULL`, workspace, parent).Scan(&archived)
			if errors.Is(e, pgx.ErrNoRows) {
				return commands.Mutation{}, &identity.Error{Status: 404, Code: "not_found"}
			}
			if e != nil {
				return commands.Mutation{}, e
			}
			if archived && (previousParent == nil || *previousParent != parent) {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
			var cycle bool
			e = tx.QueryRow(ctx, `WITH RECURSIVE ancestors AS (SELECT id,parent_id FROM categories WHERE workspace_id=$1 AND id=$2 UNION SELECT c.id,c.parent_id FROM categories c JOIN ancestors a ON c.id=a.parent_id WHERE c.workspace_id=$1) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=$3)`, workspace, parent, id).Scan(&cycle)
			if e != nil {
				return commands.Mutation{}, e
			}
			if cycle || parent == id {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
		}
		if kind == "tag" && (!update || !input.Archived) {
			var duplicate bool
			e := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM tags WHERE workspace_id=$1 AND name_normalized=$2 AND id<>$3 AND archived_at IS NULL AND deleted_at IS NULL)`, workspace, strings.ToLower(name), id).Scan(&duplicate)
			if e != nil {
				return commands.Mutation{}, e
			}
			if duplicate {
				return commands.Mutation{}, domainReject(422, "validation_error")
			}
		}
		if update {
			var e error
			if kind == "category" {
				_, e = tx.Exec(ctx, `UPDATE categories SET name=$3,parent_id=$4,archived_at=CASE WHEN $5 THEN coalesce(archived_at,clock_timestamp()) ELSE NULL END,version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, id, name, input.ParentID, input.Archived)
			} else {
				_, e = tx.Exec(ctx, `UPDATE tags SET name=$3,name_normalized=$4,archived_at=CASE WHEN $5 THEN coalesce(archived_at,clock_timestamp()) ELSE NULL END,version=version+1,updated_at=clock_timestamp() WHERE workspace_id=$1 AND id=$2`, workspace, id, name, strings.ToLower(name), input.Archived)
			}
			if e != nil {
				return commands.Mutation{}, e
			}
		} else {
			var count int64
			if kind == "category" {
				tag, e := tx.Exec(ctx, `INSERT INTO categories(id,workspace_id,name,parent_id) VALUES($1,$2,$3,$4) ON CONFLICT(id) DO NOTHING`, id, workspace, name, input.ParentID)
				if e != nil {
					return commands.Mutation{}, e
				}
				count = tag.RowsAffected()
			} else {
				tag, e := tx.Exec(ctx, `INSERT INTO tags(id,workspace_id,name,name_normalized) VALUES($1,$2,$3,$4) ON CONFLICT(id) DO NOTHING`, id, workspace, name, strings.ToLower(name))
				if e != nil {
					return commands.Mutation{}, e
				}
				count = tag.RowsAffected()
			}
			if count != 1 {
				return commands.Mutation{}, domainReject(409, "validation_error")
			}
		}
		data, e := ReadClassification(ctx, tx, workspace, id, kind)
		if e != nil {
			return commands.Mutation{}, e
		}
		status := 201
		if update {
			status = 200
		}
		return commands.Mutation{Status: status, Changes: []commands.Change{{EntityType: kind, ID: id, Version: strconv.FormatInt(current+1, 10), Operation: "upsert", Data: data}}}, nil
	}
}

func classificationSelect(kind string) (string, error) {
	if kind != "category" && kind != "tag" {
		return "", invalid()
	}
	extra := ""
	table := "tags"
	if kind == "category" {
		table = "categories"
		extra = ",'parent_id',c.parent_id"
	}
	return `SELECT jsonb_build_object('id',c.id,'workspace_id',c.workspace_id,'version',c.version::text,'name',c.name,'created_at',to_char(c.created_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),'updated_at',to_char(c.updated_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),'deleted_at',NULL,'archived_at',CASE WHEN c.archived_at IS NULL THEN NULL ELSE to_char(c.archived_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"') END` + extra + `) FROM ` + table + ` c`, nil
}
func ReadClassification(ctx context.Context, tx pgx.Tx, workspace, id, kind string) (json.RawMessage, error) {
	if !uuid(workspace) || !uuid(id) {
		return nil, &identity.Error{Status: 400, Code: "bad_request"}
	}
	sql, e := classificationSelect(kind)
	if e != nil {
		return nil, e
	}
	var data json.RawMessage
	e = tx.QueryRow(ctx, sql+` WHERE c.workspace_id=$1 AND c.id=$2 AND c.deleted_at IS NULL`, workspace, id).Scan(&data)
	if errors.Is(e, pgx.ErrNoRows) {
		return nil, &identity.Error{Status: 404, Code: "not_found"}
	}
	return data, e
}
func ListClassification(ctx context.Context, tx pgx.Tx, workspace, after, archived, kind string, limit int) ([]json.RawMessage, []string, error) {
	sql, e := classificationSelect(kind)
	if e != nil {
		return nil, nil, e
	}
	rows, e := tx.Query(ctx, sql+` WHERE c.workspace_id=$1 AND c.deleted_at IS NULL AND ($2='' OR c.id>NULLIF($2,'')::uuid) AND ($3='include' OR ($3='only' AND c.archived_at IS NOT NULL) OR ($3='exclude' AND c.archived_at IS NULL)) ORDER BY c.id LIMIT $4`, workspace, after, archived, limit)
	if e != nil {
		return nil, nil, e
	}
	return scanAggregates(rows)
}
func scanAggregates(rows pgx.Rows) ([]json.RawMessage, []string, error) {
	defer rows.Close()
	items := []json.RawMessage{}
	ids := []string{}
	for rows.Next() {
		var data json.RawMessage
		if e := rows.Scan(&data); e != nil {
			return nil, nil, e
		}
		var item struct {
			ID string `json:"id"`
		}
		if e := json.Unmarshal(data, &item); e != nil {
			return nil, nil, e
		}
		items = append(items, data)
		ids = append(ids, item.ID)
	}
	return items, ids, rows.Err()
}

func validText(s string, max int) bool {
	return utf8.ValidString(s) && utf8.RuneCountInString(s) <= max && !strings.ContainsRune(s, 0)
}
