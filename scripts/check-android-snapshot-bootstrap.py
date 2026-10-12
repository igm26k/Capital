#!/usr/bin/env python3
"""Real process restarts and PostgreSQL snapshot expiry in accounting-test only."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

os.umask(0o077)
root = Path(__file__).resolve().parents[1]
adb, serial = sys.argv[1:3]
checks = root / 'ops/.runtime/checks'
checks.mkdir(parents=True, exist_ok=True)
compose = ['docker', 'compose', '--project-name', 'accounting-test', '-f', 'compose.yaml', '-f', 'compose.test.yaml', '-f', 'compose.e2e.yaml']
def run(args, body=None):
    return subprocess.run(args, input=body, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=root, timeout=60, check=True).stdout

def proof():
    return json.loads(run([adb, '-s', serial, 'exec-out', 'run-as', 'com.capital.accounting', 'cat', 'files/snapshot-bootstrap-runtime.json']))

def native(phase):
    output = run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', f'com.capital.accounting.SnapshotBootstrapRuntimeTest#{phase}', '-e', 'snapshot_host', 'true', '-e', 'api_origin', 'https://localhost:8444', 'com.capital.accounting.test/androidx.test.runner.AndroidJUnitRunner'])
    (checks / f'android-snapshot-{phase}-instrumentation.txt').write_text(output)
    if not re.search(r'^OK \(1 test\)', output, re.M) or re.search(r'INSTRUMENTATION_STATUS_CODE: -[1-4]', output):
        raise RuntimeError('Native bootstrap phase failed; inspect restricted artifact')

def sql(query):
    return run(compose + ['exec', '-T', 'db', 'sh', '-c', 'exec psql -X -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d accounting_test'], query).strip()

def expire(data, first=False):
    for field in ['workspace', 'owner', 'snapshot']:
        if not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', data[field]):
            raise RuntimeError('Invalid synthetic proof identifier')
    selector = 'base_sequence=1' if first else f"id='{data['snapshot']}'"
    # Preserve the schema's exact fifteen-minute lifetime constraint.
    result = sql(f"UPDATE sync_snapshots SET created_at=t.stamp-interval '16 minutes', expires_at=t.stamp-interval '1 minute' FROM (SELECT clock_timestamp() AS stamp) t WHERE workspace_id='{data['workspace']}' AND actor_user_id='{data['owner']}' AND {selector} RETURNING id::text;\n")
    if len(re.findall(r'^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$', result, re.M)) != 1:
        raise RuntimeError('Expected one isolated expired snapshot')

native('prepare')
prepared = proof()
assert prepared['stage'] == 'prepared'
expire(prepared, first=True)
run([adb, '-s', serial, 'shell', 'am', 'force-stop', 'com.capital.accounting'])
native('resume')
resumed = proof()
assert resumed['stage'] == 'resumed' and resumed['cold_resume'] is True
assert resumed['pid'] != prepared['pid']
expire(resumed)
run([adb, '-s', serial, 'shell', 'am', 'force-stop', 'com.capital.accounting'])
native('expired')
verified = proof()
assert verified['stage'] == 'verified' and verified['pid'] != resumed['pid']
for flag in ['cold_resume', 'expiry_rebuilt', 'command_preserved', 'exact_minor']:
    assert verified[flag] is True
workspace = verified['workspace']
summary = json.loads(sql(f"SELECT json_build_object('actions',(SELECT count(*) FROM actions WHERE workspace_id='{workspace}'),'transactions',(SELECT count(*) FROM transactions WHERE workspace_id='{workspace}' AND deleted_at IS NULL),'balance',(SELECT sum(e.amount_minor)::text FROM entries e JOIN transactions t ON (t.workspace_id,t.id)=(e.workspace_id,e.transaction_id) WHERE e.workspace_id='{workspace}' AND t.status='posted' AND t.deleted_at IS NULL));\n"))
assert summary == {'actions': 103, 'transactions': 2, 'balance': '99000'}
verified['sql_no_duplicate'] = True
(checks / 'android-snapshot-bootstrap-runtime.json').write_text(json.dumps(verified))
print('PASS native cold staging resume, real snapshot expiry/rebuild and unchanged queued command/SQL')
