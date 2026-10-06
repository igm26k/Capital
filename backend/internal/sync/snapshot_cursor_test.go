package sync

import (
	"bytes"
	"testing"
)

func TestSnapshotPageTokenScopeAndPosition(t *testing.T) {
	actor := "10000000-0000-4000-8000-000000000001"
	s := Snapshot{ID: "10000000-0000-4000-8000-000000000002", Workspace: "10000000-0000-4000-8000-000000000003", Generation: "10000000-0000-4000-8000-000000000004", Base: "17", Count: 150}
	k, _ := NewCursorKeys("v1", map[string][]byte{"v1": bytes.Repeat([]byte{1}, 32)})
	token, e := k.pageToken(actor, s, 100)
	if e != nil {
		t.Fatal(e)
	}
	if pos, e := k.pagePosition(token, actor, s); e != nil || pos != 100 {
		t.Fatal("page position lost", e)
	}
	for _, position := range []int{-100, 1, 150, 200} {
		bad, _ := k.pageToken(actor, s, position)
		if _, e = k.pagePosition(bad, actor, s); e == nil {
			t.Fatal("invalid page position accepted")
		}
	}
	for _, change := range []func(*Snapshot){func(s *Snapshot) { s.ID = s.Workspace }, func(s *Snapshot) { s.Workspace = s.ID }, func(s *Snapshot) { s.Generation = s.ID }, func(s *Snapshot) { s.Base = "18" }} {
		other := s
		change(&other)
		if _, e = k.pagePosition(token, actor, other); e == nil {
			t.Fatal("scope accepted")
		}
	}
	if _, e = k.pagePosition(token, s.ID, s); e == nil {
		t.Fatal("actor accepted")
	}
	cursor, _ := k.Sign(actor, s.Workspace, s.Generation, 17)
	if _, e = k.pagePosition(cursor, actor, s); e == nil {
		t.Fatal("pull cursor accepted as page token")
	}
	if _, _, e = k.parse(token, actor, s.Workspace); e == nil {
		t.Fatal("page token accepted as pull cursor")
	}
	s.Count = 0
	empty, _ := k.pageToken(actor, s, 0)
	if pos, e := k.pagePosition(empty, actor, s); e != nil || pos != 0 {
		t.Fatal("empty snapshot token rejected")
	}
}
