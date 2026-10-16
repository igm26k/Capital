package integration

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/platform"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"github.com/jackc/pgx/v5"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
)

type capturedEmail struct{ recipient, body string }
type captureEmails struct {
	mu    sync.Mutex
	items []capturedEmail
	fail  bool
}

func (c *captureEmails) Send(_ context.Context, to, body string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.items = append(c.items, capturedEmail{to, body})
	if c.fail {
		return errors.New("synthetic private transport diagnostic")
	}
	return nil
}
func (c *captureEmails) code(email string) string {
	c.mu.Lock()
	defer c.mu.Unlock()
	for i := len(c.items) - 1; i >= 0; i-- {
		if c.items[i].recipient == email {
			for _, word := range strings.Fields(c.items[i].body) {
				if len(word) == 43 {
					return word
				}
			}
		}
	}
	return ""
}
func TestEmailAuth(t *testing.T) {
	f := newAccessFixture(t)
	key := bytes.Repeat([]byte{83}, 32)
	h, err := platform.NewAPIHandler(f.db, platform.Config{Environment: "test", PublicOrigin: "https://advanced.example.test", AuthHashConcurrency: 2, RegistrationEnabled: true, EmailVerification: true, EmailKey: key, CursorKeys: f.keys}, slog.New(slog.NewTextHandler(f.logs, nil)))
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewTLSServer(h)
	defer server.Close()
	client := server.Client()
	call := func(path string, body any, status int) map[string]any {
		t.Helper()
		raw, _ := json.Marshal(body)
		req, _ := http.NewRequest("POST", server.URL+"/api/v1"+path, bytes.NewReader(raw))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Origin", "https://advanced.example.test")
		res, e := client.Do(req)
		if e != nil {
			t.Fatal(e)
		}
		defer res.Body.Close()
		data, _ := io.ReadAll(res.Body)
		if res.StatusCode != status {
			t.Fatalf("%s status=%d expected=%d", path, res.StatusCode, status)
		}
		var result map[string]any
		schema := "Error"
		if status < 400 {
			schema = "EmailChallenge"
			if path == "/auth/registration/confirm" {
				schema = "AuthResult"
			} else if path == "/auth/recovery/confirm" {
				schema = "PasswordChanged"
			}
		}
		f.observed = append(f.observed, accessObserved{status, schema, data, "POST", path})
		if json.Unmarshal(data, &result) != nil {
			t.Fatal("invalid JSON")
		}
		return result
	}
	email := "verified@example.test"
	password := "synthetic verification password"
	register := func(address string) any {
		return map[string]any{"email": address, "password": password, "device_name": "verification test", "transport": "bearer", "timezone": "UTC"}
	}
	before := call("/auth/register", register(email), 202)
	existing := call("/auth/register", register(f.a.Profile.Email), 202)
	if before["status"] != existing["status"] || len(before) != 1 || len(existing) != 1 {
		t.Fatal("registration enumerates email")
	}
	var users int
	f.db.QueryRow(f.ctx, `SELECT count(*) FROM users WHERE email_normalized=$1`, email).Scan(&users)
	if users != 0 {
		t.Fatal("unverified profile created")
	}
	// Cooldown returns the same response and does not replace a live proof.
	call("/auth/register", register(email), 202)
	sender := &captureEmails{}
	for i := 0; i < 3; i++ {
		_, err = f.auth.DispatchEmail(f.ctx, key, sender)
		if err != nil {
			t.Fatal(err)
		}
	}
	code := sender.code(email)
	if code == "" {
		t.Fatal("verification mail not dispatched")
	}
	var clearBodies int
	f.db.QueryRow(f.ctx, `SELECT count(*) FROM auth_mail WHERE sent_at IS NOT NULL AND encrypted_body IS NOT NULL`).Scan(&clearBodies)
	if clearBodies != 0 {
		t.Fatal("sent token retained")
	}
	confirm := map[string]any{"email": email, "code": code, "password": password, "device_name": "confirmed", "transport": "bearer"}
	confirm["password"] = "incorrect original password"
	call("/auth/registration/confirm", confirm, 422)
	confirm["password"] = password
	call("/auth/registration/confirm", confirm, 201)
	call("/auth/registration/confirm", confirm, 422)
	if _, err = f.auth.Login(f.ctx, email, password, "second"); err != nil {
		t.Fatal(err)
	}
	unknown := "unknown-reset@example.test"
	a := call("/auth/recovery/request", map[string]any{"email": email}, 202)
	b := call("/auth/recovery/request", map[string]any{"email": unknown}, 202)
	if a["status"] != b["status"] || len(a) != 1 || len(b) != 1 {
		t.Fatal("recovery enumerates email")
	}
	for i := 0; i < 3; i++ {
		if _, err = f.auth.DispatchEmail(f.ctx, key, sender); err != nil {
			t.Fatal(err)
		}
	}
	resetCode := sender.code(email)
	if resetCode == "" || resetCode == code {
		t.Fatal("purpose proofs mixed")
	}
	reset := map[string]any{"email": email, "code": code, "password": "new synthetic verified password"}
	call("/auth/recovery/confirm", reset, 422)
	reset["code"] = resetCode
	call("/auth/recovery/confirm", reset, 200)
	call("/auth/recovery/confirm", reset, 422)
	if _, err = f.auth.Login(f.ctx, email, password, "old"); err == nil {
		t.Fatal("old password accepted")
	}
	if _, err = f.auth.Login(f.ctx, email, "new synthetic verified password", "new"); err != nil {
		t.Fatal(err)
	}
	var activeOld int
	f.db.QueryRow(f.ctx, `SELECT count(*) FROM sessions WHERE user_id=(SELECT id FROM users WHERE email_normalized=$1) AND device_name IN ('confirmed','second') AND revoked_at IS NULL`, email).Scan(&activeOld)
	if activeOld != 0 {
		t.Fatal("reset did not revoke all old sessions")
	}
	reset["email"] = unknown
	reset["code"] = sender.code(unknown)
	call("/auth/recovery/confirm", reset, 422)
	// Expiry is enforced even for a correct original password/code.
	expiry := "expires@example.test"
	call("/auth/register", register(expiry), 202)
	if _, err = f.auth.DispatchEmail(f.ctx, key, sender); err != nil {
		t.Fatal(err)
	}
	_, err = f.db.Exec(f.ctx, `UPDATE auth_challenges SET created_at=clock_timestamp()-interval '2 hours',expires_at=clock_timestamp()-interval '1 hour' WHERE email=$1`, expiry)
	if err != nil {
		t.Fatal(err)
	}
	confirm["email"] = expiry
	confirm["code"] = sender.code(expiry)
	call("/auth/registration/confirm", confirm, 422)
	// Storage failure must preserve a valid code and remain retryable, rather than claim invalid proof.
	transient := "transient-email@example.test"
	call("/auth/register", register(transient), 202)
	if _, err = f.auth.DispatchEmail(f.ctx, key, sender); err != nil {
		t.Fatal(err)
	}
	_, err = f.db.Exec(f.ctx, `CREATE FUNCTION fail_email_registration() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.email_normalized='transient-email@example.test' THEN RAISE EXCEPTION 'synthetic storage fault'; END IF; RETURN NEW; END $$; CREATE TRIGGER fail_email_registration BEFORE INSERT ON users FOR EACH ROW EXECUTE FUNCTION fail_email_registration()`)
	if err != nil {
		t.Fatal(err)
	}
	confirm["email"] = transient
	confirm["code"] = sender.code(transient)
	call("/auth/registration/confirm", confirm, 503)
	if _, err = f.db.Exec(f.ctx, `DROP TRIGGER fail_email_registration ON users; DROP FUNCTION fail_email_registration()`); err != nil {
		t.Fatal(err)
	}
	call("/auth/registration/confirm", confirm, 201)
	if path := os.Getenv("EMAIL_RESPONSE_ARTIFACT"); path != "" {
		f.saveTo(path)
	}
	if strings.Contains(f.logs.text(), code) || strings.Contains(f.logs.text(), password) || strings.Contains(f.logs.text(), email) {
		t.Fatal("auth proof leaked into logs")
	}
}

