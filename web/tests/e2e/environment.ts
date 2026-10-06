import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../../', import.meta.url));
const compose = ['compose', '--project-name', 'accounting-test', '-f', 'compose.yaml', '-f', 'compose.test.yaml', '-f', 'compose.e2e.yaml'];
function docker(args: string[], input?: string): string {
  return execFileSync('docker', args, { cwd: root, encoding: 'utf8', input, timeout: 30_000, maxBuffer: 2 * 1024 * 1024, stdio: ['pipe', 'pipe', 'pipe'] }).trim();
}
export function processes() {
  return ['api', 'web', 'db'].map(service => {
    const id = docker([...compose, 'ps', '-q', service]);
    if (!/^[a-f0-9]{64}$/.test(id)) throw new Error(`Missing test service ${service}`);
    const state = JSON.parse(docker(['inspect', '--format', '{{json .State}}', id]));
    return { service, container_id: id, started_at: state.StartedAt as string, pid: state.Pid as number, running: state.Running as boolean };
  });
}
export function restartAPIAndWeb() {
  // Fixed test project/services; never interpolate user data into shell commands.
  docker([...compose, 'restart', 'api', 'web']);
}
export function snapshot(workspace: string, account: string, action: string) {
  for (const id of [workspace, account, action]) if (!/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id)) throw new Error('Invalid snapshot identifier');
  const sql = `SELECT json_build_object(
    'posted_balance_minor', (SELECT sum(e.amount_minor)::text FROM entries e JOIN transactions t ON (t.workspace_id,t.id)=(e.workspace_id,e.transaction_id) WHERE e.workspace_id='${workspace}' AND e.account_id='${account}' AND t.status='posted' AND t.deleted_at IS NULL),
    'account_version', (SELECT version::text FROM accounts WHERE workspace_id='${workspace}' AND id='${account}'),
    'balance_version', (SELECT balance_version::text FROM accounts WHERE workspace_id='${workspace}' AND id='${account}'),
    'opening_count', (SELECT count(*)::text FROM transactions WHERE workspace_id='${workspace}' AND kind='opening'),
    'expense_count', (SELECT count(*)::text FROM transactions WHERE workspace_id='${workspace}' AND kind='expense'),
    'entry_count', (SELECT count(*)::text FROM entries WHERE workspace_id='${workspace}'),
    'allocation_count', (SELECT count(*)::text FROM allocations WHERE workspace_id='${workspace}'),
    'receipt_count', (SELECT count(*)::text FROM actions WHERE workspace_id='${workspace}'),
    'expense_receipt_count', (SELECT count(*)::text FROM actions WHERE workspace_id='${workspace}' AND action_id='${action}' AND state='applied'),
    'group_count', (SELECT count(*)::text FROM sync_groups WHERE workspace_id='${workspace}'),
    'change_count', (SELECT count(*)::text FROM sync_changes WHERE workspace_id='${workspace}'),
    'head_sequence', last_sequence::text, 'generation_id', generation_id::text
  ) FROM sync_heads WHERE workspace_id='${workspace}';`;
  // Username is resolved inside the DB container; no connection string/password is exported.
  return JSON.parse(docker([...compose, 'exec', '-T', 'db', 'sh', '-c', 'exec psql -X -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d accounting_test'], sql));
}
export function evidence(path: string, data: object) {
  const records = docker([...compose, 'logs', '--no-color', '--no-log-prefix', 'api']).split('\n').flatMap(line => {
    try {
      const value = JSON.parse(line);
      if (value.msg === 'api_started' && typeof value.time === 'string') return [{ time: value.time, event: 'api_started' }];
      if (value.msg === 'request_completed' && ['GET', 'POST', 'PUT'].includes(value.method) && Number.isInteger(value.status) && typeof value.request_id === 'string' && /^[a-f0-9-]+$/.test(value.request_id)) return [{ time: value.time, event: 'request_completed', method: value.method, status: value.status, request_id: value.request_id }];
    } catch { /* Discard unstructured logs and all unselected fields. */ }
    return [];
  });
  writeFileSync(path, JSON.stringify({ ...data, api_events: records }, null, 2), { mode: 0o600 });
}
