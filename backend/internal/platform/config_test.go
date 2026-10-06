package platform

import (
	"strings"
	"testing"
)

func configFrom(values map[string]string) (Config, error) {
	return loadConfig(func(name string) (string, bool) { value, ok := values[name]; return value, ok })
}

func TestRegistrationRequiresExplicitLocalConfiguration(t *testing.T) {
	cfg, err := configFrom(map[string]string{"DATABASE_URL": "postgres://app:example@127.0.0.1/accounting_local?sslmode=disable"})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.RegistrationEnabled {
		t.Fatal("registration enabled by default")
	}
	cfg, err = configFrom(map[string]string{"DATABASE_URL": "postgres://app:example@127.0.0.1/accounting_test?sslmode=disable", "APP_ENV": "test", "REGISTRATION_ENABLED": "true"})
	if err != nil || !cfg.RegistrationEnabled {
		t.Fatal("explicit test registration not accepted", err)
	}
	_, err = configFrom(map[string]string{"DATABASE_URL": "postgres://app:example@database/accounting?sslmode=verify-full", "APP_ENV": "production", "PUBLIC_ORIGIN": "https://accounting.example.test", "REGISTRATION_ENABLED": "true"})
	if err == nil {
		t.Fatal("unverified public registration accepted")
	}
}

func TestInvalidConfigurationDoesNotExposeCredentials(t *testing.T) {
	secret := "private-database-password"
	for _, dsn := range []string{"postgres://app:" + secret + "%INVALID@127.0.0.1/db", "https://app:" + secret + "@host/db", "postgres://app:" + secret + "@host/"} {
		_, err := configFrom(map[string]string{"DATABASE_URL": dsn})
		if err == nil {
			t.Fatal("invalid database URL accepted")
		}
		if strings.Contains(err.Error(), secret) {
			t.Fatal("credentials exposed in config error")
		}
	}
}

func TestProductionRequiresVerifiedTLS(t *testing.T) {
	values := map[string]string{"DATABASE_URL": "postgres://app:example@database/accounting?sslmode=disable", "APP_ENV": "production", "PUBLIC_ORIGIN": "https://accounting.example.test"}
	if _, err := configFrom(values); err == nil {
		t.Fatal("unverified database transport accepted")
	}
	values["DATABASE_URL"] = "postgres://app:example@database/accounting?sslmode=verify-full"
	if _, err := configFrom(values); err != nil {
		t.Fatal(err)
	}
	values["PUBLIC_ORIGIN"] = "https://user:password@accounting.example.test"
	if _, err := configFrom(values); err == nil {
		t.Fatal("userinfo accepted in public origin")
	}
}

func TestAuthResourceLimits(t *testing.T) {
	for _, name := range []string{"AUTH_HASH_CONCURRENCY", "AUTH_IP_ATTEMPTS_PER_MINUTE", "AUTH_EMAIL_ATTEMPTS_PER_MINUTE"} {
		for _, invalid := range []string{"0", "-1", "invalid", "10001"} {
			if _, err := configFrom(map[string]string{"DATABASE_URL": "postgres://app:example@host/test", name: invalid}); err == nil {
				t.Fatalf("invalid %s accepted", name)
			}
		}
	}
}

func TestCursorKeyConfiguration(t *testing.T) {
	values := map[string]string{"DATABASE_URL": "postgres://app:example@localhost/accounting?sslmode=disable", "SYNC_CURSOR_ACTIVE_KEY": "v1", "SYNC_CURSOR_KEYS": `{"v1":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="}`}
	cfg, e := configFrom(values)
	if e != nil || cfg.CursorKeys == nil {
		t.Fatal("cursor keys unavailable", e)
	}
	for _, bad := range []string{`{"v1":"secret"}`, `{"v1":"AQ=="}`, `{"v2":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="}`, `{`} {
		values["SYNC_CURSOR_KEYS"] = bad
		if _, e = configFrom(values); e == nil || strings.Contains(e.Error(), "secret") {
			t.Fatal("invalid key configuration accepted or disclosed")
		}
	}
}
