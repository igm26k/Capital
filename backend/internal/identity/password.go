package identity

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"golang.org/x/crypto/argon2"
	"strings"
)

// Hasher limits memory consumption: each active Argon2id operation uses 64 MiB.
type Hasher struct {
	slots chan struct{}
	dummy string
}

func NewHasher(concurrency int) (*Hasher, error) {
	if concurrency < 1 || concurrency > 8 {
		return nil, errors.New("invalid hash concurrency")
	}
	h := &Hasher{slots: make(chan struct{}, concurrency)}
	var err error
	h.dummy, err = h.Hash(context.Background(), "unknown account dummy password")
	return h, err
}
func (h *Hasher) acquire(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	select {
	case h.slots <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	default:
		return errors.New("password hashing capacity exceeded")
	}
}
func (h *Hasher) Hash(ctx context.Context, password string) (string, error) {
	if err := h.acquire(ctx); err != nil {
		return "", err
	}
	defer func() { <-h.slots }()
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", errors.New("password salt unavailable")
	}
	key := argon2.IDKey([]byte(password), salt, 3, 65536, 4, 32)
	return "$argon2id$v=19$m=65536,t=3,p=4$" + base64.RawStdEncoding.EncodeToString(salt) + "$" + base64.RawStdEncoding.EncodeToString(key), nil
}
func (h *Hasher) Verify(ctx context.Context, encoded, password string) (bool, error) {
	if err := h.acquire(ctx); err != nil {
		return false, err
	}
	defer func() { <-h.slots }()
	valid := true
	if encoded == "" {
		encoded = h.dummy
		valid = false
	}
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" || parts[2] != "v=19" || parts[3] != "m=65536,t=3,p=4" {
		return false, errors.New("unsupported password hash")
	}
	salt, e1 := base64.RawStdEncoding.DecodeString(parts[4])
	expected, e2 := base64.RawStdEncoding.DecodeString(parts[5])
	if e1 != nil || e2 != nil || len(salt) != 16 || len(expected) != 32 {
		return false, errors.New("invalid password hash")
	}
	actual := argon2.IDKey([]byte(password), salt, 3, 65536, 4, 32)
	return subtle.ConstantTimeCompare(actual, expected) == 1 && valid, nil
}
