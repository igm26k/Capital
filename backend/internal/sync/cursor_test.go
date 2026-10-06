package sync

import (
	"bytes"
	"testing"
)

func TestCursorIntegrityScopeAndRotation(t *testing.T) {
	actor := "10000000-0000-4000-8000-000000000001"
	workspace := "10000000-0000-4000-8000-000000000002"
	generation := "10000000-0000-4000-8000-000000000003"
	old, e := NewCursorKeys("v1", map[string][]byte{"v1": bytes.Repeat([]byte{1}, 32)})
	if e != nil {
		t.Fatal(e)
	}
	token, e := old.Sign(actor, workspace, generation, 9223372036854775807)
	if e != nil {
		t.Fatal(e)
	}
	rotated, _ := NewCursorKeys("v2", map[string][]byte{"v1": bytes.Repeat([]byte{1}, 32), "v2": bytes.Repeat([]byte{2}, 32)})
	p, n, e := rotated.parse(token, actor, workspace)
	if e != nil || n != 9223372036854775807 || p.Generation != generation {
		t.Fatal("rotation lost old position", e)
	}
	revoked, _ := NewCursorKeys("v2", map[string][]byte{"v2": bytes.Repeat([]byte{2}, 32)})
	if _, _, e = revoked.parse(token, actor, workspace); e == nil {
		t.Fatal("retired key accepted")
	}
	for _, bad := range []string{"", token + ".", token[:len(token)-4] + "AAAA"} {
		if _, _, e = old.parse(bad, actor, workspace); e == nil {
			t.Fatal("tampered cursor accepted")
		}
	}
	if _, _, e = old.parse(token, workspace, workspace); e == nil {
		t.Fatal("wrong actor accepted")
	}
	if _, _, e = old.parse(token, actor, actor); e == nil {
		t.Fatal("wrong workspace accepted")
	}
	if _, e = NewCursorKeys("v1", map[string][]byte{"v1": []byte("weak")}); e == nil {
		t.Fatal("weak key accepted")
	}
	if _, e = old.Sign(actor, workspace, generation, -1); e == nil {
		t.Fatal("negative position accepted")
	}
}
