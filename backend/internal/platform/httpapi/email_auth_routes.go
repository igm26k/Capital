package httpapi

import (
	"net"
	"net/http"
	"net/mail"
	"strings"
	"unicode/utf8"
)

// All three confirmation endpoints are public and rate limited, without session cookies as proof.
func (a *authRoutes) emailFields(w http.ResponseWriter, r *http.Request, keys ...string) (map[string]string, error) {
	if r.URL.RawQuery != "" || mixed(r) {
		return nil, fail(400, "bad_request")
	}
	if !a.options.Verification {
		return nil, fail(403, "registration_disabled")
	}
	v, err := fields(w, r, keys...)
	if err != nil {
		return nil, err
	}
	v["email"] = strings.ToLower(strings.TrimSpace(v["email"]))
	parsed, err := mail.ParseAddress(v["email"])
	if err != nil || len(v["email"]) > 254 || parsed.Address != v["email"] {
		return nil, fail(422, "validation_error")
	}
	for _, c := range v["email"] {
		if c < 33 || c > 126 {
			return nil, fail(422, "validation_error")
		}
	}
	// Cookie requests require same-origin. Native bearers do not send an Origin.
	if len(r.Header.Values("Origin")) > 0 && (len(r.Header.Values("Origin")) != 1 || r.Header.Get("Origin") != a.options.Origin) {
		return nil, fail(403, "csrf_invalid")
	}
	if v["transport"] == "cookie" && (len(r.Header.Values("Origin")) != 1 || r.Header.Get("Origin") != a.options.Origin) {
		return nil, fail(403, "csrf_invalid")
	}
	ip, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		ip = r.RemoteAddr
	}
	if !a.limiter.allow("email-ip:"+ip, a.options.IPAttemptsPerMinute) || !a.limiter.allow("email-confirm:"+v["email"], a.options.EmailAttemptsPerMinute) {
		return nil, fail(429, "rate_limited")
	}
	if password, ok := v["password"]; ok {
		if utf8.RuneCountInString(password) < 12 || utf8.RuneCountInString(password) > 128 {
			return nil, fail(422, "validation_error")
		}
	}
	return v, nil
}
func (a *authRoutes) confirmRegistration(w http.ResponseWriter, r *http.Request) {
	if !a.options.Registration {
		reject(w, fail(403, "registration_disabled"))
		return
	}
	v, err := a.emailFields(w, r, "email", "code", "password", "device_name", "transport")
	if err != nil {
		reject(w, err)
		return
	}
	if (v["transport"] != "cookie" && v["transport"] != "bearer") || utf8.RuneCountInString(strings.TrimSpace(v["device_name"])) < 1 || utf8.RuneCountInString(v["device_name"]) > 100 {
		reject(w, fail(422, "validation_error"))
		return
	}
	result, err := a.service.ConfirmRegistration(r.Context(), v["email"], v["code"], v["password"], strings.TrimSpace(v["device_name"]))
	if err != nil {
		reject(w, err)
		return
	}
	setCookie(w, result, v["transport"])
	respond(w, 201, result.Response(v["transport"]))
}
func (a *authRoutes) requestRecovery(w http.ResponseWriter, r *http.Request) {
	v, err := a.emailFields(w, r, "email")
	if err != nil {
		reject(w, err)
		return
	}
	if err = a.service.StartEmailAuth(r.Context(), a.options.EmailKey, v["email"], "recovery", "", ""); err != nil {
		reject(w, err)
		return
	}
	respond(w, 202, map[string]string{"status": "confirmation_required"})
}
func (a *authRoutes) confirmRecovery(w http.ResponseWriter, r *http.Request) {
	v, err := a.emailFields(w, r, "email", "code", "password")
	if err != nil {
		reject(w, err)
		return
	}
	if err = a.service.ConfirmRecovery(r.Context(), v["email"], v["code"], v["password"]); err != nil {
		reject(w, err)
		return
	}
	respond(w, 200, map[string]string{"status": "password_changed"})
}
