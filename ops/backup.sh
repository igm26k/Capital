#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
profile="${1:-test}"
case "$profile" in
 test) compose=(docker compose --project-name accounting-test -f compose.yaml -f compose.test.yaml) ;;
 local) compose=(docker compose --project-name accounting-local -f compose.yaml) ;;
 prod) compose=(docker compose --project-name accounting-prod -f compose.yaml -f compose.prod.yaml) ;;
 *) echo 'Usage: ops/backup.sh test|local|prod [output.dump]' >&2; exit 2 ;;
esac
if [[ -n "${ACCOUNTING_ENV_FILE:-}" ]]; then compose+=(--env-file "$ACCOUNTING_ENV_FILE"); fi
output="${2:-ops/.runtime/backups/${profile}-$(date -u +%Y%m%dT%H%M%SZ).dump}"
mkdir -p "$(dirname "$output")"
for target in "$output" "${output}.sha256"; do
 [[ ! -e "$target" && ! -L "$target" ]] || { echo 'Backup or checksum already exists.' >&2; exit 1; }
done
partial=$(mktemp "${output}.partial.XXXXXX")
checksum=$(mktemp "${output}.sha256.partial.XXXXXX")
trap 'rm -f -- "$partial" "$checksum"' EXIT
"${compose[@]}" exec -T db sh -c 'exec pg_dump --format=custom --no-owner --no-privileges -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$partial"
test -s "$partial"
python3 - "$partial" <<'PYSYNC'
import os,sys
with open(sys.argv[1],'rb') as file:os.fsync(file.fileno())
PYSYNC
# Hard links publish without replacement, including when concurrent writers chose the same name.
ln -- "$partial" "$output"
(cd "$(dirname "$output")" && sha256sum -- "$(basename "$output")") > "$checksum"
ln -- "$checksum" "${output}.sha256"
python3 - "$output" <<'PYSYNC'
import os,pathlib,sys
with open(sys.argv[1]+'.sha256','rb') as file:os.fsync(file.fileno())
fd=os.open(pathlib.Path(sys.argv[1]).parent,os.O_RDONLY|os.O_DIRECTORY)
try:os.fsync(fd)
finally:os.close(fd)
PYSYNC
echo "Backup created: $output"
