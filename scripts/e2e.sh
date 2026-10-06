#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v npm >/dev/null || { echo 'Node/npm required for make e2e.' >&2; exit 1; }
test -x "${CHROME_PATH:-/usr/bin/google-chrome}" || { echo 'Set CHROME_PATH to an installed Chrome executable.' >&2; exit 1; }
compose=(docker compose --project-name accounting-test -f compose.yaml -f compose.test.yaml -f compose.e2e.yaml)
trap '"${compose[@]}" down' EXIT
./scripts/local-tls.sh
"${compose[@]}" up --build -d --wait
curl --noproxy '*' --fail --silent --show-error --cacert ops/.runtime/tls/localhost.crt --retry 10 --retry-connrefused --retry-delay 1 --retry-all-errors https://localhost:8444/health/ready >/dev/null
(cd web && npm ci && npm exec playwright test)
