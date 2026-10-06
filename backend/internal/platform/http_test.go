package platform

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
)

type failingDatabase struct {
	calls           int
	deadlinePresent bool
}

func (d *failingDatabase) Ping(ctx context.Context) error {
	d.calls++
	_, d.deadlinePresent = ctx.Deadline()
	return errors.New("private-database-password financial-text")
}

func TestLivenessAndFailedReadinessAreDifferent(t *testing.T) {
	var logs bytes.Buffer
	database := &failingDatabase{}
	handler := Handler(database, slog.New(slog.NewJSONHandler(&logs, nil)))
	live := httptest.NewRecorder()
	handler.ServeHTTP(live, httptest.NewRequest(http.MethodGet, "/health/live", nil))
	if live.Code != http.StatusOK || database.calls != 0 {
		t.Fatal("liveness requires database")
	}
	ready := httptest.NewRecorder()
	handler.ServeHTTP(ready, httptest.NewRequest(http.MethodGet, "/health/ready", nil))
	if ready.Code != http.StatusServiceUnavailable || database.calls != 1 || !database.deadlinePresent {
		t.Fatal("readiness failed to bound or report database failure")
	}
	if strings.Contains(logs.String()+ready.Body.String(), "private-database-password") {
		t.Fatal("database error exposed")
	}
}

func TestUnknownRouteReturnsPrivateContractError(t *testing.T) {
	var logs bytes.Buffer
	handler := Handler(nil, slog.New(slog.NewJSONHandler(&logs, nil)))
	response := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/private-financial-text?token=private-token", strings.NewReader("private-body"))
	request.Header.Set("Authorization", "Bearer private-credential")
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusNotFound {
		t.Fatal(response.Code)
	}
	var body APIError
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	uuid := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	if body.Code != "not_found" || !uuid.MatchString(body.RequestID) || body.RequestID != response.Header().Get("X-Request-ID") || body.FieldErrors == nil || body.CurrentVersions == nil {
		t.Fatal("invalid API error envelope")
	}
	if response.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("private response can be cached")
	}
	for _, secret := range []string{"private-financial-text", "private-token", "private-body", "private-credential"} {
		if strings.Contains(logs.String()+response.Body.String(), secret) {
			t.Fatalf("request data exposed: %s", secret)
		}
	}
}

func TestPanicRecoveryRedactsValue(t *testing.T) {
	var logs bytes.Buffer
	handler := protect(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { panic("private-financial-text") }), slog.New(slog.NewJSONHandler(&logs, nil)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/", nil))
	if response.Code != http.StatusInternalServerError {
		t.Fatal(response.Code)
	}
	if strings.Contains(logs.String()+response.Body.String(), "private-financial-text") {
		t.Fatal("panic content exposed")
	}
	var body APIError
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil || body.Code != "internal_error" {
		t.Fatal("invalid recovered error", err)
	}
}

func TestPanicAfterResponseBeginsAbortsInsteadOfAppendingSuccess(t *testing.T) {
	var logs bytes.Buffer
	handler := protect(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		panic("private-financial-text")
	}), slog.New(slog.NewJSONHandler(&logs, nil)))
	defer func() {
		if recovered := recover(); recovered != http.ErrAbortHandler {
			t.Fatal("partially written response was not aborted", recovered)
		}
		if strings.Contains(logs.String(), "private-financial-text") || strings.Contains(logs.String(), "request_completed") {
			t.Fatal("failed partial response logged as success or exposed content")
		}
	}()
	handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/", nil))
}
