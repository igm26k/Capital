#!/usr/bin/env python3
"""Recover the Room enqueue-to-WorkManager gap through real cold UI restoration."""
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
    return subprocess.run(args, input=body, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90, check=True, cwd=root).stdout

def native(test, flag, artifact):
    output = run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test, '-e', flag, 'true', '-e', 'api_origin', 'https://localhost:8444', 'com.capital.accounting.test/androidx.test.runner.AndroidJUnitRunner'])
    (checks / artifact).write_text(output)
    if not re.search(r'^OK \(1 test\)', output, re.M) or re.search(r'INSTRUMENTATION_STATUS_CODE: -[1-4]', output):
        raise RuntimeError('Native queue startup failed; inspect restricted artifact')

def proof():
    return json.loads(run([adb, '-s', serial, 'exec-out', 'run-as', 'com.capital.accounting', 'cat', 'files/queue-worker-runtime.json']))

native('com.capital.accounting.QueueWorkerRuntimeTest#prepare', 'queue_worker_host', 'android-queue-startup-prepare-instrumentation.txt')
before = proof()
assert before['stage'] == 'prepared'
assert re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', before['workspace'])
run([adb, '-s', serial, 'shell', 'am', 'force-stop', 'com.capital.accounting'])
native('com.capital.accounting.QueueStartupRuntimeTest', 'queue_startup_host', 'android-queue-startup-recover-instrumentation.txt')
after = proof()
assert after['stage'] == 'startup_verified' and after['pid'] != before['pid']
for key in ['actions', 'hashes', 'workspace', 'owner', 'generation']:
    assert after[key] == before[key]
for key in ['missing_job_recovered', 'command_preserved', 'pull_covered']:
    assert after[key] is True
w = before['workspace']
counts = json.loads(run(compose + ['exec', '-T', 'db', 'sh', '-c', 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d accounting_test'], f"SELECT json_build_object('actions',(SELECT count(*) FROM actions WHERE workspace_id='{w}'),'tags',(SELECT count(*) FROM tags WHERE workspace_id='{w}'),'head',(SELECT last_sequence FROM sync_heads WHERE workspace_id='{w}'));\n").strip())
assert counts == {'actions': 2, 'tags': 2, 'head': 2}
after['sql_no_duplicate'] = True
(checks / 'android-queue-startup-runtime.json').write_text(json.dumps(after))
print('PASS cold UI startup recovers unscheduled Room queue with original commands and SQL no duplicate')
