import { test, expect } from '@playwright/test';
import { processes, restartAPIAndWeb, snapshot, evidence } from './environment';
const password = 'synthetic-browser-password-2026';
const email = () => `browser-${crypto.randomUUID()}@example.test`;
test('real cookie session, ledger, lost-response retry, container restart and text rendering', async ({ page, context }, testInfo) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  const address = email();
  await page.getByLabel('Электронная почта').fill(address);
  await page.getByLabel('Пароль', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  const cookie = (await context.cookies()).find(value => value.name === '__Host-accounting_session');
  expect(cookie).toMatchObject({ secure: true, httpOnly: true, sameSite: 'Lax', path: '/' });
  await page.getByLabel('Название счета').fill('Основной счет');
  await page.getByLabel('Начальный остаток, EUR').fill('1000,00');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.locator('.accounts strong').filter({ hasText: '1000,00 EUR' })).toBeVisible();
  const requests: { key: string | undefined; generation: string | undefined; body: string | null }[] = [];
  let dropped = false, replayed = false;
  await page.route('**/api/v1/workspaces/*/transactions', async route => {
    if (route.request().method() !== 'POST') { await route.continue(); return; }
    const headers = route.request().headers();
    requests.push({ key: headers['idempotency-key'], generation: headers['x-sync-generation'], body: route.request().postData() });
    // Fault injection only: the real API commits to PostgreSQL before its response is lost.
    const response = await route.fetch();
    expect(response.status()).toBe(201);
    if (!dropped) { dropped = true; await route.abort('connectionfailed'); }
    else { replayed = response.headers()['idempotency-replayed'] === 'true'; await route.fulfill({ response }); }
  });
  const note = '<img src=x onerror="window.accountingXss=true">';
  await page.getByLabel('Сумма', { exact: true }).fill('12,34');
  await page.getByLabel('Примечание').fill(note);
  await page.getByRole('button', { name: 'Сохранить операцию', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Операция могла сохраниться');
  await expect(page.getByRole('button', { name: 'Создать счет', exact: true })).toBeDisabled();
  const beforeSession = await (await context.request.get('/api/v1/auth/session')).json();
  const pendingBody = JSON.parse(requests[0].body!);
  const beforeDB = snapshot(beforeSession.workspace.id, pendingBody.account_id, requests[0].key!);
  expect(beforeDB).toMatchObject({ posted_balance_minor: '98766', account_version: '2', balance_version: '2', opening_count: '1', expense_count: '1', entry_count: '2', allocation_count: '1', receipt_count: '2', expense_receipt_count: '1', group_count: '2', change_count: '4', head_sequence: '2', generation_id: beforeSession.workspace.sync_generation_id });
  const beforeProcesses = processes();
  restartAPIAndWeb();
  await expect.poll(async () => {
    try { return (await context.request.get('/health/ready', { timeout: 1_000 })).status(); }
    catch { return 0; }
  }, { timeout: 20_000 }).toBe(200);
  const afterProcesses = processes();
  for (const service of ['api', 'web']) {
    const before = beforeProcesses.find(value => value.service === service)!;
    const after = afterProcesses.find(value => value.service === service)!;
    expect(after.running).toBe(true); expect(after.started_at).not.toBe(before.started_at); expect(after.pid).not.toBe(before.pid);
  }
  expect(afterProcesses.find(value => value.service === 'db')).toEqual(beforeProcesses.find(value => value.service === 'db'));
  expect(snapshot(beforeSession.workspace.id, pendingBody.account_id, requests[0].key!)).toEqual(beforeDB);
  await page.reload();
  await expect(page.getByRole('button', { name: 'Повторить ту же команду', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Повторить ту же команду', exact: true }).click();
  await expect(page.getByText('987,66 EUR', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Повторить ту же команду', exact: true })).toHaveCount(0);
  expect(requests).toHaveLength(2); expect(requests[1]).toEqual(requests[0]); expect(replayed).toBe(true);
  await expect(page.getByText(note, { exact: true })).toBeVisible();
  await expect(page.locator('img')).toHaveCount(0);
  expect(await page.evaluate(() => Reflect.get(window, 'accountingXss'))).toBeUndefined();
  await page.reload();
  await expect(page.getByText('987,66 EUR', { exact: true })).toBeVisible();
  await expect(page.locator('.history strong').filter({ hasText: /^Расход$/ })).toHaveCount(1);
  const session = await (await context.request.get('/api/v1/auth/session')).json();
  expect(session.session.id).toBe(beforeSession.session.id);
  const afterDB = snapshot(session.workspace.id, pendingBody.account_id, requests[0].key!);
  expect(afterDB).toEqual(beforeDB);
  evidence(testInfo.outputPath('vertical-browser.json'), { scenario: 'lost_response_container_restart_retry', before_processes: beforeProcesses, after_processes: afterProcesses, before_db: beforeDB, after_db: afterDB, same_command: requests[0].key === requests[1].key && requests[0].generation === requests[1].generation && requests[0].body === requests[1].body, replayed });
  expect(session.transport).toBe('cookie'); expect(session.access_token).toBeUndefined();
  const history = await (await context.request.get(`/api/v1/workspaces/${session.workspace.id}/transactions`)).json();
  expect(history.items.filter((value: { kind: string }) => value.kind === 'expense')).toHaveLength(1);
  // Genuine actor B cannot assign actor A's account to a financial command.
  const foreign = await context.browser()!.newContext({ baseURL: 'https://localhost:8444', ignoreHTTPSErrors: true });
  try {
    const registered = await foreign.request.post('/api/v1/auth/register', { headers: { Origin: 'https://localhost:8444' }, data: { email: email(), password, device_name: 'Foreign test', transport: 'cookie', timezone: 'UTC' } });
    expect(registered.status()).toBe(201);
    const other = await registered.json();
    const body = JSON.parse(requests[0].body!); body.id = crypto.randomUUID(); body.allocations[0].id = crypto.randomUUID();
    const result = await foreign.request.post(`/api/v1/workspaces/${other.workspace.id}/transactions`, { headers: { Origin: 'https://localhost:8444', 'X-CSRF-Token': other.csrf_token, 'X-Sync-Generation': other.workspace.sync_generation_id, 'Idempotency-Key': crypto.randomUUID() }, data: body });
    expect(result.status()).toBe(404); expect((await result.json()).code).toBe('not_found');
  } finally { await foreign.close(); }
  // Generation and CSRF guards are exercised against the real endpoint.
  const original = JSON.parse(requests[0].body!); original.id = crypto.randomUUID(); original.allocations[0].id = crypto.randomUUID();
  const root = `/api/v1/workspaces/${session.workspace.id}/transactions`;
  const stale = await context.request.post(root, { headers: { Origin: 'https://localhost:8444', 'X-CSRF-Token': session.csrf_token, 'X-Sync-Generation': crypto.randomUUID(), 'Idempotency-Key': crypto.randomUUID() }, data: original });
  expect(stale.status()).toBe(409); expect((await stale.json()).code).toBe('sync_generation_conflict');
  const csrf = await context.request.post(root, { headers: { Origin: 'https://localhost:8444', 'X-CSRF-Token': 'invalid', 'X-Sync-Generation': requests[0].generation!, 'Idempotency-Key': requests[0].key! }, data: JSON.parse(requests[0].body!) });
  expect(csrf.status()).toBe(403);
  await page.unroute('**/api/v1/workspaces/*/transactions');
  await page.route('**/api/v1/workspaces/*/transactions', async route => {
    if (route.request().method() === 'POST') {
      // Inject a stale generation into a real request; the server rejects it.
      await route.continue({ headers: { ...route.request().headers(), 'x-sync-generation': crypto.randomUUID() } });
    } else await route.continue();
  });
  await page.getByLabel('Сумма', { exact: true }).fill('1,00');
  await page.getByRole('button', { name: 'Сохранить операцию', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Данные восстановлены или изменены на сервере');
  await expect(page.getByRole('button', { name: 'Создать счет', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Обновить', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Создать счет', exact: true })).toBeEnabled();
  await expect(page.getByText('987,66 EUR', { exact: true })).toBeVisible();
  await context.setOffline(true);
  await expect(page.getByText('Нет соединения.', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Сохранить операцию', exact: true })).toBeDisabled();
  await context.setOffline(false);
  await page.getByRole('button', { name: 'Выйти', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(address);
  await page.getByLabel('Пароль', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page.getByText('987,66 EUR', { exact: true })).toBeVisible();
});
