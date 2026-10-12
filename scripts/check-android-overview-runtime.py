#!/usr/bin/env python3
"""Exercise readonly overview failure/retry against the real dedicated API."""
import json
import re
import subprocess
import sys
import time
from pathlib import Path

adb, serial = sys.argv[1:3]
root = Path(__file__).resolve().parents[1]
package = 'com.capital.accounting'
compose = ['docker', 'compose', '--project-name', 'accounting-test', '-f', 'compose.yaml', '-f', 'compose.test.yaml', '-f', 'compose.e2e.yaml']

def device(*args, input=None, check=True):
    return subprocess.run([adb, '-s', serial, *args], input=input, check=check, capture_output=True).stdout

def read_proof():
    try:
        return json.loads(device('exec-out', 'run-as', package, 'cat', 'files/overview-outage-proof.json', check=False))
    except (ValueError, json.JSONDecodeError):
        return {}

def wait_stage(stage, process):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        data = read_proof()
        if data.get('stage') == stage:
            return data
        if process.poll() is not None:
            raise AssertionError('Native overview test ended before expected stage; inspect restricted artifact')
        time.sleep(0.2)
    raise AssertionError('Native overview stage timed out; inspect restricted artifact')

artifact = root / 'ops/.runtime/checks/android-overview-outage-instrumentation.txt'
device('shell', 'run-as', package, 'rm', '-f', 'files/overview-outage-proof.json', 'files/overview-host-state.txt')
with artifact.open('w') as output:
    artifact.chmod(0o600)
    process = subprocess.Popen([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', package+'.InitialOverviewRuntimeTest', '-e', 'api_origin', 'https://localhost:8444', '-e', 'overview_host', 'true', package+'.test/androidx.test.runner.AndroidJUnitRunner'], stdout=output, stderr=subprocess.STDOUT)
    stopped = False
    try:
        wait_stage('ready', process)
        subprocess.run(compose + ['stop', 'api'], check=True, stdout=subprocess.DEVNULL)
        stopped = True
        device('shell', 'run-as', package, 'sh', '-c', "'printf %s offline > files/overview-host-state.txt'")
        wait_stage('offline_verified', process)
        subprocess.run(compose + ['up', '-d', 'api'], check=True, stdout=subprocess.DEVNULL)
        stopped = False
        subprocess.run(['curl', '--noproxy', '*', '--fail', '--silent', '--show-error', '--cacert', 'ops/.runtime/tls/localhost.crt', '--retry', '20', '--retry-all-errors', '--retry-delay', '1', 'https://localhost:8444/health/ready'], check=True, stdout=subprocess.DEVNULL)
        device('shell', 'run-as', package, 'sh', '-c', "'printf %s online > files/overview-host-state.txt'")
        data = wait_stage('verified', process)
        assert process.wait(timeout=60) == 0
    finally:
        if stopped:
            subprocess.run(compose + ['up', '-d', 'api'], check=True, stdout=subprocess.DEVNULL)
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=30)
text = artifact.read_text()
assert re.search(r'^OK \(1 test\)', text, re.M) and not re.search(r'INSTRUMENTATION_STATUS_CODE: -[1-4]', text)
assert all(data[key] for key in ['cache_retained', 'raw_version_retained', 'readonly_retry', 'no_commands', 'no_extra_transactions'])
proof = root / 'ops/.runtime/checks/android-overview-runtime.json'
proof.write_text(json.dumps(data, indent=2)+'\n')
proof.chmod(0o600)
print('PASS native overview outage, retained cache/raw versions and explicit readonly retry')
