"""Validate contract structure and schema fixtures, without claiming service tests."""
import json
import re
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker, RefResolver

ROOT = Path(__file__).resolve().parent


def load(path):
    return json.loads(path.read_text())


def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def pointer(doc, fragment):
    value = doc
    for part in fragment.lstrip('/').split('/'):
        value = value[part.replace('~1', '/').replace('~0', '~')]
    return value


def validator(schema, doc, path):
    return Draft202012Validator(schema, resolver=RefResolver(path.as_uri(), doc), format_checker=FormatChecker())


def check_document(doc, path, meta):
    Draft202012Validator(meta).validate(doc)
    for node in walk(doc):
        if '$ref' in node:
            target = node['$ref']
            assert target.startswith('#/'), f'Unexpected external ref: {target}'
            pointer(doc, target[1:])
    for schema in doc['components']['schemas'].values():
        Draft202012Validator.check_schema(schema)
    operations = set()
    for route, item in doc['paths'].items():
        for method, operation in item.items():
            if method not in {'get', 'put', 'post', 'delete', 'patch'}:
                continue
            assert operation['operationId'] not in operations
            operations.add(operation['operationId'])
            parameters = [pointer(doc, p['$ref'][1:]) if '$ref' in p else p for p in operation.get('parameters', [])]
            required_paths = {p['name'] for p in parameters if p['in'] == 'path' and p['required']}
            assert required_paths == set(re.findall(r'\{([^}]+)\}', route)), route
            if route.startswith('/workspaces/{workspace_id}') and method != 'get':
                assert any(p['name'] == 'Idempotency-Key' and p['required'] for p in parameters), route
                assert {'sessionCookie': [], 'csrfToken': []} in operation['security'], route
                if '/sync/snapshots' not in route:
                    assert any(p['name'] == 'X-Sync-Generation' and p['required'] for p in parameters), route
            if 'requestBody' in operation:
                schema = operation['requestBody']['content']['application/json']['schema']
                validator(schema, doc, path).check_schema(schema)
    return len(operations)


def check_group(group):
    changes = group['changes']
    assert [event['ordinal'] for event in changes] == list(range(1, len(changes) + 1)), 'Noncontiguous ordinals'
    assert len({event['change_id'] for event in changes}) == len(changes), 'Duplicate change id'
    assert len({(event['entity_type'], event['data']['id']) for event in changes}) == len(changes), 'Duplicate aggregate'
    for event in changes:
        assert event['group_size'] == len(changes), 'Incomplete group'
        for key in ['workspace_id', 'action_id', 'sequence', 'generation_id']:
            assert event[key] == group[key], f'Mixed {key}'
        assert event['data']['workspace_id'] == group['workspace_id'], 'Foreign payload'
        if event['operation'] == 'delete':
            assert event['entity_type'] == event['data']['entity_type']


def check_page(page, input_sequence):
    position = int(input_sequence)
    assert position >= int(page['min_available_sequence']) - 1, 'Expired input cursor'
    for group in page['groups']:
        position += 1
        assert int(group['sequence']) == position, 'Gap in committed groups'
        assert group['workspace_id'] == page['workspace_id']
        assert group['generation_id'] == page['generation_id'], 'Mixed page generation'
        check_group(group)
    head = int(page['head_sequence'])
    assert position <= head, 'Cursor after head'
    assert page['has_more'] == (position < head), 'Wrong has_more'
    return position


def check_snapshot(data):
    metadata = data['metadata']
    pages = data['pages']
    assert pages, 'Snapshot has no final page'
    assert pages[-1]['next_page_token'] is None, 'Incomplete snapshot'
    identifiers = set()
    for index, page in enumerate(pages):
        assert page['snapshot_id'] == metadata['id']
        for key in ['workspace_id', 'base_sequence', 'generation_id']:
            assert page[key] == metadata[key], f'Mixed snapshot {key}'
        if index < len(pages) - 1:
            assert page['next_page_token'] is not None, 'Early final page'
        for item in page['items']:
            assert item['data']['workspace_id'] == metadata['workspace_id']
            assert item['data']['deleted_at'] is None, 'Deleted entity in snapshot'
            identifier = (item['entity_type'], item['data']['id'])
            assert identifier not in identifiers, 'Duplicate snapshot entity'
            identifiers.add(identifier)
    assert len(identifiers) == metadata['item_count'], 'Snapshot item count mismatch'


