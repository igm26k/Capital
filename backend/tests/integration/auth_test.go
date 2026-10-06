package integration

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/platform"
	"accounting/backend/migrate"
	"accounting/backend/migrations"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestAuth(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	cfg, e := pgxpool.ParseConfig(url)
	if e != nil || cfg.ConnConfig.Database != "accounting_test" {
		t.Fatal("requires isolated accounting_test")
	}
	admin, e := pgxpool.NewWithConfig(ctx, cfg)
	if e != nil {
		t.Fatal(e)
	}
	defer admin.Close()
	schema := fmt.Sprintf("auth_test_%d", time.Now().UnixNano())
	ident := pgx.Identifier{schema}.Sanitize()
	if _, e = admin.Exec(ctx, "CREATE SCHEMA "+ident); e != nil {
		t.Fatal(e)
	}
	defer func() {
		clean, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		if _, e := admin.Exec(clean, "DROP SCHEMA "+ident+" CASCADE"); e != nil {
			t.Error("schema cleanup failed")
		}
	}()
	scoped := cfg.Copy()
	scoped.ConnConfig.RuntimeParams["search_path"] = schema
	db, e := pgxpool.NewWithConfig(ctx, scoped)
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	if _, e = migrate.Apply(ctx, db, migrations.Files); e != nil {
		t.Fatal(e)
	}
	var logs bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&logs, nil))
	server := httptest.NewUnstartedServer(nil)
	options := platform.Config{Environment: "test", PublicOrigin: "https://" + server.Listener.Addr().String(), RegistrationEnabled: true, AuthHashConcurrency: 2}
	handler, e := platform.NewAPIHandler(db, options, logger)
	if e != nil {
		t.Fatal(e)
	}
	server.Config.Handler = handler
	server.StartTLS()
	defer server.Close()
	cookieClient := server.Client()
	jar, _ := cookiejar.New(nil)
	cookieClient.Jar = jar
	bearerCopy := *server.Client()
	bearerClient := &bearerCopy
	bearerClient.Jar = nil
	request := func(client *http.Client, method, path string, body any, headers map[string]string, status int) map[string]any {
		t.Helper()
		var data []byte
		if body != nil {
			data, _ = json.Marshal(body)
		}
		r, e := http.NewRequest(method, server.URL+"/api/v1"+path, bytes.NewReader(data))
		if e != nil {
			t.Fatal(e)
		}
		if body != nil {
			r.Header.Set("Content-Type", "application/json")
		}
		for k, v := range headers {
			r.Header.Set(k, v)
		}
		response, e := client.Do(r)
		if e != nil {
			t.Fatal("HTTP request failed")
		}
		defer response.Body.Close()
		payload, e := io.ReadAll(response.Body)
		if e != nil {
			t.Fatal(e)
		}
		var result map[string]any
		if e = json.Unmarshal(payload, &result); e != nil {
			t.Fatal("invalid JSON response")
		}
		if response.StatusCode != status {
			t.Fatalf("%s %s status %d expected %d (code=%v)", method, path, response.StatusCode, status, result["code"])
		}
		if response.Header.Get("Cache-Control") != "no-store" {
			t.Fatal("private response cacheable")
		}
		if status >= 400 {
			if result["request_id"] != response.Header.Get("X-Request-ID") {
				t.Fatal("request_id mismatch")
			}
			return result
		}
		if result["transport"] == "cookie" {
			if _, exists := result["access_token"]; exists {
				t.Fatal("cookie response exposed bearer token")
			}
			if cookies := response.Cookies(); len(cookies) > 0 {
				c := cookies[0]
				if !c.Secure || !c.HttpOnly || c.Path != "/" || c.Domain != "" || c.SameSite != http.SameSiteLaxMode {
					t.Fatal("cookie flags invalid")
				}
			}
		}
		return result
	}
	password := "  exact пароль  "
	registration := map[string]string{"email": " Alice@EXAMPLE.TEST ", "password": password, "device_name": " Browser ", "transport": "cookie", "timezone": "UTC"}
	request(cookieClient, "POST", "/auth/register", registration, nil, 403)
	headers := map[string]string{"Origin": server.URL}
	first := request(cookieClient, "POST", "/auth/register", registration, headers, 201)
	csrf := first["csrf_token"].(string)
	sessionID := first["session"].(map[string]any)["id"].(string)
	userID := first["profile"].(map[string]any)["id"].(string)
	workspaceID := first["workspace"].(map[string]any)["id"].(string)
	request(cookieClient, "POST", "/auth/register", registration, headers, 422)
	var users, workspaces, heads, sessions int
	if e = db.QueryRow(ctx, `SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM workspaces),(SELECT count(*) FROM sync_heads),(SELECT count(*) FROM sessions)`).Scan(&users, &workspaces, &heads, &sessions); e != nil || users != 1 || workspaces != 1 || heads != 1 || sessions != 1 {
		t.Fatal("registration not atomic or duplicate created records")
	}
	var aligned bool
	if e = db.QueryRow(ctx, `SELECT w.sync_generation_id=h.generation_id FROM workspaces w JOIN sync_heads h ON h.workspace_id=w.id`).Scan(&aligned); e != nil || !aligned {
		t.Fatal("generation mismatch")
	}
	restored := request(cookieClient, "GET", "/auth/session", nil, nil, 200)
	if restored["csrf_token"] != csrf || restored["session"].(map[string]any)["id"] != sessionID {
		t.Fatal("GET rotated session or CSRF")
	}
	request(cookieClient, "PUT", "/me", map[string]string{"timezone": "Europe/Nicosia"}, headers, 403)
	writeHeaders := map[string]string{"Origin": server.URL, "X-CSRF-Token": csrf}
	profile := request(cookieClient, "PUT", "/me", map[string]string{"timezone": "Europe/Nicosia"}, writeHeaders, 200)
	if profile["timezone"] != "Europe/Nicosia" {
		t.Fatal("profile update failed")
	}
	request(cookieClient, "POST", "/auth/renew", nil, writeHeaders, 200)
	login := map[string]string{"email": "alice@example.test", "password": strings.TrimSpace(password), "device_name": "phone", "transport": "bearer"}
	request(bearerClient, "POST", "/auth/login", login, nil, 401)
	login["email"] = "unknown@example.test"
	request(bearerClient, "POST", "/auth/login", login, nil, 401)
	login["email"] = "ALICE@example.test"
	login["password"] = password
	second := request(bearerClient, "POST", "/auth/login", login, nil, 200)
	token := second["access_token"].(string)
	bearer := map[string]string{"Authorization": "Bearer " + token}
	request(cookieClient, "GET", "/me", nil, bearer, 400)
	request(bearerClient, "GET", "/me", nil, bearer, 200)
	list := request(bearerClient, "GET", "/sessions?limit=1", nil, bearer, 200)
	if len(list["items"].([]any)) != 1 || list["next_cursor"] == nil {
		t.Fatal("session pagination missing")
	}
	request(bearerClient, "GET", "/sessions?limit=1&cursor="+list["next_cursor"].(string), nil, bearer, 200)
	request(bearerClient, "GET", "/workspaces?cursor="+list["next_cursor"].(string), nil, bearer, 400)
	request(bearerClient, "GET", "/workspaces", nil, bearer, 200)
	request(bearerClient, "DELETE", "/sessions/ffffffff-ffff-4fff-8fff-ffffffffffff", nil, bearer, 404)
	request(bearerClient, "DELETE", "/sessions/"+sessionID, nil, bearer, 200)
	request(bearerClient, "DELETE", "/sessions/"+sessionID, nil, bearer, 200)
	request(cookieClient, "GET", "/me", nil, nil, 401)
	// Only hashes are stored, and the literal password verifies through HTTP.
	var hash string
	var storedToken []byte
	e = db.QueryRow(ctx, `SELECT u.password_hash,s.token_hash FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.id=$1`, second["session"].(map[string]any)["id"]).Scan(&hash, &storedToken)
	sum := sha256.Sum256([]byte(token))
	if e != nil || !strings.HasPrefix(hash, "$argon2id$v=19$") || !bytes.Equal(storedToken, sum[:]) {
		t.Fatal("credential storage invalid")
	}

	limitedOptions := options
	limitedOptions.AuthEmailAttemptsPerMinute = 1
	limitedHandler, e := platform.NewAPIHandler(db, limitedOptions, logger)
	if e != nil {
		t.Fatal(e)
	}
	for _, status := range []int{200, 429} {
		loginBody, _ := json.Marshal(login)
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("POST", "/api/v1/auth/login", bytes.NewReader(loginBody))
		req.Header.Set("Content-Type", "application/json")
		limitedHandler.ServeHTTP(rec, req)
		if rec.Code != status {
			t.Fatalf("auth attempt limit: status %d expected %d", rec.Code, status)
		}
		if status == 429 && rec.Header().Get("Retry-After") != "60" {
			t.Fatal("missing retry information")
		}
	}
	// A known session owned by another user stays inaccessible.
	other := map[string]string{"email": "bob@example.test", "password": "another literal password", "device_name": "other", "transport": "bearer", "timezone": "UTC"}
	foreign := request(bearerClient, "POST", "/auth/register", other, nil, 201)
	foreignID := foreign["session"].(map[string]any)["id"].(string)
	request(bearerClient, "DELETE", "/sessions/"+foreignID, nil, bearer, 404)
	foreignBearer := map[string]string{"Authorization": "Bearer " + foreign["access_token"].(string)}
	request(bearerClient, "GET", "/sessions?cursor="+list["next_cursor"].(string), nil, foreignBearer, 400)
	request(bearerClient, "GET", "/me", nil, foreignBearer, 200)
	request(bearerClient, "POST", "/auth/logout", nil, foreignBearer, 200)
	request(bearerClient, "GET", "/me", nil, foreignBearer, 401)
	expired := request(bearerClient, "POST", "/auth/login", login, nil, 200)
	expiredID := expired["session"].(map[string]any)["id"].(string)
	if _, e = db.Exec(ctx, `UPDATE sessions SET created_at=now()-interval '2 days',expires_at=now()-interval '1 hour' WHERE id=$1`, expiredID); e != nil {
		t.Fatal(e)
	}
	expiredBearer := map[string]string{"Authorization": "Bearer " + expired["access_token"].(string)}
	request(bearerClient, "POST", "/auth/renew", nil, expiredBearer, 401)
	absolute := request(bearerClient, "POST", "/auth/login", login, nil, 200)
	absoluteID := absolute["session"].(map[string]any)["id"].(string)
	if _, e = db.Exec(ctx, `UPDATE sessions SET created_at=now()-interval '31 days',expires_at=now()+interval '1 day' WHERE id=$1`, absoluteID); e != nil {
		t.Fatal(e)
	}
	absoluteBearer := map[string]string{"Authorization": "Bearer " + absolute["access_token"].(string)}
	request(bearerClient, "GET", "/me", nil, absoluteBearer, 401)
	// Registration remains closed in the default configuration.
	disabledOptions := options
	disabledOptions.RegistrationEnabled = false
	disabledHandler, e := platform.NewAPIHandler(db, disabledOptions, logger)
	if e != nil {
		t.Fatal(e)
	}
	recorder := httptest.NewRecorder()
	disabledBody, _ := json.Marshal(map[string]string{"email": "charlie@example.test", "password": "disabled registration password", "transport": "bearer", "device_name": "disabled", "timezone": "UTC"})
	disabledRequest := httptest.NewRequest("POST", "/api/v1/auth/register", bytes.NewReader(disabledBody))
	disabledHandler.ServeHTTP(recorder, disabledRequest)
	var afterDisabled int
	if e = db.QueryRow(ctx, "SELECT count(*) FROM users").Scan(&afterDisabled); e != nil || afterDisabled != 2 {
		t.Fatal("disabled registration created user")
	}
	if recorder.Code != 403 {
		t.Fatal("default registration open")
	}
	// Revoked membership disappears from list and fails the transactional membership guard.
	if _, e = db.Exec(ctx, `UPDATE memberships SET revoked_at=now() WHERE workspace_id=$1 AND user_id=$2`, workspaceID, userID); e != nil {
		t.Fatal(e)
	}
	guard := &identity.Service{DB: db}
	guardErr := guard.WithSession(ctx, token, false, func(tx pgx.Tx, p identity.Principal) error {
		return identity.RequireMembership(ctx, tx, p.Profile.ID, workspaceID)
	})
	var guardFailure *identity.Error
	if !errors.As(guardErr, &guardFailure) || guardFailure.Status != 404 {
		t.Fatal("revoked membership guard allowed access")
	}
	empty := request(bearerClient, "GET", "/workspaces", nil, bearer, 200)
	if len(empty["items"].([]any)) != 0 {
		t.Fatal("revoked membership exposed")
	}
	if _, e = db.Exec(ctx, `UPDATE users SET disabled_at=now() WHERE id=$1`, userID); e != nil {
		t.Fatal(e)
	}
	request(bearerClient, "GET", "/me", nil, bearer, 401)
	login["email"] = "alice@example.test"
	request(bearerClient, "POST", "/auth/login", login, nil, 401)
	if strings.Contains(logs.String(), password) || strings.Contains(logs.String(), token) || strings.Contains(logs.String(), csrf) {
		t.Fatal("credentials leaked into logs")
	}
}
