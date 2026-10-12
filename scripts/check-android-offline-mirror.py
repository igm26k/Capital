#!/usr/bin/env python3
"""Actual API outage/process restart and cached financial UI with denied-session cleanup."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
os.umask(0o077)
root = Path(__file__).resolve().parents[1]
adb, serial = sys.argv[1:3]
checks = root / 'ops/.runtime/checks'; checks.mkdir(parents=True, exist_ok=True)
compose = ['docker', 'compose', '--project-name', 'accounting-test', '-f', 'compose.yaml', '-f', 'compose.test.yaml', '-f', 'compose.e2e.yaml']
def run(args, body=None):
    return subprocess.run(args, input=body, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, check=True, cwd=root).stdout

def native(phase):
    output = run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', f'com.capital.accounting.OfflineMirrorRuntimeTest#{phase}', '-e', 'offline_host', 'true', '-e', 'offline_prepare', 'true' if phase == 'prepare' else 'false', '-e', 'api_origin', 'https://localhost:8444', 'com.capital.accounting.test/androidx.test.runner.AndroidJUnitRunner'])
    (checks / f'android-offline-mirror-{phase}-instrumentation.txt').write_text(output)
    if not re.search(r'^OK \(1 test\)', output, re.M) or re.search(r'INSTRUMENTATION_STATUS_CODE: -[1-4]', output): raise RuntimeError('Offline UI failed; inspect restricted artifact')

def proof():
    return json.loads(run([adb, '-s', serial, 'exec-out', 'run-as', 'com.capital.accounting', 'cat', 'files/offline-mirror-runtime.json']))

def stop(): run([adb, '-s', serial, 'shell', 'am', 'force-stop', 'com.capital.accounting'])
try:
    native('prepare'); initial = proof()
    run(compose + ['stop', 'api']); stop()
    native('offline'); offline = proof(); assert offline['cold_read'] is True and offline['pid'] != initial['pid']
    artifact = checks / 'android-offline-mirror-online-instrumentation.txt'
    with artifact.open('w') as log:
        process = subprocess.Popen([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.capital.accounting.OfflineMirrorRuntimeTest#online', '-e', 'offline_host', 'true', '-e', 'offline_prepare', 'false', '-e', 'api_origin', 'https://localhost:8444', 'com.capital.accounting.test/androidx.test.runner.AndroidJUnitRunner'], stdout=log, stderr=log, cwd=root)
        try:
            deadline = time.monotonic() + 40
            while proof().get('stage') != 'reconnect_ready':
                if process.poll() is not None or time.monotonic() >= deadline: raise RuntimeError('Native reconnect readiness failed')
                time.sleep(0.1)
            run(compose + ['up', '-d', '--wait', 'api'])
            run(['curl', '--noproxy', '*', '--fail', '--silent', '--show-error', '--cacert', 'ops/.runtime/tls/localhost.crt', '--retry', '10', '--retry-all-errors', '--retry-delay', '1', 'https://localhost:8444/health/ready'])
            run([adb, '-s', serial, 'shell', 'run-as', 'com.capital.accounting', 'sh', '-c', '"printf online > files/offline-host-online.txt"'])
            process.wait(timeout=60)
        finally:
            if process.poll() is None: process.terminate(); process.wait(timeout=10)
    output = artifact.read_text()
    assert re.search(r'^OK \(1 test\)', output, re.M) and not re.search(r'INSTRUMENTATION_STATUS_CODE: -[1-4]', output)
    online = proof(); assert online['online_recovered'] is True and online['raw_preserved'] is True
    for field in ['owner', 'workspace', 'session']: assert re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', online[field])
    query = f"UPDATE sessions SET revoked_at=clock_timestamp() WHERE id='{online['session']}' AND user_id='{online['owner']}';\n"
    run(compose + ['exec', '-T', 'db', 'sh', '-c', 'exec psql -X -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d accounting_test'], query)
    stop(); native('denied'); result = proof(); assert result['stage']=='verified'
    for flag in ['cold_read','online_recovered','raw_preserved','denied_hidden','no_commands']: assert result[flag] is True
    (checks / 'android-offline-mirror-runtime.json').write_text(json.dumps(result))
    print('PASS cold offline mirror UI, online pull, unchanged raw draft and actual401 hidden cache')
finally:
    run(compose + ['up', '-d', '--wait', 'api'])
