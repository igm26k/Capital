#!/usr/bin/env python3
"""Validate synthetic PostgreSQL integration responses against the unchanged OpenAPI."""
import json
import sys
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

root = Path(__file__).resolve().parents[1]
contract = json.loads((root / 'contracts/openapi.json').read_text())
response_file = Path(sys.argv[1]) if len(sys.argv) == 2 else root / 'ops/.runtime/checks/ledger-responses.json'
observed = json.loads(response_file.read_text())
assert observed, 'No integration responses captured'
validators = {}
for index, item in enumerate(observed):
    name = item['schema']
    if name not in validators:
        validators[name] = Draft202012Validator(
            {'$ref': '#/components/schemas/' + name, 'components': contract['components']},
            format_checker=FormatChecker())
    errors = list(validators[name].iter_errors(item['body']))
    # Output paths/validator names only; never print financial values or request bodies.
    if errors:
        raise SystemExit(f'Response {index} {name}: ' + ', '.join(
            f'{list(e.absolute_path)} ({e.validator})' for e in errors[:3]))
print(f'PASS {len(observed)} real HTTP responses against OpenAPI ({len(validators)} schemas)')
