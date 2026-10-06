package identity

import (
	"context"
	"strings"
	"testing"
)

func TestPasswordLiteralAndRandomSalt(t *testing.T) {
	h, e := NewHasher(1)
	if e != nil {
		t.Fatal(e)
	}
	password := "  literal пароль  "
	first, e := h.Hash(context.Background(), password)
	if e != nil {
		t.Fatal(e)
	}
	second, e := h.Hash(context.Background(), password)
	if e != nil {
		t.Fatal(e)
	}
	if first == second || !strings.HasPrefix(first, "$argon2id$v=19$m=65536,t=3,p=4$") {
		t.Fatal("salt or parameters invalid")
	}
	ok, e := h.Verify(context.Background(), first, password)
	if !ok || e != nil {
		t.Fatal("literal password rejected")
	}
	ok, e = h.Verify(context.Background(), first, strings.TrimSpace(password))
	if ok || e != nil {
		t.Fatal("password was normalized")
	}
	ok, e = h.Verify(context.Background(), "", password)
	if ok || e != nil {
		t.Fatal("unknown email dummy verification failed")
	}
}
func TestHashCapacityIsBounded(t *testing.T) {
	h, e := NewHasher(1)
	if e != nil {
		t.Fatal(e)
	}
	h.slots <- struct{}{}
	defer func() { <-h.slots }()
	if _, e = h.Hash(context.Background(), "secret"); e == nil {
		t.Fatal("hash capacity not bounded")
	}
}

func BenchmarkArgon2id(b *testing.B) {
	h, e := NewHasher(1)
	if e != nil {
		b.Fatal(e)
	}
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if _, e = h.Hash(context.Background(), "benchmark literal password"); e != nil {
			b.Fatal(e)
		}
	}
}
