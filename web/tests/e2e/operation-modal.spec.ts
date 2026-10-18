import { test, expect } from '@playwright/test';
import type { Auth, Account, List, Transaction } from '../../src/api/client';
test('the unified modal creates expense, income, FX transfer and balance correction in the real ledger', async ({ page, context }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const origin = { Origin: 'https://localhost:8444' };
  const response = await context.request.post('/api/v1/auth/register', { headers: origin, data: { email: `modal-${crypto.randomUUID()}@example.test`, password: 'synthetic-modal-password-2026', device_name: 'Operation modal acceptance', transport: 'cookie', timezone: 'UTC' } });
  expect(response.status()).toBe(201); const auth = await response.json() as Auth;
  const root = `/api/v1/workspaces/${auth.workspace.id}`, ids = { EUR: crypto.randomUUID(), USD: crypto.randomUUID() };
  for (const currency of ['EUR', 'USD'] as const) {
    const created = await context.request.post(`${root}/accounts`, { headers: { ...origin, 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': auth.workspace.sync_generation_id, 'Idempotency-Key': crypto.randomUUID() }, data: { id: ids[currency], name: currency, currency, type: 'bank', opening_balance_minor: '10000', opened_at: '2025-01-01T00:00:00Z', occurred_timezone: 'UTC' } });
    expect(created.status()).toBe(201);
  }
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
  await page.goto('/#operations');
  for (const kind of ['expense', 'income', 'transfer', 'adjustment']) {
    await page.getByRole('button', { name: 'Добавить операцию', exact: true }).click();
    const modal = page.getByRole('dialog', { name: 'Новая операция' });
    await modal.getByRole('combobox', { name: 'Тип операции', exact: true }).selectOption(kind);
    if (kind === 'transfer') {
      await modal.getByRole('combobox', { name: 'Со счета', exact: true }).selectOption(ids.EUR);
      await modal.getByRole('combobox', { name: 'На счет', exact: true }).selectOption(ids.USD);
      await modal.getByLabel('Сумма списания, EUR', { exact: true }).fill('10.00');
      await modal.getByLabel('Сумма зачисления, USD', { exact: true }).fill('11.00');
    } else if (kind === 'adjustment') {
      await modal.getByRole('combobox', { name: 'Счет корректировки', exact: true }).selectOption(ids.EUR);
      await modal.getByLabel('Фактический остаток, EUR', { exact: true }).fill('150.00');
      await modal.getByLabel('Причина корректировки', { exact: true }).fill('Сверка');
    } else {
      await modal.getByRole('combobox', { name: 'Счет', exact: true }).selectOption(ids.EUR);
      await modal.getByLabel('Сумма', { exact: true }).fill(kind === 'expense' ? '12.34' : '20.00');
    }
    await modal.getByRole('button', { name: 'Добавить', exact: true }).click();
    await expect(modal).toHaveCount(0);
  }
  const accounts = (await (await context.request.get(`${root}/accounts`)).json() as List<Account>).items;
  expect(accounts.find(a => a.id === ids.EUR)?.posted_balance_minor).toBe('15000');
  expect(accounts.find(a => a.id === ids.USD)?.posted_balance_minor).toBe('11100');
  const records = (await (await context.request.get(`${root}/transactions`)).json() as List<Transaction>).items;
  expect(records.filter(t => t.kind !== 'opening').map(t => t.kind).sort()).toEqual(['adjustment', 'expense', 'income', 'transfer']);
  expect(records.find(t => t.kind === 'transfer')?.rate).toEqual({ numerator: '11', denominator: '10' });
  expect(records.find(t => t.kind === 'adjustment')?.entries[0].amount_minor).toBe('5234');
  expect(errors).toEqual([]);
});
