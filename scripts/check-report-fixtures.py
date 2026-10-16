#!/usr/bin/env python3
"""Independent integer arithmetic oracle for the PostgreSQL reporting acceptance."""
import json
from datetime import datetime
from pathlib import Path
root = Path(__file__).resolve().parents[1]
fixture = json.loads((root / 'tests/fixtures/reports/control.json').read_text())
start = datetime.fromisoformat(fixture['period']['from'])
end = datetime.fromisoformat(fixture['period']['to'])
for currency, expected in fixture['expected'].items():
    actual = dict.fromkeys(expected, 0)
    for row in fixture['rows']:
        if row['currency'] != currency:
            continue
        at = datetime.fromisoformat(row['date'])
        movement = sum(row['entries'])
        if at < end:
            actual['projected'] += movement
            if row['status'] == 'posted':
                actual['closing'] += movement
                if at < start:
                    actual['opening'] += movement
        if start <= at < end:
            if row['kind'] == 'income':
                actual['income'] += movement
            if row['kind'] in {'expense', 'refund'}:
                amount = -movement
                actual['expense'] += amount
                actual[f"{row['status']}_expense"] += amount
        if 'parts' in row:
            assert sum(row['parts']) == abs(movement)
    assert actual == expected, (currency, actual, expected)
print('PASS independent reporting arithmetic: exact currencies, pending, refund, transfer, splits and boundaries')
