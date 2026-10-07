#!/usr/bin/env python3
"""Drive a native Create during real API outage, cold restart and same-key retry."""
import json
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
def device(*args, check=True):
    return subprocess.run([adb, '-s', serial, *args], check=check, capture_output=True).stdout

def proof():
    try:
        return json.loads(device('exec-out', 'run-as', package, 'cat', 'files/financial-outage-proof.json', check=False))
    except (ValueError, json.JSONDecodeError):
        return None

def screen():
    return read_screen(device, '/sdcard/accounting-financial-ui.xml')

def find(text, seconds=45, scroll=False, row=False):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        matches = [n for n in screen().iter('node') if n.get('text') == text and (not row or n.get('class') != 'android.widget.EditText')]
        if matches:
            return matches[0]
        if scroll:
            device('shell', 'input', 'swipe', '500', '1500', '500', '500', '400')
        time.sleep(1)
    raise RuntimeError('Expected financial UI state missing; no credential/body logged')

def tap(text, scroll=False):
    node = find(text, scroll=scroll)
    assert node.get('enabled') == 'true'
    x1, y1, x2, y2 = [int(v) for v in re.findall(r'\d+', node.attrib['bounds'])]
    assert x2 > x1 and y2 > y1
    device('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def restart():
    device('shell', 'am', 'force-stop', package)
    device('shell', 'am', 'start', '-W', '-n', package+'/.MainActivity')

def sql(query):
    return subprocess.run(compose+['exec', '-T', 'db', 'sh', '-c', 'psql -U "$POSTGRES_USER" -d accounting_test -Atc "$1"', '_', query], check=True, capture_output=True, text=True).stdout.strip()

device('exec-out', 'run-as', package, 'rm', '-f', 'files/financial-outage-proof.json', 'files/financial-outage-gate')
artifact = root / 'ops/.runtime/checks/android-financial-outage-instrumentation.txt'
with artifact.open('w') as output:
    artifact.chmod(0o600)
    process = subprocess.Popen([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.capital.accounting.FinancialOutageTest', '-e', 'api_origin', 'https://localhost:8444', '-e', 'outage_harness', 'true', package+'.test/androidx.test.runner.AndroidJUnitRunner'], stdout=output, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            ready = proof()
            if ready and ready.get('stage') == 'ready':
                break
            if process.poll() is not None:
                raise RuntimeError('Financial instrumentation ended before host gate')
            time.sleep(1)
        else:
            raise RuntimeError('Financial fixture was not ready')
        subprocess.run(compose+['stop', 'api'], check=True)
        device('exec-out', 'run-as', package, 'touch', 'files/financial-outage-gate')
        process.wait(timeout=60)
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
result = artifact.read_text()
assert re.search(r'^OK \(1 test\)', result, re.M) and not re.search(r'INSTRUMENTATION_STATUS_CODE: -[1-4]', result)
data = proof()
assert data and data['stage'] == 'pending'
assert re.fullmatch(r'outage-[0-9a-f-]+@example\.test', data['email'])
for key in ['owner_id', 'workspace_id', 'session_id', 'command_id', 'account_id']:
    data[key] = str(uuid.UUID(data[key]))
account = data['account_id']
assert sql("SELECT count(*) FROM accounts WHERE id='"+account+"'") == '0'
restart()
find('Проверить сохраненную сессию')
subprocess.run(compose+['start', 'api'], check=True)
subprocess.run(['curl', '--noproxy', '*', '--fail', '--silent', '--show-error', '--cacert', 'ops/.runtime/tls/localhost.crt', '--retry', '10', '--retry-all-errors', '--retry-delay', '1', 'https://localhost:8444/health/ready'], check=True, stdout=subprocess.DEVNULL)
tap('Проверить сохраненную сессию')
find('Вы вошли: '+data['email'])
find('Сохраненная команда: '+data['command_id'], scroll=True)
tap('Повторить сохраненную команду', scroll=True)
# Message is at the top; restart to verify acknowledged queue is gone and server state reloads.
find('Outage pocket', scroll=True, row=True)
restart()
find('Вы вошли: '+data['email'])
tap('Обновить счета', scroll=True)
find('Outage pocket', scroll=True)
find('123,456 KWD', scroll=True)
query = "SELECT (SELECT count(*) FROM accounts WHERE id='"+account+"'), (SELECT count(*) FROM transactions WHERE opening_account_id='"+account+"'), (SELECT sum(amount_minor) FROM entries WHERE account_id='"+account+"'), (SELECT count(*) FROM actions WHERE actor_user_id='"+data['owner_id']+"' AND action_id='"+data['command_id']+"' AND state='applied')"
assert sql(query) == '1|1|123456|1'
assert sql("SELECT to_char(opened_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM accounts WHERE id='"+account+"'") == '2026-10-06T08:00:00'
assert sql("SELECT occurred_timezone FROM transactions WHERE opening_account_id='"+account+"'") == 'Europe/Nicosia'
# Native logout is enabled only after durable confirmation has been consumed.
restart()
find('Вы вошли: '+data['email'])
tap('Выйти')
find('Вы вышли из аккаунта')
data.update(cold_restart=True, original_key=True, exact_minor=True, original_opening=True, no_duplicate=True, queue_consumed=True)
output = root / 'ops/.runtime/checks/android-financial-runtime.json'
output.write_text(json.dumps(data, indent=2)+'\n')
output.chmod(0o600)
device('shell', 'rm', '/sdcard/accounting-financial-ui.xml')
print('PASS native financial outage, cold restart, same-key retry and actual SQL opening/balance')
