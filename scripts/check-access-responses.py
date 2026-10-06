#!/usr/bin/env python3
"""Check real API route coverage alongside the separate OpenAPI body validators."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
contract = json.loads((root / 'contracts/openapi.json').read_text())
responses = json.loads((root / 'ops/.runtime/checks/access-responses.json').read_text())
expected = {(method.upper(), path) for path, operations in contract['paths'].items()
            for method in operations if method in {'get', 'post', 'put', 'delete'}}
seen = {(item['method'], item['path']) for item in responses}
missing = expected - seen
assert not missing, f'Untested API operations: {sorted(missing)}'
public = {('POST', '/auth/register'), ('POST', '/auth/login')}
unauthenticated = {(item['method'], item['path']) for item in responses
                   if item['status'] == 401 and item['body'].get('code') == 'unauthenticated'}
missing_guards = (expected - public) - unauthenticated
assert not missing_guards, f'No actual 401 guard check: {sorted(missing_guards)}'
for item in responses:
    if item['status'] in {401, 403, 404}:
        assert item['body']['current_versions'] == []
        assert item['body']['field_errors'] == []
print(f'PASS {len(expected)} API operations; all {len(expected - public)} private guards and empty unauthorized metadata')
