package platform

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/platform/httpapi"
	commands "accounting/backend/internal/sync"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"github.com/jackc/pgx/v5/pgxpool"
	"log/slog"
	"net/http"
	"time"
)

type Pinger interface{ Ping(context.Context) error }

type APIError struct {
	RequestID         string `json:"request_id"`
	Code              string `json:"code"`
	Message           string `json:"message"`
	FieldErrors       []any  `json:"field_errors"`
	CurrentVersions   []any  `json:"current_versions"`
	RetryAfterSeconds *int   `json:"retry_after_seconds"`
}

func requestID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic("request identifier unavailable")
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	s := hex.EncodeToString(b[:])
	return s[:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:]
}

type requestIDKey struct{}

func writeJSON(w http.ResponseWriter, status int, value any) {
	data, err := json.Marshal(value)
	if err != nil {
		panic("response encoding failed")
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(append(data, '\n'))
}

func writeError(w http.ResponseWriter, r *http.Request, status int, code, message string) {
	id, _ := r.Context().Value(requestIDKey{}).(string)
	writeJSON(w, status, APIError{RequestID: id, Code: code, Message: message, FieldErrors: []any{}, CurrentVersions: []any{}})
}

func Handler(database Pinger, logger *slog.Logger) http.Handler {
	return handler(database, logger, nil, httpapi.AuthOptions{})
}
func handler(database Pinger, logger *slog.Logger, auth *identity.Service, options httpapi.AuthOptions) http.Handler {
	mux := http.NewServeMux()
	if auth != nil {
		httpapi.MountAuth(mux, auth, options)
		runner := &commands.Runner{Identity: auth}
		httpapi.MountActions(mux, runner)
		httpapi.MountLedger(mux, runner, options)
		httpapi.MountPull(mux, &commands.PullReader{Identity: auth, Keys: options.CursorKeys})
		httpapi.MountSnapshots(mux, &commands.SnapshotService{Identity: auth, Keys: options.CursorKeys}, options)
	}
	mux.HandleFunc("GET /health/live", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("GET /health/ready", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), time.Second)
		defer cancel()
		if database == nil || database.Ping(ctx) != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "not_ready"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, r, http.StatusNotFound, "not_found", "Ресурс не найден.")
	})
	return protect(mux, logger)
}

type recorder struct {
	http.ResponseWriter
	status int
}

func (w *recorder) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}
func (w *recorder) Write(data []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(data)
}
func (w *recorder) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func protect(next http.Handler, logger *slog.Logger) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := requestID()
		r = r.WithContext(context.WithValue(r.Context(), requestIDKey{}, id))
		w.Header().Set("X-Request-ID", id)
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		wrapped := &recorder{ResponseWriter: w}
		defer func() {
			if recovered := recover(); recovered != nil {
				logger.Error("request_failed", "request_id", id, "code", "internal_error")
				if wrapped.status != 0 {
					panic(http.ErrAbortHandler)
				}
				writeError(wrapped, r, http.StatusInternalServerError, "internal_error", "Не удалось выполнить запрос.")
			}
			status := wrapped.status
			if status == 0 {
				status = http.StatusOK
			}
			// Never log user-supplied paths, queries, credentials or request bodies.
			logger.Info("request_completed", "request_id", id, "method", r.Method, "status", status)
		}()
		next.ServeHTTP(wrapped, r)
	})
}

func NewAPIHandler(database *pgxpool.Pool, cfg Config, logger *slog.Logger) (http.Handler, error) {
	auth, err := identity.New(database, cfg.AuthHashConcurrency)
	if err != nil {
		return nil, err
	}
	return handler(database, logger, auth, httpapi.AuthOptions{CursorKeys: cfg.CursorKeys, Origin: cfg.PublicOrigin, Registration: cfg.RegistrationEnabled, IPAttemptsPerMinute: cfg.AuthIPAttemptsPerMinute, EmailAttemptsPerMinute: cfg.AuthEmailAttemptsPerMinute}), nil
}
