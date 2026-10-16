package httpapi

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"io"
	"mime"
	"net/http"
	"strings"
)

func MountActions(mux *http.ServeMux, runner *commands.Runner) {
	mux.HandleFunc("GET /api/v1/workspaces/{workspace_id}/actions/{action_id}", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.RawQuery != "" {
			reject(w, fail(400, "bad_request"))
			return
		}
		credential, _, e := credential(r)
		if e != nil {
			reject(w, e)
			return
		}
		result, e := runner.Outcome(r.Context(), credential, r.PathValue("workspace_id"), r.PathValue("action_id"))
		if e != nil {
			reject(w, e)
			return
		}
		respond(w, 200, result)
	})
}

// MutationHandler is used by domain routes after supplying a schema validator and a transactional command.
// Domain routes supply their validator and transactional implementation.
func MutationHandler(runner *commands.Runner, options AuthOptions, validate func([]byte) error, command func(context.Context, []byte) commands.Command) http.HandlerFunc {
	if command == nil {
		return mutationHandler(runner, options, validate, nil)
	}
	return mutationHandler(runner, options, validate, func(r *http.Request, body []byte) commands.Command { return command(r.Context(), body) })
}

func mutationHandler(runner *commands.Runner, options AuthOptions, validate func([]byte) error, command func(*http.Request, []byte) commands.Command) http.HandlerFunc {
	return commandHTTPHandler(options, validate, func(r *http.Request, request commands.Request) (commands.Result, error) {
		if command == nil {
			return commands.Result{}, fail(500, "internal_error")
		}
		return runner.Execute(r.Context(), request, command(r, request.Body))
	})
}

// Shared transport validation keeps configuration writes subject to the same CSRF and wire guards.
func commandHTTPHandler(options AuthOptions, validate func([]byte) error, execute func(*http.Request, commands.Request) (commands.Result, error)) http.HandlerFunc {
	return commandHTTPHandlerLimit(options, validate, execute, 262144)
}
func commandHTTPHandlerLimit(options AuthOptions, validate func([]byte) error, execute func(*http.Request, commands.Request) (commands.Result, error), limit int64) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		credential, transport, e := credential(r)
		if e != nil {
			reject(w, e)
			return
		}
		if r.URL.RawQuery != "" || len(r.Header.Values("Idempotency-Key")) != 1 || len(r.Header.Values("X-Sync-Generation")) != 1 {
			reject(w, fail(400, "bad_request"))
			return
		}
		media, _, e := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if e != nil || media != "application/json" {
			reject(w, fail(400, "bad_request"))
			return
		}
		body, e := io.ReadAll(http.MaxBytesReader(w, r.Body, limit))
		if e != nil {
			reject(w, fail(413, "bad_request"))
			return
		}
		if _, e = commands.CanonicalJSONLimit(body, limit); e != nil {
			reject(w, fail(400, "bad_request"))
			return
		}
		if validate == nil || execute == nil {
			reject(w, fail(500, "internal_error"))
			return
		}
		if e = validate(body); e != nil {
			reject(w, e)
			return
		}
		request := commands.Request{Credential: credential, WorkspaceID: strings.ToLower(r.PathValue("workspace_id")), ActionID: strings.ToLower(r.Header.Get("Idempotency-Key")), GenerationID: strings.ToLower(r.Header.Get("X-Sync-Generation")), Method: r.Method, Path: r.URL.Path, Body: body, RequestID: w.Header().Get("X-Request-ID"), Authorize: func(p identity.Principal) error {
			if transport == "cookie" {
				sum := sha256.Sum256([]byte(r.Header.Get("X-CSRF-Token")))
				if len(r.Header.Values("Origin")) != 1 || r.Header.Get("Origin") != options.Origin || len(r.Header.Values("X-CSRF-Token")) != 1 || subtle.ConstantTimeCompare(sum[:], p.CSRFHash) != 1 {
					return fail(403, "csrf_invalid")
				}
			}
			return nil
		}}
		result, e := execute(r, request)
		if e != nil {
			reject(w, e)
			return
		}
		w.Header().Set("Idempotency-Replayed", "false")
		if result.Replayed {
			w.Header().Set("Idempotency-Replayed", "true")
		}
		respond(w, result.Status, result.Body)
	}
}
