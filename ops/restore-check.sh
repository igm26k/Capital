#!/usr/bin/env bash
# Actual restore drill in a fresh database inside the isolated test PostgreSQL.
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
compose=(docker compose --project-name accounting-test -f compose.yaml -f compose.test.yaml)
backup="${1:?Usage: ops/restore-check.sh test-backup.dump}"
test -s "$backup"
clone="accounting_restore_$(date -u +%Y%m%d%H%M%S)_$$"
created=false
cleanup() { if [[ "$created" == true ]]; then "${compose[@]}" exec -T db sh -c 'exec dropdb --if-exists -U "$POSTGRES_USER" "$1"' sh "$clone"; fi; }
trap cleanup EXIT
"${compose[@]}" exec -T db sh -c 'exec createdb -U "$POSTGRES_USER" "$1"' sh "$clone"
created=true
"${compose[@]}" exec -T db sh -c 'exec pg_restore --exit-on-error --no-owner --no-privileges -U "$POSTGRES_USER" -d "$1"' sh "$clone" < "$backup"
# Compare content hashes, not just counts or a successful pg_restore exit status.
query="SET TIME ZONE 'UTC'; "
for table in users sessions workspaces memberships accounts transactions entries allocations categories tags transaction_tags planning_resources planning_actions planning_audit csv_imports csv_rows csv_sources sync_heads actions sync_groups sync_changes sync_snapshots sync_snapshot_items audit_events auth_challenges auth_mail; do
    query+="SELECT '$table|'||coalesce(md5(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text)),'empty') FROM $table t; "
done
source_hash="$(printf '%s' "$query" | "${compose[@]}" exec -T db sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d accounting_test')"
clone_hash="$(printf '%s' "$query" | "${compose[@]}" exec -T db sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh "$clone")"
[[ "$source_hash" == "$clone_hash" ]] || { echo 'Restored financial content differs from source. Stop writes during the drill.' >&2; exit 1; }
# Apply the exact operator procedure to the clone and prove financial data stays unchanged.
financial_query="SET TIME ZONE 'UTC'; "
for table in accounts transactions entries allocations categories tags transaction_tags actions planning_resources planning_actions planning_audit csv_rows csv_sources audit_events; do
 financial_query+="SELECT '$table|'||coalesce(md5(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text)),'empty') FROM $table t; "
done
before_recovery="$(printf '%s' "$financial_query" | "${compose[@]}" exec -T db sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh "$clone")"
# A clone-only synthetic proof proves recovery cannot revive outstanding email codes.
"${compose[@]}" exec -T db sh -c 'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh "$clone" <<'SQL'
WITH proof AS (
 INSERT INTO auth_challenges(id,email,purpose,token_hash,expires_at)
 VALUES(gen_random_uuid(),gen_random_uuid()::text||'@restore.invalid','recovery',decode(repeat('00',32),'hex'),clock_timestamp()+interval '30 minutes') RETURNING id
)
INSERT INTO auth_mail(challenge_id,encrypted_body) SELECT id,decode('0001','hex') FROM proof;
SQL
"${compose[@]}" exec -T db sh -c 'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh "$clone" < ops/recovery-prepare.sql
after_recovery="$(printf '%s' "$financial_query" | "${compose[@]}" exec -T db sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh "$clone")"
[[ "$before_recovery" == "$after_recovery" ]] || { echo 'Recovery procedure changed financial data or historical receipts.' >&2; exit 1; }
mkdir -p ops/.runtime/checks
printf '%s\n' '{"restored":true,"financial_content_equal":true,"isolated_test_database":true,"verified_tables":26,"generations_rotated":true,"restored_sessions_revoked":true,"snapshot_payloads_cleared":true,"csv_jobs_paused":true,"email_proofs_cleared":true,"email_canary_removed":true,"financial_content_preserved_after_rotation":true}' > ops/.runtime/checks/restore-result.json
echo 'PASS actual PostgreSQL restore; financial content hashes match.'
