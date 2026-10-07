#!/usr/bin/env python3
"""Check real process restart + persisted logout intent against the running test API."""
import json
import os
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path
sys.dont_write_bytecode = True
from android_ui import read_screen
adb, serial = sys.argv[1:3]
root = Path(__file__).resolve().parents[1]
package = 'com.capital.accounting'
compose = ['docker', 'compose', '--project-name', 'accounting-test', '-f', 'compose.yaml', '-f', 'compose.test.yaml', '-f', 'compose.e2e.yaml']
def device(*args):
    return subprocess.run([adb, '-s', serial, *args], check=True, capture_output=True).stdout
proof = json.loads(device('exec-out', 'run-as', package, 'cat', 'files/android-auth-proof.json'))
assert uuid.UUID(proof['session_id']).version is not None
assert uuid.UUID(proof['revoke_session_id']).version is not None
assert re.fullmatch(r'android-ui-[0-9a-f-]+@example\.test', proof['email'])
def screen():
    return read_screen(device, '/sdcard/accounting-auth-ui.xml')
def wait_text(text, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        tree = screen()
        matches = [node for node in tree.iter('node') if node.get('text') == text]
        if matches: return matches[0]
        time.sleep(1)
    raise RuntimeError('Expected auth UI state was not reached; no credential/body logged')
def tap(text, scroll=False):
    if scroll:
        for _ in range(8):
            matches = [node for node in screen().iter('node') if node.get('text') == text and node.get('enabled') == 'true']
            if matches: break
            device('shell', 'input', 'swipe', '500', '1500', '500', '500', '400')
        else: raise RuntimeError('Device action not visible')
    node = wait_text(text)
    bounds = [int(x) for x in re.findall(r'\d+', node.attrib['bounds'])]
    device('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
def restart():
    device('shell', 'am', 'force-stop', package)
    device('shell', 'am', 'start', '-W', '-n', package+'/.MainActivity')
restart()
wait_text('Вы вошли: '+proof['email'])
tap('Обновить устройства', scroll=True)
# Save the exact target before a real outage; cold restart must retain it.
time.sleep(2)
subprocess.run(compose+['stop', 'api'], check=True)
tap('Отключить устройство Runtime device', scroll=True)
wait_text('Повторить отзыв устройства')
restart()
wait_text('Повторить отзыв устройства')
subprocess.run(compose+['start', 'api'], check=True)
subprocess.run(['curl', '--noproxy', '*', '--fail', '--silent', '--show-error', '--cacert', 'ops/.runtime/tls/localhost.crt', '--retry', '10', '--retry-all-errors', '--retry-delay', '1', 'https://localhost:8444/health/ready'], check=True, stdout=subprocess.DEVNULL)
tap('Повторить отзыв устройства')
wait_text('Устройство отключено')
wait_text('Вы вошли: '+proof['email'])
sql = "SELECT (revoked_at IS NOT NULL)::text FROM sessions WHERE id='"+str(uuid.UUID(proof['revoke_session_id']))+"'"
result = subprocess.run(compose+['exec', '-T', 'db', 'sh', '-c', 'psql -U "$POSTGRES_USER" -d accounting_test -Atc "$1"', '_', sql], check=True, capture_output=True, text=True)
assert result.stdout.strip() == 'true'
proof.update(durable_revoke_intent=True, revoked_other_sql=True, primary_preserved=True)
# Server outage is real: save logout intent, fail its request, restart before confirming it.
subprocess.run(compose+['stop', 'api'], check=True)
tap('Выйти', scroll=True)
wait_text('Повторить выход')
restart()
wait_text('Повторить выход')
subprocess.run(compose+['start', 'api'], check=True)
subprocess.run(['curl', '--noproxy', '*', '--fail', '--silent', '--show-error', '--cacert', 'ops/.runtime/tls/localhost.crt', '--retry', '10', '--retry-connrefused', '--retry-delay', '1', '--retry-all-errors', 'https://localhost:8444/health/ready'], check=True, stdout=subprocess.DEVNULL)
tap('Повторить выход')
wait_text('Вы вышли из аккаунта')
# Inspect the actual session row; UUID is validated above, never interpolate credentials.
sql = "SELECT (revoked_at IS NOT NULL)::text, (SELECT count(*) FROM sessions other WHERE other.user_id=sessions.user_id) FROM sessions WHERE id='"+str(uuid.UUID(proof['session_id']))+"'"
result = subprocess.run(compose+['exec', '-T', 'db', 'sh', '-c', 'psql -U "$POSTGRES_USER" -d accounting_test -Atc "$1"', '_', sql], check=True, capture_output=True, text=True)
assert result.stdout.strip() == 'true|3'
restart()
wait_text('Войти')
remaining = device('exec-out', 'run-as', package, 'ls', '-1', 'no_backup').decode().splitlines()
assert not any(name in remaining for name in ['bearer-session-v1', 'bearer-session-v1.bak', 'bearer-session-v1.new'])
proof.update(process_restart=True, durable_logout_intent=True, revoked_sql=True, no_extra_sessions=True, credential_cleared=True)
output = root / 'ops/.runtime/checks/android-auth-runtime.json'
output.write_text(json.dumps(proof, indent=2)+'\n')
output.chmod(0o600)
device('shell', 'rm', '/sdcard/accounting-auth-ui.xml')
print('PASS real Android process restart, outage/logout intent, SQL revoke and credential cleanup')