func TestEmailProofConcurrencyAndRetry(t *testing.T) {
	f := newAccessFixture(t)
	key := bytes.Repeat([]byte{42}, 32)
	email := "email-race@example.test"
	password := "synthetic concurrent password"
	sender := &captureEmails{fail: true}
	if err := f.auth.StartEmailAuth(f.ctx, key, email, "registration", password, "UTC"); err != nil {
		t.Fatal(err)
	}
	processed, err := f.auth.DispatchEmail(f.ctx, key, sender)
	if !processed || err == nil || strings.Contains(err.Error(), "diagnostic") {
		t.Fatal("SMTP failure not bounded/redacted")
	}
	firstCode := sender.code(email)
	if firstCode == "" {
		t.Fatal("SMTP send not attempted")
	}
	_, err = f.db.Exec(f.ctx, `UPDATE auth_mail SET available_at=clock_timestamp()`)
	if err != nil {
		t.Fatal(err)
	}
	sender.fail = false
	if _, err = f.auth.DispatchEmail(f.ctx, key, sender); err != nil {
		t.Fatal(err)
	}
	if sender.code(email) != firstCode {
		t.Fatal("retry changed original email proof")
	}
	results := make(chan error, 2)
	for i := 0; i < 2; i++ {
		go func() {
			_, err := f.auth.ConfirmRegistration(f.ctx, email, firstCode, password, "concurrent")
			results <- err
		}()
	}
	successes := 0
	for i := 0; i < 2; i++ {
		if err := <-results; err == nil {
			successes++
		} else if e, ok := err.(*identity.Error); !ok || e.Code != "confirmation_invalid" {
			t.Fatal("concurrent proof failure", err)
		}
	}
	if successes != 1 {
		t.Fatal("email code applied more than once", successes)
	}
	var workspaces int
	f.db.QueryRow(f.ctx, `SELECT count(*) FROM workspaces w JOIN users u ON w.owner_user_id=u.id WHERE u.email_normalized=$1`, email).Scan(&workspaces)
	if workspaces != 1 {
		t.Fatal("registration created duplicate workspace")
	}
	if err = f.auth.StartEmailAuth(f.ctx, key, email, "recovery", "", ""); err != nil {
		t.Fatal(err)
	}
	session, err := f.auth.Login(f.ctx, email, password, "delete")
	if err != nil {
		t.Fatal(err)
	}
	err = f.auth.WithSession(f.ctx, session.Credential, true, func(tx pgx.Tx, p identity.Principal) error { return f.auth.DeleteUserData(f.ctx, tx, p, password) })
	if err != nil {
		t.Fatal(err)
	}
	var private int
	f.db.QueryRow(f.ctx, `SELECT count(*) FROM auth_challenges WHERE email=$1`, email).Scan(&private)
	if private != 0 {
		t.Fatal("deleted user's auth proofs retained")
	}
}
