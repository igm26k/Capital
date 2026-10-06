package sync

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"strings"
)

type snapshotPosition struct {
	Protocol   int    `json:"protocol"`
	Kind       string `json:"kind"`
	Key        string `json:"key"`
	Actor      string `json:"actor"`
	Workspace  string `json:"workspace"`
	Generation string `json:"generation"`
	Snapshot   string `json:"snapshot"`
	Base       string `json:"base"`
	Position   int    `json:"position"`
}

func (k *CursorKeys) snapshotKey(id string) (*CursorKeys, error) {
	if k == nil {
		return nil, problem(503, "service_unavailable")
	}
	if len(k.Keys[id]) < 32 {
		return nil, problem(410, "snapshot_expired")
	}
	return &CursorKeys{Active: id, Keys: k.Keys}, nil
}
func (k *CursorKeys) pageToken(actor string, s Snapshot, position int) (string, error) {
	if k == nil || len(k.Keys[k.Active]) < 32 {
		return "", problem(503, "service_unavailable")
	}
	raw, _ := json.Marshal(snapshotPosition{1, "snapshot-page", k.Active, actor, s.Workspace, s.Generation, s.ID, s.Base, position})
	mac := hmac.New(sha256.New, k.Keys[k.Active])
	mac.Write(raw)
	return base64.RawURLEncoding.EncodeToString(raw) + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), nil
}
func (k *CursorKeys) pagePosition(token, actor string, s Snapshot) (int, error) {
	invalid := problem(400, "cursor_invalid")
	if k == nil {
		return 0, problem(503, "service_unavailable")
	}
	if len(token) > 2048 {
		return 0, invalid
	}
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		return 0, invalid
	}
	raw, e := base64.RawURLEncoding.DecodeString(parts[0])
	if e != nil {
		return 0, invalid
	}
	sig, e := base64.RawURLEncoding.DecodeString(parts[1])
	if e != nil {
		return 0, invalid
	}
	var p snapshotPosition
	if json.Unmarshal(raw, &p) != nil || len(k.Keys[p.Key]) < 32 {
		return 0, invalid
	}
	canonical, _ := json.Marshal(p)
	mac := hmac.New(sha256.New, k.Keys[p.Key])
	mac.Write(raw)
	if !bytes.Equal(canonical, raw) || !hmac.Equal(mac.Sum(nil), sig) || p.Protocol != 1 || p.Kind != "snapshot-page" || p.Actor != actor || p.Workspace != s.Workspace || p.Generation != s.Generation || p.Snapshot != s.ID || p.Base != s.Base || p.Key != k.Active || p.Position < 0 || p.Position%100 != 0 || (p.Position >= s.Count && !(p.Position == 0 && s.Count == 0)) {
		return 0, invalid
	}
	return p.Position, nil
}
