package sync

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"os"
	"testing"
)

func TestCanonicalIndependentFixtures(t *testing.T) {
	data, e := os.ReadFile("../../../contracts/fixtures/sync-examples.json")
	if e != nil {
		t.Fatal(e)
	}
	var fixtures struct {
		Cases []struct {
			ID, Method, Path, Left, Right string
			Same                          bool   `json:"expected_same"`
			LeftGeneration                string `json:"left_generation"`
			RightGeneration               string `json:"right_generation"`
		} `json:"canonical_cases"`
	}
	if e = json.Unmarshal(data, &fixtures); e != nil {
		t.Fatal(e)
	}
	for _, c := range fixtures.Cases {
		t.Run(c.ID, func(t *testing.T) {
			a, e := RequestHash(c.Method, c.Path, c.LeftGeneration, []byte(c.Left))
			if e != nil {
				t.Fatal(e)
			}
			b, e := RequestHash(c.Method, c.Path, c.RightGeneration, []byte(c.Right))
			if e != nil {
				t.Fatal(e)
			}
			if (a == b) != c.Same {
				t.Fatal("independent hash fixture mismatch")
			}
		})
	}
}
func TestCanonicalExactUTF8(t *testing.T) {
	body := []byte("{\"z\":\"<>&\u2028\u2029\",\"a\":\"\\u0001\\n\\t\\\\u2028\"}")
	expected := []byte("{\"a\":\"\\u0001\\n\\t\\\\u2028\",\"z\":\"<>&\u2028\u2029\"}")
	actual, e := CanonicalJSON(body)
	if e != nil || !bytes.Equal(actual, expected) {
		t.Fatalf("unexpected canonical encoding: %s", actual)
	}
	generation := "00000000-0000-4000-8000-000000000900"
	hash, e := RequestHash("POST", "/api/v1/workspaces/w/tags", generation, body)
	if e != nil {
		t.Fatal(e)
	}
	independent := sha256.Sum256(append([]byte("POST\n/api/v1/workspaces/w/tags\n"+generation+"\n"), expected...))
	if hash != independent {
		t.Fatal("hash framing mismatch")
	}
}
func TestInvalidCanonicalCommands(t *testing.T) {
	for _, s := range []string{`{"x":1,"x":2}`, `{"x":1e2}`, `{"x":1.0}`, `{"x":"\ud800"}`, `{"x":"\udc00"}`, `{"x":{"a":1,"a":2}}`, `[]`, `{} {}`, "{\"x\":\"\xff\"}"} {
		if _, e := CanonicalJSON([]byte(s)); e == nil {
			t.Fatalf("accepted invalid JSON: %q", s)
		}
	}
}
