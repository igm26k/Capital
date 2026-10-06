package sync

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
)

// CursorKeys keeps prior signing keys available during rotation. The active key
// signs new tokens; removing an old key deliberately requires a new bootstrap.
type CursorKeys struct {
	Active string
	Keys   map[string][]byte
}
type cursorPosition struct {
	Protocol   int    `json:"protocol"`
	Key        string `json:"key"`
	Actor      string `json:"actor"`
	Workspace  string `json:"workspace"`
	Generation string `json:"generation"`
	Sequence   string `json:"sequence"`
}

func NewCursorKeys(active string, keys map[string][]byte) (*CursorKeys, error) {
	if active == "" || len(keys[active]) < 32 {
		return nil, errors.New("invalid sync cursor keys")
	}
	out := &CursorKeys{Active: active, Keys: map[string][]byte{}}
	for id, key := range keys {
		if id == "" || len(id) > 64 || strings.Contains(id, ".") || len(key) < 32 {
			return nil, errors.New("invalid sync cursor keys")
		}
		out.Keys[id] = append([]byte(nil), key...)
	}
	return out, nil
}
func (k *CursorKeys) Sign(actor, workspace, generation string, sequence int64) (string, error) {
	if k == nil || len(k.Keys[k.Active]) < 32 || !uuidPattern.MatchString(actor) || !uuidPattern.MatchString(workspace) || !uuidPattern.MatchString(generation) || sequence < 0 {
		return "", problem(503, "service_unavailable")
	}
	raw, _ := json.Marshal(cursorPosition{1, k.Active, actor, workspace, generation, strconv.FormatInt(sequence, 10)})
	mac := hmac.New(sha256.New, k.Keys[k.Active])
	mac.Write(raw)
	return base64.RawURLEncoding.EncodeToString(raw) + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), nil
}
func (k *CursorKeys) parse(token, actor, workspace string) (cursorPosition, int64, error) {
	invalid := problem(400, "cursor_invalid")
	var p cursorPosition
	if k == nil {
		return p, 0, problem(503, "service_unavailable")
	}
	if len(token) > 2048 {
		return p, 0, invalid
	}
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		return p, 0, invalid
	}
	raw, e := base64.RawURLEncoding.DecodeString(parts[0])
	if e != nil {
		return p, 0, invalid
	}
	sig, e := base64.RawURLEncoding.DecodeString(parts[1])
	if e != nil {
		return p, 0, invalid
	}
	if json.Unmarshal(raw, &p) != nil || len(k.Keys[p.Key]) < 32 {
		return p, 0, invalid
	}
	mac := hmac.New(sha256.New, k.Keys[p.Key])
	mac.Write(raw)
	canonical, _ := json.Marshal(p)
	seq, e := strconv.ParseInt(p.Sequence, 10, 64)
	if !hmac.Equal(sig, mac.Sum(nil)) || !bytes.Equal(raw, canonical) || p.Protocol != 1 || p.Actor != actor || p.Workspace != workspace || !uuidPattern.MatchString(p.Generation) || e != nil || seq < 0 || strconv.FormatInt(seq, 10) != p.Sequence {
		return p, 0, invalid
	}
	return p, seq, nil
}
