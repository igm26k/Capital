package sync

import (
	"encoding/json"
	"strconv"
	"time"
)

type event struct {
	Schema      int             `json:"schema_version"`
	ChangeID    string          `json:"change_id"`
	ActionID    string          `json:"action_id"`
	WorkspaceID string          `json:"workspace_id"`
	Generation  string          `json:"generation_id"`
	Sequence    string          `json:"sequence"`
	Ordinal     int             `json:"ordinal"`
	GroupSize   int             `json:"group_size"`
	Created     time.Time       `json:"created_at"`
	Operation   string          `json:"operation"`
	EntityType  string          `json:"entity_type"`
	Data        json.RawMessage `json:"data"`
	DataID      string          `json:"-"`
	Version     int64           `json:"-"`
}

func journalSize(r Request, events []event, sequence int64) (int, error) {
	b, e := json.Marshal(map[string]any{"sequence": strconv.FormatInt(sequence, 10), "action_id": r.ActionID, "workspace_id": r.WorkspaceID, "generation_id": r.GenerationID, "changes": events})
	return len(b), e
}
func buildJournal(r Request, m Mutation, sequence int64) ([]event, []byte, []byte, int, error) {
	if len(m.Changes) > 200 {
		return nil, nil, nil, 0, &Rejection{Status: 422, Code: "sync_group_too_large", Message: "Группа изменений слишком велика."}
	}
	if len(m.Changes) == 0 || m.Status != 200 && m.Status != 201 {
		return nil, nil, nil, 0, problem(500, "internal_error")
	}
	body := map[string]any{"action_id": r.ActionID, "generation_id": r.GenerationID, "sync_group_sequence": strconv.FormatInt(sequence, 10), "accounts": []json.RawMessage{}, "transactions": []json.RawMessage{}, "categories": []json.RawMessage{}, "tags": []json.RawMessage{}, "deleted": []json.RawMessage{}}
	keys := map[string]string{"account": "accounts", "transaction": "transactions", "category": "categories", "tag": "tags"}
	events := []event{}
	refs := []map[string]any{}
	seen := map[string]bool{}
	for i, c := range m.Changes {
		key, ok := keys[c.EntityType]
		version, e := strconv.ParseInt(c.Version, 10, 64)
		if !ok || e != nil || version < 1 || strconv.FormatInt(version, 10) != c.Version || !uuidPattern.MatchString(c.ID) || seen[c.EntityType+":"+c.ID] || c.Operation != "upsert" && c.Operation != "delete" {
			return nil, nil, nil, 0, problem(500, "internal_error")
		}
		seen[c.EntityType+":"+c.ID] = true
		if _, e := CanonicalJSON(c.Data); e != nil {
			return nil, nil, nil, 0, problem(500, "internal_error")
		}
		var payload map[string]json.RawMessage
		if e = json.Unmarshal(c.Data, &payload); e != nil || payloadString(payload["id"]) != c.ID || payloadString(payload["workspace_id"]) != r.WorkspaceID || payloadString(payload["version"]) != c.Version {
			return nil, nil, nil, 0, problem(500, "internal_error")
		}
		if c.Operation == "delete" {
			if payloadString(payload["entity_type"]) != c.EntityType || payloadString(payload["deleted_at"]) == "" {
				return nil, nil, nil, 0, problem(500, "internal_error")
			}
			key = "deleted"
		}
		body[key] = append(body[key].([]json.RawMessage), c.Data)
		refs = append(refs, map[string]any{"entity_type": c.EntityType, "id": c.ID, "version": c.Version})
		events = append(events, event{Schema: 1, ChangeID: newUUID(), ActionID: r.ActionID, WorkspaceID: r.WorkspaceID, Generation: r.GenerationID, Sequence: strconv.FormatInt(sequence, 10), Ordinal: i + 1, GroupSize: len(m.Changes), Operation: c.Operation, EntityType: c.EntityType, Data: c.Data, DataID: c.ID, Version: version})
	}
	response, e := json.Marshal(body)
	if e != nil {
		return nil, nil, nil, 0, problem(500, "internal_error")
	}
	references, e := json.Marshal(refs)
	return events, response, references, 0, e
}

func payloadString(raw json.RawMessage) string {
	var value string
	if json.Unmarshal(raw, &value) != nil {
		return ""
	}
	return value
}
