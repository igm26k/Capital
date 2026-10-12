#!/usr/bin/env python3
"""Isolated PostgreSQL journal expiry/rotation and native cold recovery."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
os.umask(0o077)
root = Path(__file__).resolve().parents[1]
adb, serial = sys.argv[1:3]
checks = root / 'ops/.runtime/checks'
checks.mkdir(parents=True, exist_ok=True)
compose = ['docker', 'compose', '--project-name', 'accounting-test', '-f', 'compose.yaml', '-f', 'compose.test.yaml', '-f', 'compose.e2e.yaml']
def run(args, body=None):
    return subprocess.run(args, input=body, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, check=True, cwd=root).stdout

def native(phase):
    output = run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', f'com.capital.accounting.MirrorCursorRuntimeTest#{phase}', '-e', 'cursor_host', 'true', '-e', 'api_origin', 'https://localhost:8444', 'com.capital.accounting.test/androidx.test.runner.AndroidJUnitRunner'])
    (checks / f'android-cursor-{phase}-instrumentation.txt').write_text(output)
    if not re.search(r'^OK \(1 test\)', output, re.M) or re.search(r'INSTRUMENTATION_STATUS_CODE: -[1-4]', output):
        raise RuntimeError('Native cursor recovery failed; inspect restricted artifact')

def proof(mode):
    return json.loads(run([adb, '-s', serial, 'exec-out', 'run-as', 'com.capital.accounting', 'cat', f'files/mirror-cursor-{mode}.json']))

def sql(query):
    return run(compose + ['exec', '-T', 'db', 'sh', '-c', 'exec psql -X -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d accounting_test'], query).strip()

results = {}
for mode in ['expiry', 'rotation']:
    suffix = mode.title()
    native('prepare' + suffix)
    before = proof(mode)
    assert before['stage'] == 'prepared'
    for key in ['workspace', 'owner', 'generation']:
        assert re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', before[key])
    workspace, generation = before['workspace'], before['generation']
    new_generation = str(uuid.uuid4())
    next_head = "min_available_sequence=3" if mode == 'expiry' else f"generation_id='{new_generation}',last_sequence=0,min_available_sequence=1"
    workspace_rotation = "" if mode == 'expiry' else f"UPDATE workspaces SET sync_generation_id='{new_generation}' WHERE id='{workspace}' AND sync_generation_id='{generation}';"
    sql(f"BEGIN; SELECT last_sequence FROM sync_heads WHERE workspace_id='{workspace}' AND generation_id='{generation}' FOR UPDATE; DELETE FROM sync_changes WHERE workspace_id='{workspace}' AND generation_id='{generation}'; DELETE FROM sync_groups WHERE workspace_id='{workspace}' AND generation_id='{generation}'; UPDATE sync_heads SET {next_head} WHERE workspace_id='{workspace}' AND generation_id='{generation}'; {workspace_rotation} COMMIT;\n")
    run([adb, '-s', serial, 'shell', 'am', 'force-stop', 'com.capital.accounting'])
    native('verify' + suffix)
    after = proof(mode)
    assert after['stage'] == 'verified' and after['pid'] != before['pid']
    for key in ['recovered', 'command_preserved', 'exact_minor']:
        assert after[key] is True
    counts = json.loads(sql(f"SELECT json_build_object('actions',(SELECT count(*) FROM actions WHERE workspace_id='{workspace}'),'transactions',(SELECT count(*) FROM transactions WHERE workspace_id='{workspace}' AND deleted_at IS NULL),'balance',(SELECT sum(e.amount_minor)::text FROM entries e JOIN transactions t ON (t.workspace_id,t.id)=(e.workspace_id,e.transaction_id) WHERE e.workspace_id='{workspace}' AND t.status='posted' AND t.deleted_at IS NULL));\n"))
    assert counts == {'actions': 2, 'transactions': 2, 'balance': '99000'}
    after['sql_no_duplicate'] = True
    results[mode] = after
(checks / 'android-cursor-runtime.json').write_text(json.dumps(results))
print('PASS native cold cursor expiry and generation rotation recovery with unchanged command/SQL')
