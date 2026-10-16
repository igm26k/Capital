package identity

import (
	"bytes"
	"testing"
)

func TestQueuedEmailEncryption(t *testing.T) {
	key := bytes.Repeat([]byte{21}, 32)
	secret := "private-code-body"
	encrypted, err := EncryptEmail(key, "challenge-one", secret)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(encrypted, []byte(secret)) {
		t.Fatal("queued email plaintext")
	}
	plain, err := DecryptEmail(key, "challenge-one", encrypted)
	if err != nil || plain != secret {
		t.Fatal("email decryption failed")
	}
	if _, err = DecryptEmail(key, "challenge-two", encrypted); err == nil {
		t.Fatal("email moved to another challenge")
	}
	encrypted[len(encrypted)-1] ^= 1
	if _, err = DecryptEmail(key, "challenge-one", encrypted); err == nil {
		t.Fatal("tampered email accepted")
	}
	if _, err = EncryptEmail(key[:16], "id", secret); err == nil {
		t.Fatal("weak email key accepted")
	}
}
