#!/usr/bin/env python3
"""Validate rendered Compose without exposing interpolated secrets."""
import json
import os
import subprocess
import sys

cli = sys.argv[1:] or ['docker', 'compose']
env = dict(os.environ, REGISTRATION_ENABLED='false', POSTGRES_PASSWORD='synthetic_validation_only',
           ACCOUNTING_DEV_SUBNET='192.168.242.0/24', ACCOUNTING_TEST_SUBNET='192.168.243.0/24',
           PRODUCTION_DATABASE_URL='postgres://accounting:synthetic@db:5432/accounting?sslmode=verify-full&sslrootcert=/etc/accounting/db-tls/ca.crt',
           PRODUCTION_PUBLIC_ORIGIN='https://accounting.example',
           PRODUCTION_DB_TLS_DIR='/tmp/db-tls', PRODUCTION_DB_CA_DIR='/tmp/db-ca',
           PRODUCTION_WEB_TLS_DIR='/tmp/web-tls', PRODUCTION_HTTPS_BIND='127.0.0.1')

def render(project, overlay=None):
    args = cli + ['--project-name', project, '-f', 'compose.yaml']
    if overlay:
        for filename in ([overlay] if isinstance(overlay, str) else overlay):
            args += ['-f', filename]
    result = subprocess.run(args + ['config', '--format', 'json'], env=env,
                            capture_output=True, text=True)
    if result.returncode:
        raise SystemExit('Compose validation failed; check CLI/version and configuration')
    return json.loads(result.stdout)

for overlay in [None, 'compose.dev.yaml', 'compose.test.yaml', 'compose.prod.yaml']:
    cfg = render('accounting-local', overlay)
    services = cfg['services']
    assert not services['db'].get('ports')
    for service in ['api', 'worker']:
        assert services[service]['depends_on']['migrate']['condition'] == 'service_completed_successfully'
    assert services['migrate']['depends_on']['db']['condition'] == 'service_healthy'
    if overlay == 'compose.dev.yaml':
        assert cfg['networks']['private']['ipam']['config'][0]['subnet'] == env.get('ACCOUNTING_DEV_SUBNET', '192.168.240.0/24')
    if overlay == 'compose.test.yaml':
        assert cfg['networks']['private']['ipam']['config'][0]['subnet'] == env.get('ACCOUNTING_TEST_SUBNET', '192.168.241.0/24')
        assert services['web']['ports'][0]['published'] == '8444'
        assert services['api']['environment']['APP_ENV'] == 'test'
    if overlay == 'compose.prod.yaml':
        assert services['api']['environment']['APP_ENV'] == 'production'
        assert 'verify-full' in services['api']['environment']['DATABASE_URL']
    print('PASS Compose:', overlay or 'base')
assert render('accounting-local')['volumes']['db-data']['name'] != render('accounting-test', 'compose.test.yaml')['volumes']['db-data']['name']
print('PASS local/test volume isolation')

e2e = render('accounting-test', ['compose.test.yaml', 'compose.e2e.yaml'])
assert e2e['services']['api']['environment']['APP_ENV'] == 'test'
assert e2e['services']['api']['environment']['REGISTRATION_ENABLED'] == 'true'
assert render('accounting-local', 'compose.dev.yaml')['services']['api']['environment']['REGISTRATION_ENABLED'] == 'false'
print('PASS explicit E2E registration / local registration disabled')
