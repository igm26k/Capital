package httpapi

import (
	"accounting/backend/internal/identity"
	commands "accounting/backend/internal/sync"
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"github.com/jackc/pgx/v5"
	"io"
	"mime"
	"net"
	"net/http"
	"net/mail"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type AuthOptions struct {
	CursorKeys             *commands.CursorKeys
	Origin                 string
	Registration           bool
	IPAttemptsPerMinute    int
	EmailAttemptsPerMinute int
}
type authRoutes struct {
	service *identity.Service
	options AuthOptions
	limiter attemptLimiter
}
type attempt struct {
	start time.Time
	count int
}
type attemptLimiter struct {
	mu      sync.Mutex
	entries map[string]attempt
}

func (l *attemptLimiter) allow(key string, maximum int) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	if l.entries == nil {
		l.entries = map[string]attempt{}
	}
	for k, v := range l.entries {
		if now.Sub(v.start) >= time.Minute {
			delete(l.entries, k)
		}
	}
	v, ok := l.entries[key]
	if !ok {
		if len(l.entries) >= 4096 {
			return false
		}
		v.start = now
	}
	v.count++
	l.entries[key] = v
	return v.count <= maximum
}
func MountAuth(mux *http.ServeMux, service *identity.Service, options AuthOptions) {
	if options.IPAttemptsPerMinute == 0 {
		options.IPAttemptsPerMinute = 100
	}
	if options.EmailAttemptsPerMinute == 0 {
		options.EmailAttemptsPerMinute = 20
	}
	a := &authRoutes{service: service, options: options}
	mux.HandleFunc("POST /api/v1/auth/register", a.register)
	mux.HandleFunc("POST /api/v1/auth/login", a.login)
	for _, route := range []string{"GET /api/v1/auth/session", "POST /api/v1/auth/renew", "POST /api/v1/auth/logout", "GET /api/v1/me", "PUT /api/v1/me", "DELETE /api/v1/sessions/{id}", "GET /api/v1/sessions", "GET /api/v1/workspaces"} {
		mux.HandleFunc(route, a.private)
	}
}
func respond(w http.ResponseWriter, status int, value any) {
	data, e := json.Marshal(value)
	if e != nil {
		panic("response encoding failed")
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(append(data, '\n'))
}
func reject(w http.ResponseWriter, err error) {
	status, code := 503, "service_unavailable"
	var e *identity.Error
	if errors.As(err, &e) {
		status, code = e.Status, e.Code
	}
	if status == 429 {
		w.Header().Set("Retry-After", "60")
	}
	var retry any
	if status == 429 {
		retry = 60
	}
	respond(w, status, map[string]any{"request_id": w.Header().Get("X-Request-ID"), "code": code, "message": "Не удалось выполнить запрос.", "field_errors": []any{}, "current_versions": []any{}, "retry_after_seconds": retry})
}
func fail(status int, code string) error { return &identity.Error{Status: status, Code: code} }
func validEscapes(data []byte) bool {
	for i := 0; i < len(data); i++ {
		if data[i] != '\\' {
			continue
		}
		i++
		if i >= len(data) {
			return false
		}
		if data[i] != 'u' {
			continue
		}
		if i+4 >= len(data) {
			return false
		}
		n, e := strconv.ParseUint(string(data[i+1:i+5]), 16, 16)
		if e != nil {
			return false
		}
		i += 4
		if n >= 0xD800 && n <= 0xDBFF {
			if i+6 >= len(data) || data[i+1] != '\\' || data[i+2] != 'u' {
				return false
			}
			low, e := strconv.ParseUint(string(data[i+3:i+7]), 16, 16)
			if e != nil || low < 0xDC00 || low > 0xDFFF {
				return false
			}
			i += 6
		} else if n >= 0xDC00 && n <= 0xDFFF {
			return false
		}
	}
	return true
}
func fields(w http.ResponseWriter, r *http.Request, required ...string) (map[string]string, error) {
	media, _, e := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if e != nil || media != "application/json" {
		return nil, fail(400, "bad_request")
	}
	data, e := io.ReadAll(http.MaxBytesReader(w, r.Body, 16384))
	if e != nil {
		var large *http.MaxBytesError
		if errors.As(e, &large) {
			return nil, fail(413, "bad_request")
		}
		return nil, fail(400, "bad_request")
	}
	if !utf8.Valid(data) || !json.Valid(data) || !validEscapes(data) {
		return nil, fail(400, "bad_request")
	}
	d := json.NewDecoder(bytes.NewReader(data))
	first, e := d.Token()
	if e != nil || first != json.Delim('{') {
		return nil, fail(400, "bad_request")
	}
	values := map[string]string{}
	known := map[string]bool{}
	for _, k := range required {
		known[k] = true
	}
	for d.More() {
		k, e := d.Token()
		if e != nil {
			return nil, fail(400, "bad_request")
		}
		key, ok := k.(string)
		if !ok {
			return nil, fail(400, "bad_request")
		}
		if _, exists := values[key]; exists {
			return nil, fail(400, "bad_request")
		}
		if !known[key] {
			return nil, fail(422, "validation_error")
		}
		v, e := d.Token()
		if e != nil {
			return nil, fail(400, "bad_request")
		}
		value, ok := v.(string)
		if !ok {
			return nil, fail(422, "validation_error")
		}
		values[key] = value
	}
	_, _ = d.Token()
	if len(values) != len(required) {
		return nil, fail(422, "validation_error")
	}
	return values, nil
}
func validZone(zone string) bool {
	if zone == "Local" || len(zone) > 100 {
		return false
	}
	_, e := time.LoadLocation(zone)
	return e == nil && zone != ""
}
func validate(v map[string]string, registration bool) error {
	v["email"] = strings.ToLower(strings.TrimSpace(v["email"]))
	v["device_name"] = strings.TrimSpace(v["device_name"])
	email := v["email"]
	if len(email) > 254 {
		return fail(422, "validation_error")
	}
	for _, c := range email {
		if c < 33 || c > 126 {
			return fail(422, "validation_error")
		}
	}
	parsed, e := mail.ParseAddress(email)
	if e != nil || parsed.Address != email {
		return fail(422, "validation_error")
	}
	minimum := 1
	if registration {
		minimum = 12
	}
	length := utf8.RuneCountInString(v["password"])
	if length < minimum || length > 128 || utf8.RuneCountInString(v["device_name"]) < 1 || utf8.RuneCountInString(v["device_name"]) > 100 {
		return fail(422, "validation_error")
	}
	if v["transport"] != "cookie" && v["transport"] != "bearer" {
		return fail(422, "validation_error")
	}
	if registration && !validZone(v["timezone"]) {
		return fail(422, "validation_error")
	}
	return nil
}
func mixed(r *http.Request) bool {
	_, e := r.Cookie("__Host-accounting_session")
	return e == nil && len(r.Header.Values("Authorization")) > 0
}
func (a *authRoutes) public(w http.ResponseWriter, r *http.Request, registration bool) {
	if len(r.URL.RawQuery) > 0 {
		reject(w, fail(400, "bad_request"))
		return
	}
	if mixed(r) {
		reject(w, fail(400, "bad_request"))
		return
	}
	if registration && !a.options.Registration {
		reject(w, fail(403, "registration_disabled"))
		return
	}
	required := []string{"email", "password", "device_name", "transport"}
	if registration {
		required = append(required, "timezone")
	}
	v, e := fields(w, r, required...)
	if e == nil {
		e = validate(v, registration)
	}
	if e != nil {
		reject(w, e)
		return
	}
	if v["transport"] == "cookie" && (len(r.Header.Values("Origin")) != 1 || r.Header.Get("Origin") != a.options.Origin) {
		reject(w, fail(403, "csrf_invalid"))
		return
	}
	ip, _, e := net.SplitHostPort(r.RemoteAddr)
	if e != nil {
		ip = r.RemoteAddr
	}
	if !a.limiter.allow("ip:"+ip, a.options.IPAttemptsPerMinute) || !a.limiter.allow("email:"+v["email"], a.options.EmailAttemptsPerMinute) {
		reject(w, fail(429, "rate_limited"))
		return
	}
	var result identity.Auth
	if registration {
		result, e = a.service.Register(r.Context(), v["email"], v["password"], v["device_name"], v["timezone"])
	} else {
		result, e = a.service.Login(r.Context(), v["email"], v["password"], v["device_name"])
	}
	if e != nil {
		reject(w, e)
		return
	}
	setCookie(w, result, v["transport"])
	status := 200
	if registration {
		status = 201
	}
	respond(w, status, result.Response(v["transport"]))
}
func (a *authRoutes) register(w http.ResponseWriter, r *http.Request) { a.public(w, r, true) }
func (a *authRoutes) login(w http.ResponseWriter, r *http.Request)    { a.public(w, r, false) }
func setCookie(w http.ResponseWriter, a identity.Auth, transport string) {
	if transport != "cookie" {
		return
	}
	http.SetCookie(w, &http.Cookie{Name: "__Host-accounting_session", Value: a.Credential, Path: "/", Secure: true, HttpOnly: true, SameSite: http.SameSiteLaxMode, Expires: a.Session.Expires})
}
func credential(r *http.Request) (string, string, error) {
	if mixed(r) {
		return "", "", fail(400, "bad_request")
	}
	headers := r.Header.Values("Authorization")
	if len(headers) > 0 {
		if len(headers) != 1 {
			return "", "", fail(400, "bad_request")
		}
		parts := strings.Fields(headers[0])
		if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
			return "", "", fail(401, "unauthenticated")
		}
		if len(parts[1]) != 43 {
			return "", "", fail(401, "unauthenticated")
		}
		return parts[1], "bearer", nil
	}
	var cookies []*http.Cookie
	for _, c := range r.Cookies() {
		if c.Name == "__Host-accounting_session" {
			cookies = append(cookies, c)
		}
	}
	if len(cookies) > 1 {
		return "", "", fail(400, "bad_request")
	}
	if len(cookies) != 1 || len(cookies[0].Value) != 43 {
		return "", "", fail(401, "unauthenticated")
	}
	return cookies[0].Value, "cookie", nil
}
func (a *authRoutes) private(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/api/v1/sessions" && r.URL.Path != "/api/v1/workspaces" && len(r.URL.RawQuery) > 0 {
		reject(w, fail(400, "bad_request"))
		return
	}
	token, transport, e := credential(r)
	if e != nil {
		reject(w, e)
		return
	}
	write := r.Method != "GET"
	var result any
	var auth *identity.Auth
	clear := false
	e = a.service.WithSession(r.Context(), token, write, func(tx pgx.Tx, p identity.Principal) error {
		if transport == "cookie" && write {
			sum := sha256.Sum256([]byte(r.Header.Get("X-CSRF-Token")))
			if len(r.Header.Values("Origin")) != 1 || r.Header.Get("Origin") != a.options.Origin || len(r.Header.Values("X-CSRF-Token")) != 1 || subtle.ConstantTimeCompare(sum[:], p.CSRFHash) != 1 {
				return fail(403, "csrf_invalid")
			}
		}
		switch {
		case r.URL.Path == "/api/v1/auth/session" || r.URL.Path == "/api/v1/auth/renew":
			var a identity.Auth
			var err error
			if write {
				a, err = identity.Renew(r.Context(), tx, p, token)
			} else {
				a, err = identity.Current(r.Context(), tx, p, token)
			}
			if err != nil {
				return err
			}
			result = a.Response(transport)
			auth = &a
		case r.URL.Path == "/api/v1/auth/logout":
			if err := identity.Revoke(r.Context(), tx, p.Profile.ID, p.Session.ID); err != nil {
				return err
			}
			result = map[string]bool{"ok": true}
			clear = transport == "cookie"
		case r.Method == "DELETE":
			id := r.PathValue("id")
			if !validUUID(id) {
				return fail(400, "bad_request")
			}
			if err := identity.Revoke(r.Context(), tx, p.Profile.ID, id); err != nil {
				return err
			}
			result = map[string]bool{"ok": true}
			clear = transport == "cookie" && strings.EqualFold(id, p.Session.ID)
		case r.URL.Path == "/api/v1/me":
			if write {
				v, err := fields(w, r, "timezone")
				if err != nil {
					return err
				}
				if !validZone(v["timezone"]) {
					return fail(422, "validation_error")
				}
				profile, err := identity.UpdateProfile(r.Context(), tx, p.Profile, v["timezone"])
				if err != nil {
					return err
				}
				result = profile
			} else {
				result = p.Profile
			}
		default:
			list, err := list(r.Context(), tx, p, token, r.URL)
			if err != nil {
				return err
			}
			result = list
		}
		return nil
	})
	if e != nil {
		reject(w, e)
		return
	}
	if auth != nil && write {
		setCookie(w, *auth, transport)
	}
	if clear {
		http.SetCookie(w, &http.Cookie{Name: "__Host-accounting_session", Value: "", Path: "/", Secure: true, HttpOnly: true, SameSite: http.SameSiteLaxMode, MaxAge: -1})
	}
	respond(w, 200, result)
}
func validUUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i, c := range s {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if c != '-' {
				return false
			}
		} else if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F') {
			return false
		}
	}
	return true
}
func signedCursor(token, value string) string {
	m := hmac.New(sha256.New, []byte(token))
	m.Write([]byte(value))
	return base64.RawURLEncoding.EncodeToString([]byte(value)) + "." + base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}
