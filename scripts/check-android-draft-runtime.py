#!/usr/bin/env python3
"""Verify unsent scoped drafts across an actual Android process stop and SQL ledger."""
import json
import re
import subprocess
import sys
import uuid
from pathlib import Path

adb, serial = sys.argv[1:3]
root = Path(__file__).resolve().parents[1]
package = 'com.capital.accounting'
compose = ['docker', 'compose', '--project-name', 'accounting-test', '-f', 'compose.yaml', '-f', 'compose.test.yaml', '-f', 'compose.e2e.yaml']

def device(*args):
    return subprocess.run([adb, '-s', serial, *args], check=True, capture_output=True).stdout

def sql(query):
    return subprocess.run(compose + ['exec', '-T', 'db', 'sh', '-c', 'psql -U "$POSTGRES_USER" -d accounting_test -Atc "$1"', '_', query], check=True, capture_output=True, text=True).stdout.strip()

for phase in ['prepare', 'verify']:
    artifact = root / f'ops/.runtime/checks/android-draft-{phase}-instrumentation.txt'
    with artifact.open('w') as output:
        artifact.chmod(0o600)
        subprocess.run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', package+'.FinancialDraftRuntimeTest', '-e', 'api_origin', 'https://localhost:8444', '-e', 'draft_phase', phase, package+'.test/androidx.test.runner.AndroidJUnitRunner'], check=True, stdout=output, stderr=subprocess.STDOUT, timeout=300)
    result = artifact.read_text()
    assert re.search(r'^OK \(1 test\)', result, re.M) and not re.search(r'INSTRUMENTATION_STATUS_CODE: -[1-4]', result), 'Draft native phase failed; inspect restricted artifact'
    if phase == 'prepare':
        device('shell', 'am', 'force-stop', package)
        stopped = subprocess.run([adb, '-s', serial, 'shell', 'pidof', package], capture_output=True)
        assert not stopped.stdout.strip(), 'Target process is still live'

data = json.loads(device('exec-out', 'run-as', package, 'cat', 'files/financial-draft-proof.json'))
assert data['stage'] == 'verified' and all(data[key] for key in ['process_restart', 'scope_isolation', 'stale_version_retained'])
workspace = str(uuid.UUID(data['workspace_id']))
expense = str(uuid.UUID(data['expense_id']))
assert sql(f"SELECT (SELECT count(*) FROM accounts WHERE workspace_id='{workspace}'), (SELECT count(*) FROM transactions WHERE workspace_id='{workspace}'), (SELECT count(*) FROM categories WHERE workspace_id='{workspace}'), (SELECT count(*) FROM tags WHERE workspace_id='{workspace}')") == '2|3|0|0'
assert sql(f"SELECT a.currency_code || ':' || COALESCE(sum(e.amount_minor) FILTER (WHERE t.status='posted' AND t.deleted_at IS NULL),0)::text FROM accounts a LEFT JOIN entries e ON e.account_id=a.id LEFT JOIN transactions t ON t.id=e.transaction_id WHERE a.workspace_id='{workspace}' GROUP BY a.id ORDER BY a.currency_code") == 'KWD:87655\nUSD:3000'
assert sql(f"SELECT version || ':' || note || ':' || to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') FROM transactions WHERE id='{expense}'") == '2:Foreign version2:2026-10-06T08:10:00.123456'
# Retain proof flags/identities only in the host acceptance artifact, never draft bodies.
data.pop('snapshot')
output = root / 'ops/.runtime/checks/android-draft-runtime.json'
output.write_text(json.dumps(data, indent=2)+'\n')
output.chmod(0o600)
print('PASS native unsent drafts, actual process stop, owner isolation, retained stale versions and unchanged SQL ledger')
