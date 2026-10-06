package httpapi

import (
	"accounting/backend/internal/identity"
	"errors"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestStrictAuthJSON(t *testing.T) {
	for _, c := range []struct {
		body   string
		status int
	}{
		{`{"password":"ok"}`, 0},
		{`{"password":"first","password":"last"}`, 400},
		{`{"password":"ok","other":"secret"}`, 422},
		{`{"Password":"ok"}`, 422},
		{`{"password":null}`, 422},
		{`{"password":"\ud800"}`, 400},
		{`{"password":"\udc00"}`, 400},
		{`{"password":"\ud83d\ude00"}`, 0},
		{`{"password":"\\ud800"}`, 0},
		{`{"password":"ok"} {}`, 400},
		{"{\"password\":\"\xff\"}", 400},
	} {
		t.Run(c.body, func(t *testing.T) {
			r := httptest.NewRequest("POST", "/", strings.NewReader(c.body))
			r.Header.Set("Content-Type", "application/json")
			_, e := fields(httptest.NewRecorder(), r, "password")
			if c.status == 0 {
				if e != nil {
					t.Fatal(e)
				}
			} else {
				var err *identity.Error
				if !errors.As(e, &err) || err.Status != c.status {
					t.Fatalf("status=%d error=%v", c.status, e)
				}
			}
		})
	}
}
