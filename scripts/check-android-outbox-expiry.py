#!/usr/bin/env python3
"""Cold expired-receipt reconciliation against isolated accounting-test PostgreSQL."""
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
    return subprocess.run(args, input=body, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, check=True, cwd=root).stdout

def native(phase):
    output = run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', f'com.capital.accounting.OutboxExpiryRuntimeTest#{phase}', '-e', 'outbox_expiry_host', 'true', '-e', 'api_origin', 'https://localhost:8444', 'com.capital.accounting.test/androidx.test.runner.AndroidJUnitRunner'])
    (checks / f'android-outbox-expiry-{phase}-instrumentation.txt').write_text(output)
    if not re.search(r'^OK \(1 test\)', output, re.M) or re.search(r'INSTRUMENTATION_STATUS_CODE: -[1-4]', output):
        raise RuntimeError('Native outbox expiry failed; inspect restricted artifact')

def proof():
    return json.loads(run([adb, '-s', serial, 'exec-out', 'run-as', 'com.capital.accounting', 'cat', 'files/outbox-expiry.json']))

def sql(query):
    return run(compose + ['exec', '-T', 'db', 'sh', '-c', 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d accounting_test'], query).strip()

native('prepare')
before = proof()
assert before['stage'] == 'prepared'
for key in ['workspace', 'owner', 'action', 'generation', 'tag']:
    assert re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', before[key])
w, owner, action, generation = [before[key] for key in ['workspace', 'owner', 'action', 'generation']]
# Preserve the exact 720-hour deadline CHECK; only this synthetic fixture is aged.
assert sql(f"WITH stamp AS (SELECT clock_timestamp() AS now) UPDATE actions SET completed_at=stamp.now-interval '744 hours',response_expires_at=stamp.now-interval '24 hours',response_body=NULL FROM stamp WHERE workspace_id='{w}' AND actor_user_id='{owner}' AND action_id='{action}' AND generation_id='{generation}' AND state='applied' RETURNING action_id;\n") == action
run([adb, '-s', serial, 'shell', 'am', 'force-stop', 'com.capital.accounting'])
native('verify')
after = proof()
assert after['stage'] == 'verified' and after['pid'] != before['pid']
for key in ['workspace', 'owner', 'action', 'generation', 'tag']:
    assert after[key] == before[key]
for key in ['expired_reply', 'compact_applied', 'cursor_preserved', 'command_preserved', 'pull_covered']:
    assert after[key] is True
counts = json.loads(sql(f"SELECT json_build_object('actions',(SELECT count(*) FROM actions WHERE workspace_id='{w}'),'groups',(SELECT count(*) FROM sync_groups WHERE workspace_id='{w}'),'tags',(SELECT count(*) FROM tags WHERE workspace_id='{w}'),'head',(SELECT last_sequence FROM sync_heads WHERE workspace_id='{w}'),'expired',(SELECT response_body IS NULL AND response_expires_at<=clock_timestamp() FROM actions WHERE workspace_id='{w}' AND actor_user_id='{owner}' AND action_id='{action}'));\n"))
assert counts == {'actions': 1, 'groups': 1, 'tags': 1, 'head': 1, 'expired': True}
after['sql_no_duplicate'] = True
(checks / 'android-outbox-expiry-runtime.json').write_text(json.dumps(after))
print('PASS native cold expired receipt/compact outcome/pull coverage and unchanged SQL action count')
