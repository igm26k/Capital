import { test, expect } from '@playwright/test';
import { parseWallet, planWallet } from '../../src/features/imports/wallet';
import type { Auth, Account, List } from '../../src/api/client';
test('Wallet home renders real ledger data without treating FX or corrections as spending', async ({ page, context }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const origin = { Origin: 'https://localhost:8444' };
  const registered = await context.request.post('/api/v1/auth/register', { headers: origin, data: { email: `home-${crypto.randomUUID()}@example.test`, password: 'synthetic-home-password-2026', device_name: 'Home acceptance', transport: 'cookie', timezone: 'UTC' } });
  expect(registered.status()).toBe(201);
  const auth = await registered.json() as Auth;
  const root = `/api/v1/workspaces/${auth.workspace.id}`;
  const headers = { ...origin, 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': auth.workspace.sync_generation_id };
  for (const [name, currency] of [['Cash', 'EUR'], ['Bank', 'USD'], ['Rubles', 'RUB']]) {
    const response = await context.request.post(`${root}/accounts`, { headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() }, data: { id: crypto.randomUUID(), name, currency, type: 'bank', opening_balance_minor: '0', opened_at: '2025-01-01T00:00:00Z', occurred_timezone: 'UTC' } });
    expect(response.status()).toBe(201);
  }
  const accounts = (await (await context.request.get(`${root}/accounts`)).json() as List<Account>).items;
  const at = (minutes: number) => new Date(Date.now() - (60 - minutes) * 60000).toISOString();
  const date = at(2);
  const csv = 'account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels\n' + [
    `Cash;Groceries;EUR;12.34;12.34;Expense;Cash;;${at(0)};false;Shop;`,
    `Cash;Salary;EUR;100;100;Income;Cash;;${at(1)};false;;`,
    `Bank;Transfer;USD;100;100;Expense;Card;;${date};true;;`,
    `Cash;Transfer;EUR;90;100;Income;Cash;;${date};true;;`,
    `Cash;Correction;EUR;10;10;Income;Cash;;${at(3)};true;;`,
    `Rubles;Groceries;RUB;500;5;Expense;Card;;${at(4)};false;Synthetic store;`,
  ].join('\n');
  const plan = await planWallet(await parseWallet(csv), accounts, [], auth.profile.id, auth.workspace.id, auth.workspace.sync_generation_id);
  for (const command of plan.commands) {
    const response = await context.request.post(`${root}/${command.path}`, { headers: { ...headers, 'Idempotency-Key': command.key, 'Content-Type': 'application/json' }, data: command.body });
    expect(response.status()).toBe(201);
  }
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const writes: string[] = [];
  page.on('request', r => { if (r.method() !== 'GET') writes.push(r.url()); });
  await page.goto('/');
  const overview = page.getByRole('region', { name: 'Структура расходов' });
  await expect(overview.locator('.flow-summary')).toContainText('12,34 EUR');
  await expect(overview.locator('.flow-summary')).toContainText('100,00 EUR');
  await expect(page.getByRole('region', { name: 'Последние записи' })).toContainText('Synthetic store');
  await page.getByRole('button', { name: 'RUB', exact: true }).click();
  await expect(overview.locator('.flow-summary')).toContainText('500,00 RUB');
  await page.getByRole('button', { name: 'Операции за период' }).click();
  await expect(page.getByRole('heading', { name: 'История операций' })).toBeVisible();
  await page.getByText('Фильтры операций', { exact: true }).click();
  await expect(page.getByLabel('Статус в истории', { exact: true })).toHaveValue('posted');
  expect(writes).toEqual([]);
  expect(errors).toEqual([]);
});
