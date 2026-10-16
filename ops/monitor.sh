#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
profile="${1:-test}"
case "$profile" in
 test) compose=(docker compose --project-name accounting-test -f compose.yaml -f compose.test.yaml) ;;
 local) compose=(docker compose --project-name accounting-local -f compose.yaml) ;;
 prod) compose=(docker compose --project-name accounting-prod -f compose.yaml -f compose.prod.yaml) ;;
 *) echo 'Usage: ops/monitor.sh test|local|prod' >&2; exit 2 ;;
esac
if [[ -n "${ACCOUNTING_ENV_FILE:-}" ]]; then compose+=(--env-file "$ACCOUNTING_ENV_FILE"); fi
"${compose[@]}" exec -T db sh -c 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT json_build_object('source_queued',(SELECT count(*) FROM source_events WHERE state='queued'),'source_failed',(SELECT count(*) FROM source_events WHERE state='failed'),'source_oldest_pending_seconds',(SELECT coalesce(extract(epoch FROM clock_timestamp()-min(queued_at)),0)::bigint FROM source_events WHERE state='queued'),'email_queued',(SELECT count(*) FROM auth_mail WHERE sent_at IS NULL AND attempts<5),'email_failed',(SELECT count(*) FROM auth_mail WHERE sent_at IS NULL AND attempts=5),'email_oldest_pending_seconds',(SELECT coalesce(extract(epoch FROM clock_timestamp()-min(c.created_at)),0)::bigint FROM auth_mail m JOIN auth_challenges c ON c.id=m.challenge_id WHERE m.sent_at IS NULL),'csv_queued',(SELECT count(*) FROM csv_imports WHERE state='queued'),'csv_failed',(SELECT count(*) FROM csv_imports WHERE state='failed'),'csv_unapplied_rows',(SELECT count(*) FROM csv_rows WHERE state='queued'),'oldest_pending_seconds',(SELECT coalesce(extract(epoch FROM clock_timestamp()-min(expires_at-interval '24 hours')),0)::bigint FROM csv_imports WHERE state='queued'),'snapshot_cleanup_overdue',(SELECT count(*) FROM sync_snapshots WHERE expires_at<clock_timestamp()-interval '5 minutes' AND payload_cleared_at IS NULL),'expired_snapshots',(SELECT count(*) FROM sync_snapshots WHERE expires_at<clock_timestamp() AND payload_cleared_at IS NULL));
SQL
