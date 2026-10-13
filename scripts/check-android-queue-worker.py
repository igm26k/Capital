#!/usr/bin/env python3
"""Dedicated API outage, WorkManager backoff and cold original-job recovery."""
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
def run(args, body=None, timeout=60):
    return subprocess.run(args, input=body, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=True, cwd=root).stdout

def native(phase):
    output = run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', f'com.capital.accounting.QueueWorkerRuntimeTest#{phase}', '-e', 'queue_worker_host', 'true', '-e', 'api_origin', 'https://localhost:8444', 'com.capital.accounting.test/androidx.test.runner.AndroidJUnitRunner'], timeout=120)
    (checks / f'android-queue-worker-{phase}-instrumentation.txt').write_text(output)
    if not re.search(r'^OK \(1 test\)', output, re.M) or re.search(r'INSTRUMENTATION_STATUS_CODE: -[1-4]', output):
        raise RuntimeError('Native queue worker failed; inspect restricted artifact')

def proof():
    return json.loads(run([adb, '-s', serial, 'exec-out', 'run-as', 'com.capital.accounting', 'cat', 'files/queue-worker-runtime.json']))

def sql(query):
    return run(compose + ['exec', '-T', 'db', 'sh', '-c', 'exec psql -X -q -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d accounting_test'], query).strip()

def ready():
    run(compose + ['up', '-d', 'api'])
    run(['curl', '--noproxy', '*', '--fail', '--silent', '--show-error', '--cacert', 'ops/.runtime/tls/localhost.crt', '--retry', '10', '--retry-all-errors', '--retry-delay', '1', 'https://localhost:8444/health/ready'])

native('prepare')
before = proof()
assert before['stage'] == 'prepared'
for value in [before['workspace'], before['owner'], before['generation'], *before['actions']]:
    assert re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', value)
w = before['workspace']
try:
    run(compose + ['stop', 'api'])
    native('outage')
    retried = proof()
    assert retried['stage'] == 'retrying' and retried['backoff'] is True and retried['attempts'] >= 1
    assert sql(f"SELECT count(*) FROM actions WHERE workspace_id='{w}';\n") == '0'
    run([adb, '-s', serial, 'shell', 'am', 'force-stop', 'com.capital.accounting'])
finally:
    ready()
native('recover')
after = proof()
assert after['stage'] == 'verified' and after['pid'] != retried['pid'] and after['work'] == retried['work']
for key in ['workspace', 'owner', 'generation', 'actions', 'hashes']:
    assert after[key] == before[key]
for key in ['backoff', 'original_work', 'command_preserved', 'pull_covered']:
    assert after[key] is True
counts = json.loads(sql(f"SELECT json_build_object('actions',(SELECT count(*) FROM actions WHERE workspace_id='{w}'),'groups',(SELECT count(*) FROM sync_groups WHERE workspace_id='{w}'),'tags',(SELECT count(*) FROM tags WHERE workspace_id='{w}'),'head',(SELECT last_sequence FROM sync_heads WHERE workspace_id='{w}'));\n"))
assert counts == {'actions': 2, 'groups': 2, 'tags': 2, 'head': 2}
after['sql_no_duplicate'] = True
(checks / 'android-queue-worker-runtime.json').write_text(json.dumps(after))
print('PASS actual worker outage/backoff/cold original WorkSpec recovery and SQL no duplicate')