def check_receipt(receipt):
    applied = receipt['state'] == 'applied'
    assert (receipt['group_sequence'] is not None) == applied, 'Wrong receipt group'
    assert (receipt['generation_id'] is not None) == applied, 'Wrong receipt generation'
    assert (200 <= receipt['original_http_status'] < 300) == applied, 'Wrong receipt status'
    if not applied:
        assert not receipt['entity_refs'], 'Rejected receipt changed entities'
    body = receipt['response_body']
    if applied and body is not None:
        assert body['action_id'] == receipt['action_id']
        assert body['sync_group_sequence'] == receipt['group_sequence']
        assert body['generation_id'] == receipt['generation_id']


def check_sync(core, core_path):
    fixture = load(ROOT / 'fixtures/sync-examples.json')
    for sample in fixture['valid']:
        validator(core['components']['schemas'][sample['schema']], core, core_path).validate(sample['data'])
        if sample['schema'] == 'SyncGroup':
            check_group(sample['data'])
        if sample['schema'] == 'ActionOutcome':
            check_receipt(sample['data'])
    for sample in fixture['invalid']:
        assert list(validator(core['components']['schemas'][sample['schema']], core, core_path).iter_errors(sample['data'])), sample['id']
    for sample in fixture['semantic_invalid']:
        try:
            if sample['kind'] == 'group':
                check_group(sample['data'])
            elif sample['kind'] == 'receipt':
                check_receipt(sample['data'])
            elif sample['kind'] == 'page':
                check_page(sample['data'], sample['input_sequence'])
            elif sample['kind'] == 'snapshot':
                check_snapshot(sample['data'])
            else:
                raise ValueError(sample['kind'])
        except AssertionError:
            pass
        else:
            raise AssertionError(f'Invalid sync semantics accepted: {sample["id"]}')
    page = next(sample['data'] for sample in fixture['valid'] if sample['id'] == 'changes_page')
    assert check_page(page, fixture['valid_page_input_sequence']) == 6
    check_snapshot(fixture['valid_snapshot'])
    for case in fixture['retention_cases']:
        actual = 'cursor_invalid' if case['cursor'] > case['head'] else 'cursor_expired' if case['cursor'] < case['minimum'] - 1 else 'ok'
        assert actual == case['expected'], case['id']
    # A reference schedule validates the specification, not PostgreSQL locking.
    for trace in fixture['commit_traces']:
        holder = None
        committed = []
        for operation, actor in trace['steps']:
            if operation == 'acquire':
                assert holder is None
                holder = actor
            elif operation == 'blocked':
                assert holder is not None and holder != actor
            elif operation in {'commit', 'abort', 'reject'}:
                assert holder == actor
                if operation == 'commit':
                    committed.append(len(committed) + 1)
                holder = None
            else:
                raise ValueError(operation)
        assert committed == trace['expected_sequences'], trace['id']
    unsafe = fixture['unsafe_commit_order']
    committed = [unsafe['allocated'][actor] for actor in unsafe['commit_order']]
    assert (committed == sorted(committed)) == unsafe['expected_safe']
    for case in fixture['receipt_cases']:
        if not case['authorized'] or not case['same_actor'] or not case['exists']:
            actual = 'not_found'
        elif not case['same_hash']:
            actual = 'idempotency_conflict'
        elif case['days'] >= 30:
            actual = 'action_result_expired'
        else:
            actual = 'replay_' + case['state']
        assert actual == case['expected'], case['id']
    sizing = fixture['page_sizes']
    indices = []
    used = 0
    for index, size in enumerate(sizing['group_sizes']):
        if len(indices) == sizing['limit'] or used + size > sizing['byte_budget']:
            break
        indices.append(index)
        used += size
    assert indices == sizing['expected_first_indices']
    assert len(indices) == sizing['expected_last_sequence']
    recovery = fixture['bootstrap_recovery']
    restored_mirror = dict(recovery['snapshot_mirror'])
    # The outbox is a separate store, not the data replaced by the snapshot.
    retained_outbox = json.loads(json.dumps(recovery['outbox']))
    assert sorted(restored_mirror) == recovery['expected_mirror_keys']
    assert [item['action_id'] for item in retained_outbox] == recovery['expected_outbox_ids']
    assert retained_outbox == recovery['outbox']
    assert [seq for seq in recovery['later_groups'] if seq > recovery['base_sequence']] == recovery['expected_pull_sequences']
    for case in fixture['version_cases']:
        old, new = int(case['current_version']), int(case['incoming_version'])
        actual = 'ignore' if new < old else 'apply' if new > old else 'unchanged' if case['same_payload'] else 'protocol_error'
        assert actual == case['expected'], case['id']
    for case in fixture['overlay_cases']:
        actual = 'clear_overlay' if case['mirror_sequence'] >= case['applied_sequence'] else 'retain_overlay'
        assert actual == case['expected'], case['id']
    # Command canonicalization examples are independently authored text pairs.
    import hashlib
    def reject_duplicate_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result
    def command_hash(case, field):
        body = json.loads(case[field], object_pairs_hook=reject_duplicate_keys)
        canonical = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
        generation = case['left_generation'] if field == 'left' else case['right_generation']
        return hashlib.sha256((case['method'] + '\n' + case['path'] + '\n' + generation + '\n' + canonical).encode('utf-8')).digest()
    for case in fixture['canonical_cases']:
        assert (command_hash(case, 'left') == command_hash(case, 'right')) == case['expected_same'], case['id']
    try:
        json.loads(fixture['duplicate_keys_json'], object_pairs_hook=reject_duplicate_keys)
    except ValueError:
        pass
    else:
        raise AssertionError('Duplicate JSON keys accepted')
    print(f'PASS: sync schemas ({len(fixture["valid"])} valid, {len(fixture["invalid"])} rejected), {len(fixture["semantic_invalid"])} protocol rejections')
    print('PASS: group completeness, snapshot completeness, retention boundaries, receipt expiry, commit schedules, outbox/overlay recovery, stale versions and command hashes')


