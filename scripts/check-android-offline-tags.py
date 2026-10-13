#!/usr/bin/env python3
"""Offline catalog UI create/update/archive, cold sync and isolated SQL no-duplicate acceptance."""
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
compose = ['docker', 'compose', '--project-name', 'accounting-test', '-f', 'compose.yaml', '-f', 'compose.test.yaml', '-f', 'compose.e2e.yaml']
def run(args, body=None):
    return subprocess.run(args, input=body, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120, check=True, cwd=root).stdout

def native(test, artifact):
    output = run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test, '-e', 'offline_tag_host', 'true', '-e', 'api_origin', 'https://localhost:8444', 'com.capital.accounting.test/androidx.test.runner.AndroidJUnitRunner'])
    (checks / artifact).write_text(output)
    if not re.search(r'^OK \(1 test\)', output, re.M) or re.search(r'INSTRUMENTATION_STATUS_CODE: -[1-4]', output):
        raise RuntimeError('Native offline catalog failed; inspect restricted artifact')

def proof():
    return json.loads(run([adb, '-s', serial, 'exec-out', 'run-as', 'com.capital.accounting', 'cat', 'files/offline-tag-runtime.json']))

def sql(query):
    return run(compose + ['exec', '-T', 'db', 'sh', '-c', 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d accounting_test'], query).strip()

native('com.capital.accounting.OfflineTagPrepareTest', 'android-offline-tags-prepare-instrumentation.txt')
before = proof(); assert before['stage'] == 'prepared'
for key in ['owner', 'workspace', 'generation', 'tag']:
    assert re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', before[key])
w = before['workspace']
try:
    run(compose + ['stop', 'api']); run([adb, '-s', serial, 'shell', 'am', 'force-stop', 'com.capital.accounting'])
    native('com.capital.accounting.OfflineTagRuntimeTest#offlineCreateEditArchive', 'android-offline-tags-offline-instrumentation.txt')
    queued = proof(); assert queued['stage'] == 'queued' and queued['confirmed_preserved'] and queued['local_ui']
    assert sql(f"SELECT count(*) FROM actions WHERE workspace_id='{w}';\n") == '1'
    run([adb, '-s', serial, 'shell', 'am', 'force-stop', 'com.capital.accounting'])
finally:
    run(compose + ['up', '-d', 'api'])
    run(['curl', '--noproxy', '*', '--fail', '--silent', '--show-error', '--cacert', 'ops/.runtime/tls/localhost.crt', '--retry', '10', '--retry-all-errors', '--retry-delay', '1', 'https://localhost:8444/health/ready'])
native('com.capital.accounting.OfflineTagRuntimeTest#onlineColdRecover', 'android-offline-tags-online-instrumentation.txt')
after = proof(); assert after['stage'] == 'verified' and after['pid'] != queued['pid']
for key in ['actions', 'hashes']:
    assert after[key] == queued[key]
for key in ['local_ui', 'confirmed_preserved', 'cold_recovered', 'command_preserved', 'pull_covered']:
    assert after[key] is True
counts = json.loads(sql(f"SELECT json_build_object('actions',(SELECT count(*) FROM actions WHERE workspace_id='{w}'),'groups',(SELECT count(*) FROM sync_groups WHERE workspace_id='{w}'),'tags',(SELECT count(*) FROM tags WHERE workspace_id='{w}'),'archived',(SELECT count(*) FROM tags WHERE workspace_id='{w}' AND archived_at IS NOT NULL),'categories',(SELECT count(*) FROM categories WHERE workspace_id='{w}'),'archived_categories',(SELECT count(*) FROM categories WHERE workspace_id='{w}' AND archived_at IS NOT NULL),'head',(SELECT last_sequence FROM sync_heads WHERE workspace_id='{w}'));\n"))
assert counts == {'actions': 9, 'groups': 9, 'tags': 3, 'archived': 2, 'categories': 2, 'archived_categories': 1, 'head': 9}
after['sql_no_duplicate'] = True
(checks / 'android-offline-tags-runtime.json').write_text(json.dumps(after))
print('PASS offline catalog UI create/update/archive, cold sync, original commands and SQL no duplicate')
