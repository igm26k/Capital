package integration

import (
	"accounting/backend/internal/platform"
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"
)

// A test-only host for real browser acceptance; no code-reading endpoint is linked into API binaries.
func TestEmailBrowser(t *testing.T) {
	if os.Getenv("EMAIL_BROWSER_TEST") != "1" {
		t.Skip("dedicated browser runner only")
	}
	f := newAccessFixture(t)
	key := bytes.Repeat([]byte{93}, 32)
	api, err := platform.NewAPIHandler(f.db, platform.Config{Environment: "test", PublicOrigin: "https://localhost:8446", AuthHashConcurrency: 2, RegistrationEnabled: true, EmailVerification: true, EmailKey: key, CursorKeys: f.keys}, slog.New(slog.NewTextHandler(f.logs, nil)))
	if err != nil {
		t.Fatal(err)
	}
	sender := &captureEmails{}
	ctx, cancel := context.WithCancel(f.ctx)
	defer cancel()
	go func() {
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				_, _ = f.auth.DispatchEmail(ctx, key, sender)
			}
		}
	}()
	complete := make(chan struct{}, 1)
	mux := http.NewServeMux()
	mux.Handle("/api/", api)
	mux.Handle("/health/", api)
	mux.Handle("/", http.FileServer(http.Dir("/web-dist")))
	mux.HandleFunc("GET /test/code", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		json.NewEncoder(w).Encode(map[string]string{"code": sender.code(r.URL.Query().Get("email"))})
	})
	mux.HandleFunc("POST /test/complete", func(w http.ResponseWriter, r *http.Request) {
		select {
		case complete <- struct{}{}:
		default:
		}
		w.WriteHeader(204)
	})
	listener, err := net.Listen("tcp", ":8446")
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewUnstartedServer(mux)
	server.Listener.Close()
	server.Listener = listener
	server.StartTLS()
	defer server.Close()
	select {
	case <-complete:
	case <-ctx.Done():
		t.Fatal("browser acceptance did not finish")
	}
}