def check_design_review(core, core_path, future, future_path):
    fixture = load(ROOT / 'fixtures/design-review-cases.json')
    scenarios = fixture['integration_cases']
    identifiers = set()
    codes = set(core['components']['schemas']['Error']['properties']['code']['enum'])
    owners = {'S3-02', 'S3-03', 'S3-04', 'S3-06', 'S3-07', 'S6-02', 'S6-03'}
    for case in scenarios:
        assert case['id'] not in identifiers, case['id']
        identifiers.add(case['id'])
        assert case['owner'] in owners, case['id']
        assert case['integration_status'] == 'not_run', 'No service exists; integration success cannot be claimed'
        operation = core['paths'][case['path']][case['method']]
        assert str(case['expected_http']) in operation['responses'], case['id']
        if case['expected_code'] is not None:
            assert case['expected_code'] in codes, case['id']
        assert case['setup'] and case['expectation'], case['id']
    for case in fixture['schema_cases']:
        document, path = (core, core_path) if case['document'] == 'core' else (future, future_path)
        errors = list(validator(document['components']['schemas'][case['schema']], document, path).iter_errors(case['data']))
        assert (not errors) == case['valid'], case['id']
    for case in fixture['arithmetic_cases']:
        if 'expected_balance' in case:
            flows = list(map(int, case['movements']))
            assert int(case['initial']) + sum(flows) == int(case['expected_balance']), case['id']
            assert sum(value for value in flows if value > 0) == int(case['expected_income']), case['id']
            assert -sum(value for value in flows if value < 0) == int(case['expected_expense']), case['id']
        elif 'expected_remaining' in case:
            remaining = {key: int(value) - int(case['refunded'][key]) for key, value in case['parts'].items()}
            assert remaining == {key: int(value) for key, value in case['expected_remaining'].items()}
            allowed = all(int(case['requested'][key]) <= amount for key, amount in remaining.items())
            assert allowed == case['expected_allowed'], case['id']
        else:
            balances = {key: int(value) for key, value in case['initial'].items()}
            for account, value in case['movements']:
                balances[account] += int(value)
            assert balances == {key: int(value) for key, value in case['expected_balances'].items()}, case['id']
            assert -int(case['movements'][2][1]) == int(case['expense_minor']['GBP'])
    for case in fixture['generation_cases']:
        if case['receipt_present']:
            actual = 'historical_replay_then_snapshot' if case['receipt_hash_matches'] else 'idempotency_conflict'
        else:
            actual = 'execute' if case['current_generation'] == case['requested_generation'] else 'sync_generation_conflict'
        assert actual == case['expected'], case['id']
    assert all(case['status'] == 'open' and case['owner'] in owners for case in fixture['release_dependencies'])
    print(f'PASS: design-review matrix ({len(scenarios)} owned scenarios, contract paths/statuses/codes), {len(fixture["schema_cases"])} schema cases, 3 financial calculations, 4 generation cases')
    print(f'NOT EXECUTED: {len(scenarios)} integration scenarios; public registration and recovery release dependencies remain open')


