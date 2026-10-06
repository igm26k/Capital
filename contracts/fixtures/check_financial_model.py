"""Check independently specified arithmetic. This does not test an API or DB."""
import json
from fractions import Fraction
from pathlib import Path

LIMIT = 9_000_000_000_000_000


def check():
    fixture = json.loads(Path(__file__).with_name('financial-model.json').read_text())
    identifiers = set()
    for case in fixture['cases']:
        assert case['id'] not in identifiers, case['id']
        identifiers.add(case['id'])
        balances = {key: int(value) for key, value in case['initial'].items()}
        for account, value in case['movements']:
            balances[account] = balances.get(account, 0) + int(value)
        assert balances == {key: int(value) for key, value in case['expected_balances'].items()}, case['id']
        assert all(abs(value) <= LIMIT for value in balances.values()), case['id']
        expenses = {key: sum(map(int, parts)) for key, parts in case['expenses'].items()}
        assert expenses == {key: int(value) for key, value in case['expected_expenses'].items()}, case['id']
        if 'fx' in case:
            fx = case['fx']
            ratio = Fraction(int(fx['target_minor']) * 10 ** fx['source_scale'],
                             int(fx['source_minor']) * 10 ** fx['target_scale'])
            assert [str(ratio.numerator), str(ratio.denominator)] == fx['expected_ratio'], case['id']
    # Paper counterexample: two independent refunds would exceed the allocation.
    remaining = 4000 - 1500
    assert 2000 <= remaining and 2000 + 2000 > remaining
    # These rejection descriptions are specifications, not executed service tests.
    assert len({case['id'] for case in fixture['rejection_scenarios']}) == 10
    print(f"PASS: {len(identifiers)} arithmetic cases, 2 exact FX ratios, concurrent-refund arithmetic")
    print('NOT EXECUTED: 10 rejection scenarios require the future service and PostgreSQL')


if __name__ == '__main__':
    check()
