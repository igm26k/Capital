#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
command -v npm >/dev/null || { echo 'Node/npm required.' >&2; exit 1; }
compose=(docker compose --project-name accounting-test -f compose.yaml -f compose.test.yaml --profile checks)
name="accounting-email-browser-$$"
cleanup(){ docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
mkdir -p ops/.runtime/checks
npm --prefix web run build
"${compose[@]}" run --rm --name "$name" -p 127.0.0.1:8446:8446 -e EMAIL_BROWSER_TEST=1 -v "$PWD/backend:/src" -v "$PWD/contracts:/contracts:ro" -v "$PWD/web/dist:/web-dist:ro" reports-check go test ./tests/integration -run '^TestEmailBrowser$' -count=1 -v -timeout 180s > ops/.runtime/checks/email-browser-server.log 2>&1 &
runner=$!
# This dedicated test host generates its own temporary TLS certificate.
curl --noproxy '*' --insecure --fail --silent --show-error --retry 40 --retry-delay 1 --retry-all-errors --output /dev/null https://localhost:8446/health/ready
(cd web && npm exec playwright test -- --config=playwright.email.config.ts)
wait "$runner"
echo 'PASS verified-registration/password-recovery real browser and isolated PostgreSQL; SMTP transport verified separately.'