def main():
    core_path = ROOT / 'openapi.json'
    core = load(core_path)
    future_path = ROOT / 'openapi.future.json'
    future = load(future_path)
    meta = load(ROOT / 'vendor/openapi-3.1.schema.json')
    core_count = check_document(core, core_path, meta)
    future_count = check_document(future, future_path, meta)
    for name, schema in core['components']['schemas'].items():
        assert future['components']['schemas'][name] == schema, f'Shared schema drift: {name}'
    fixtures = load(ROOT / 'fixtures/api-examples.json')
    for sample in fixtures['valid']:
        validator(core['components']['schemas'][sample['schema']], core, core_path).validate(sample['data'])
    for sample in fixtures['invalid']:
        errors = list(validator(core['components']['schemas'][sample['schema']], core, core_path).iter_errors(sample['data']))
        assert errors, f'Invalid fixture accepted: {sample["id"]}'
    for sample in fixtures['future_valid']:
        validator(future['components']['schemas'][sample['schema']], future, future_path).validate(sample['data'])
    event_path = ROOT / 'events/workspace-change.schema.json'
    event_schema = load(event_path)
    Draft202012Validator.check_schema(event_schema)
    event_validator = validator(event_schema, event_schema, event_path)
    for event in fixtures['events']:
        event_validator.validate(event)
        assert event['workspace_id'] == event['data']['workspace_id']
        if event['operation'] == 'delete':
            assert event['entity_type'] == event['data']['entity_type']
    wrong_event = dict(fixtures['events'][0], entity_type='account')
    assert list(event_validator.iter_errors(wrong_event)), 'Mismatched upsert entity accepted'
    wrong_delete = dict(fixtures['events'][2], entity_type='account')
    assert list(event_validator.iter_errors(wrong_delete)), 'Mismatched delete entity accepted'
    print(f'PASS: official OpenAPI structural schema; {core_count} core and {future_count} future operations')
    print(f'PASS: refs, component schemas, path parameters, write security, shared schema consistency')
    print(f'PASS: {len(fixtures["valid"])} valid, {len(fixtures["invalid"])} rejected API examples, {len(fixtures["events"])} events, {len(fixtures["future_valid"])} future examples')
    check_sync(core, core_path)
    check_design_review(core, core_path, future, future_path)
    print('NOT EXECUTED: server behavior, PostgreSQL invariants, auth/CSRF enforcement and synchronization')


if __name__ == '__main__':
    main()
