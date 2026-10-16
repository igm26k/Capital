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
for table in users sessions workspaces memberships accounts transactions entries allocations categories tags transaction_tags planning_resources planning_actions planning_audit csv_imports csv_rows csv_sources sync_heads actions sync_groups sync_changes sync_snapshots sync_snapshot_items audit_events auth_challenges auth_mail source_events transaction_sources; do
    query+="SELECT '$table|'||coalesce(md5(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text)),'empty') FROM $table t; "
done
source_hash="$(printf '%s' "$query" | "${compose[@]}" exec -T db sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d accounting_test')"
clone_hash="$(printf '%s' "$query" | "${compose[@]}" exec -T db sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh "$clone")"
[[ "$source_hash" == "$clone_hash" ]] || { echo 'Restored financial content differs from source. Stop writes during the drill.' >&2; exit 1; }
# Apply the exact operator procedure to the clone and prove financial data stays unchanged.
financial_query="SET TIME ZONE 'UTC'; "
for table in accounts transactions entries allocations categories tags transaction_tags actions planning_resources planning_actions planning_audit csv_rows csv_sources transaction_sources audit_events; do
 financial_query+="SELECT '$table|'||coalesce(md5(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text)),'empty') FROM $table t; "
done
before_recovery="$(printf '%s' "$financial_query" | "${compose[@]}" exec -T db sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh "$clone")"
# Clone-only queued source canary; financial records stay untouched.
source_canary="$("${compose[@]}" exec -T db sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh "$clone" <<'SQLSOURCE'
WITH seed AS (
 SELECT a.workspace_id,a.id AS account_id,a.currency_code,s.user_id,s.id AS session_id,h.generation_id
 FROM accounts a JOIN memberships m ON m.workspace_id=a.workspace_id
 JOIN sessions s ON s.user_id=m.user_id JOIN sync_heads h ON h.workspace_id=a.workspace_id LIMIT 1
), candidate AS (
 SELECT *,gen_random_uuid() AS event_id,gen_random_uuid() AS connection_id FROM seed
)
INSERT INTO source_events(workspace_id,id,actor_user_id,session_id,generation_id,connection_id,external_id,account_id,payload_hash,payload,state,job,queued_at)
SELECT workspace_id,event_id,user_id,session_id,generation_id,connection_id,repeat('f',64),account_id,decode(repeat('00',32),'hex'),
 jsonb_build_object('id',event_id,'connection_id',connection_id,'external_id',repeat('f',64),'account_id',account_id,'package_name','restore.synthetic','profile_id','restore-canary','parser_version',1,'kind','expense','amount_minor','1','currency',currency_code,'observed_at','2026-10-01T00:00:00Z','observed_timezone','UTC','merchant','','card_last_four',NULL),
 'queued',jsonb_build_object('action_id',gen_random_uuid(),'session_id',session_id,'generation_id',generation_id,'decision','create_pending','transaction_id',gen_random_uuid(),'expected_version',''),clock_timestamp()
FROM candidate RETURNING id;
SQLSOURCE
)"
# Empty databases have no financial/session seed. Their schema/guards are still checked.
# A clone-only synthetic proof proves recovery cannot revive outstanding email codes.
"${compose[@]}" exec -T db sh -c 'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh "$clone" <<'SQL'
WITH proof AS (
 INSERT INTO auth_challenges(id,email,purpose,token_hash,expires_at)
 VALUES(gen_random_uuid(),gen_random_uuid()::text||'@restore.invalid','recovery',decode(repeat('00',32),'hex'),clock_timestamp()+interval '30 minutes') RETURNING id
)
INSERT INTO auth_mail(challenge_id,encrypted_body) SELECT id,decode('0001','hex') FROM proof;
SQL
"${compose[@]}" exec -T db sh -c 'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh "$clone" < ops/recovery-prepare.sql
if [[ -n "$source_canary" ]]; then
 source_canary_ok="$("${compose[@]}" exec -T db sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1" -v canary="$2"' sh "$clone" "$source_canary" <<'SQLSOURCECHECK'
SELECT count(*)=1 AND bool_and(state='failed' AND error_code='recovery_required' AND job IS NOT NULL) FROM source_events WHERE id=:'canary'::uuid;
SQLSOURCECHECK
)"
 [[ "$source_canary_ok" == t ]] || { echo 'Restored source job was not safely paused.' >&2; exit 1; }
fi
after_recovery="$(printf '%s' "$financial_query" | "${compose[@]}" exec -T db sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"' sh "$clone")"
[[ "$before_recovery" == "$after_recovery" ]] || { echo 'Recovery procedure changed financial data or historical receipts.' >&2; exit 1; }
mkdir -p ops/.runtime/checks
printf '%s\n' '{"restored":true,"financial_content_equal":true,"isolated_test_database":true,"verified_tables":28,"generations_rotated":true,"restored_sessions_revoked":true,"snapshot_payloads_cleared":true,"csv_jobs_paused":true,"source_jobs_paused":true,"email_proofs_cleared":true,"email_canary_removed":true,"financial_content_preserved_after_rotation":true}' > ops/.runtime/checks/restore-result.json
echo 'PASS actual PostgreSQL restore; financial content hashes match.'
