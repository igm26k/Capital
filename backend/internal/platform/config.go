package platform

import (
	"accounting/backend/internal/maildelivery"
	commands "accounting/backend/internal/sync"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"net/url"
	"os"
	"strconv"
)

type Config struct {
	EmailVerification          bool
	EmailKey                   []byte
	SMTP                       maildelivery.Config
	CursorKeys                 *commands.CursorKeys
	Environment                string
	DatabaseURL                string
	HTTPAddress                string
	PublicOrigin               string
	RegistrationEnabled        bool
	AuthHashConcurrency        int
	AuthIPAttemptsPerMinute    int
	AuthEmailAttemptsPerMinute int
}

func LoadConfig() (Config, error) { return loadConfig(os.LookupEnv) }

func loadConfig(lookup func(string) (string, bool)) (Config, error) {
	value := func(name, fallback string) string {
		if v, ok := lookup(name); ok {
			return v
		}
		return fallback
	}
	cfg := Config{
		Environment:  value("APP_ENV", "local"),
		DatabaseURL:  value("DATABASE_URL", ""),
		HTTPAddress:  value("HTTP_ADDR", "127.0.0.1:8080"),
		PublicOrigin: value("PUBLIC_ORIGIN", "https://localhost:5173"),
	}
	switch cfg.Environment {
	case "local", "test", "production":
	default:
		return Config{}, errors.New("invalid APP_ENV")
	}
	db, err := url.Parse(cfg.DatabaseURL)
	if err != nil || db == nil || (db.Scheme != "postgres" && db.Scheme != "postgresql") || db.Host == "" || db.Path == "" || db.Path == "/" {
		return Config{}, errors.New("invalid DATABASE_URL")
	}
	if _, _, err := net.SplitHostPort(cfg.HTTPAddress); err != nil {
		return Config{}, errors.New("invalid HTTP_ADDR")
	}
	origin, err := url.Parse(cfg.PublicOrigin)
	if err != nil || origin == nil || origin.Host == "" || origin.User != nil || origin.RawQuery != "" || origin.Fragment != "" || (origin.Path != "" && origin.Path != "/") || (origin.Scheme != "http" && origin.Scheme != "https") {
		return Config{}, errors.New("invalid PUBLIC_ORIGIN")
	}
	hashConcurrency, err := strconv.Atoi(value("AUTH_HASH_CONCURRENCY", "2"))
	if err != nil || hashConcurrency < 1 || hashConcurrency > 8 {
		return Config{}, errors.New("invalid AUTH_HASH_CONCURRENCY")
	}
	cfg.AuthHashConcurrency = hashConcurrency
	cfg.AuthIPAttemptsPerMinute, err = strconv.Atoi(value("AUTH_IP_ATTEMPTS_PER_MINUTE", "100"))
	if err != nil || cfg.AuthIPAttemptsPerMinute < 1 || cfg.AuthIPAttemptsPerMinute > 10000 {
		return Config{}, errors.New("invalid AUTH_IP_ATTEMPTS_PER_MINUTE")
	}
	cfg.AuthEmailAttemptsPerMinute, err = strconv.Atoi(value("AUTH_EMAIL_ATTEMPTS_PER_MINUTE", "20"))
	if err != nil || cfg.AuthEmailAttemptsPerMinute < 1 || cfg.AuthEmailAttemptsPerMinute > 10000 {
		return Config{}, errors.New("invalid AUTH_EMAIL_ATTEMPTS_PER_MINUTE")
	}
	enabled, err := strconv.ParseBool(value("REGISTRATION_ENABLED", "false"))
	if err != nil {
		return Config{}, errors.New("invalid REGISTRATION_ENABLED")
	}
	cfg.RegistrationEnabled = enabled
	cfg.EmailVerification, err = strconv.ParseBool(value("AUTH_EMAIL_VERIFICATION", "false"))
	if err != nil {
		return Config{}, errors.New("invalid AUTH_EMAIL_VERIFICATION")
	}
	if cfg.Environment == "production" && cfg.RegistrationEnabled && !cfg.EmailVerification {
		return Config{}, errors.New("public registration requires email verification")
	}
	if cfg.EmailVerification {
		cfg.EmailKey, err = base64.StdEncoding.DecodeString(value("AUTH_EMAIL_KEY", ""))
		if err != nil || len(cfg.EmailKey) != 32 {
			return Config{}, errors.New("invalid AUTH_EMAIL_KEY")
		}
		cfg.SMTP = maildelivery.Config{Address: value("SMTP_ADDRESS", ""), Username: value("SMTP_USERNAME", ""), Password: value("SMTP_PASSWORD", ""), From: value("SMTP_FROM", ""), Mode: value("SMTP_MODE", "starttls")}
		if err = cfg.SMTP.Validate(); err != nil {
			return Config{}, err
		}
	}
	if cfg.Environment == "production" {
		if origin.Scheme != "https" || db.Query().Get("sslmode") != "verify-full" {
			return Config{}, errors.New("production requires verified TLS")
		}
	}
	if encoded := value("SYNC_CURSOR_KEYS", ""); encoded != "" {
		var encodedKeys map[string]string
		if json.Unmarshal([]byte(encoded), &encodedKeys) != nil {
			return Config{}, errors.New("invalid SYNC_CURSOR_KEYS")
		}
		keys := map[string][]byte{}
		for id, key := range encodedKeys {
			raw, e := base64.StdEncoding.DecodeString(key)
			if e != nil {
				return Config{}, errors.New("invalid SYNC_CURSOR_KEYS")
			}
			keys[id] = raw
		}
		cfg.CursorKeys, err = commands.NewCursorKeys(value("SYNC_CURSOR_ACTIVE_KEY", ""), keys)
		if err != nil {
			return Config{}, err
		}
	}
	if cfg.Environment == "production" && cfg.CursorKeys == nil {
		return Config{}, errors.New("production requires persistent sync cursor keys")
	}
	return cfg, nil
}
