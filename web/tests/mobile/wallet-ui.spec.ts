import { test, expect } from '@playwright/test';
const workspace = { id: '00000000-0000-4000-8000-000000000001', name: 'Финансы', sync_generation_id: 'generation' };
const header = 'account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels\n';
const row = (account: string, amount: string, kind = 'Расход', transfer = 'false', currency = 'EUR') => `${account};Продукты;${currency};${amount};99999;${kind};Наличные;Тест;2025-08-12T15:00:00.000Z;${transfer};;\n`;
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'wallet@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    return route.fulfill({ json: body });
  });
  await page.goto('/#more');
  await page.locator('summary').filter({ hasText: /^Импорт из Wallet$/ }).click();
});
test('Wallet previews before writes and imports accounts, categories, expenses and paired transfers', async ({ page }) => {
  const requests: { path: string; body: Record<string, unknown>; key: string }[] = [];
  await page.route('**/api/v1/workspaces/*/*', route => {
    if (route.request().method() !== 'POST') return route.fallback();
    requests.push({ path: new URL(route.request().url()).pathname, body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] });
    return route.fulfill({ json: {} });
  });
  await page.getByLabel('Файл Wallet CSV').setInputFiles({ name: 'wallet_records.csv', mimeType: 'text/csv', buffer: Buffer.from(header + row('Cash', '12.34') + row('Bank', '100', 'Расход', 'true', 'USD') + row('Cash', '91', 'Доход', 'true')) });
  await expect(page.getByText('Подобрано пар: 1.', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Подготовить импорт Wallet', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Подтвердите перенос' })).toBeVisible();
  expect(requests).toHaveLength(0);
  await page.getByRole('button', { name: 'Подтвердить импорт Wallet', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Импорт Wallet завершен' })).toBeVisible();
  expect(requests.filter(r => r.path.endsWith('/accounts'))).toHaveLength(2);
  expect(requests.filter(r => r.path.endsWith('/categories'))).toHaveLength(1);
  const transactions = requests.filter(r => r.path.endsWith('/transactions'));
  expect(transactions).toHaveLength(2);
  expect(transactions[0].body).toMatchObject({ kind: 'transfer', source_amount_minor: '10000', target_amount_minor: '9100' });
  expect(transactions[1].body).toMatchObject({ kind: 'expense', amount_minor: '1234' });
  expect((transactions[1].body.allocations as { category_id: string }[])[0].category_id).toBe(requests.find(r => r.path.endsWith('/categories'))!.body.id);
  await page.reload(); await page.locator('summary').filter({ hasText: /^Импорт из Wallet$/ }).click();
  await expect(page.getByRole('heading', { name: 'Импорт Wallet завершен' })).toBeVisible();
  expect(requests).toHaveLength(5);
  await page.getByRole('button', { name: 'Закрыть завершенный импорт Wallet', exact: true }).click();
  await expect(page.getByLabel('Файл Wallet CSV')).toBeVisible();
});
test('Wallet resumes the exact uncertain command after reload', async ({ page }) => {
  const requests: { key: string; body: string | null }[] = [];
  let lost = false;
  await page.route('**/api/v1/workspaces/*/*', route => {
    if (route.request().method() !== 'POST') return route.fallback();
    requests.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() });
    if (route.request().url().endsWith('/transactions') && !lost) { lost = true; return route.abort('connectionfailed'); }
    return route.fulfill({ json: {} });
  });
  await page.getByLabel('Файл Wallet CSV').setInputFiles({ name: 'wallet_records.csv', mimeType: 'text/csv', buffer: Buffer.from(header + row('Cash', '12.34')) });
  await page.getByRole('button', { name: 'Подготовить импорт Wallet', exact: true }).click();
  await page.getByRole('button', { name: 'Подтвердить импорт Wallet', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  expect(requests).toHaveLength(3);
  await page.reload(); await page.locator('summary').filter({ hasText: /^Импорт из Wallet$/ }).click();
  await page.getByRole('button', { name: 'Продолжить импорт Wallet', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Импорт Wallet завершен' })).toBeVisible();
  expect(requests).toHaveLength(4);
  expect(requests[3]).toEqual(requests[2]);
});
test('Wallet requires an explicit decision for an orphan transfer', async ({ page }) => {
  await page.getByLabel('Файл Wallet CSV').setInputFiles({ name: 'wallet_records.csv', mimeType: 'text/csv', buffer: Buffer.from(header + row('Cash', '12.34') + row('Bank', '100', 'Расход', 'true', 'USD')) });
  await expect(page.getByRole('button', { name: 'Подготовить импорт Wallet', exact: true })).toBeDisabled();
  await page.getByRole('checkbox', { name: /Я проверил строки без пары/ }).check();
  await page.getByRole('button', { name: 'Подготовить импорт Wallet', exact: true }).click();
  await expect(page.getByText(/Новых счетов: 2.*Исключено строк: 1/)).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test('Wallet has no account or column mapping and automatically creates only missing accounts', async ({ page }) => {
  const existing = { id: '00000000-0000-4000-8000-000000000099', name: 'Cash', currency: 'EUR', type: 'cash', archived_at: null, posted_balance_minor: '0', version: '1', balance_version: '1' };
  const writes: { path: string; body: Record<string, unknown> }[] = [];
  await page.route('**/api/v1/workspaces/*/*', route => {
    if (route.request().method() === 'POST') { writes.push({ path: new URL(route.request().url()).pathname, body: route.request().postDataJSON() }); return route.fulfill({ json: {} }); }
    if (new URL(route.request().url()).pathname.endsWith('/accounts')) return route.fulfill({ json: { items: [existing], next_cursor: null } });
    return route.fallback();
  });
  await page.reload();
  await page.locator('summary').filter({ hasText: /^Импорт из Wallet$/ }).click();
  await page.getByLabel('Файл Wallet CSV').setInputFiles({ name: 'wallet_records.csv', mimeType: 'text/csv', buffer: Buffer.from(header + row('Cash', '12.34') + row('Bank', '50', 'Доход', 'false', 'USD')) });
  await expect(page.getByText('1 строк · Существующий счет', { exact: true })).toBeVisible();
  await expect(page.getByText('1 строк · Будет создан автоматически', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Импорт Wallet', { exact: true }).getByRole('combobox')).toHaveCount(0);
  await page.locator('summary').filter({ hasText: /^Экспорт CSV$/ }).click();
  await expect(page.getByLabel('Колонка даты', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('CSV файл', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Подготовить импорт Wallet', exact: true }).click();
  await page.getByRole('button', { name: 'Подтвердить импорт Wallet', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Импорт Wallet завершен' })).toBeVisible();
  const createdAccounts = writes.filter(r => r.path.endsWith('/accounts'));
  expect(createdAccounts).toHaveLength(1);
  expect(createdAccounts[0].body).toMatchObject({ name: 'Bank', currency: 'USD' });
  expect(writes.filter(r => r.path.endsWith('/transactions')).map(r => r.body.account_id)).toContain(existing.id);
});
test('Wallet resolves grouped transfers and shows why each pair was suggested', async ({ page }) => {
  await page.getByLabel('Файл Wallet CSV').setInputFiles({ name: 'wallet_records.csv', mimeType: 'text/csv', buffer: Buffer.from(header + row('Bank A', '100', 'Расход', 'true') + row('Bank B', '200', 'Расход', 'true') + row('Cash A', '100', 'Доход', 'true') + row('Cash B', '200', 'Доход', 'true')) });
  await expect(page.getByText(/Подобрано пар: 2\. Без пары: 0 строк/)).toBeVisible();
  await expect(page.getByRole('checkbox', { name: /Я проверил строки без пары/ })).toHaveCount(0);
  await page.getByText('Проверить пары переводов (2)', { exact: true }).click();
  await expect(page.getByText('Единственное совпадение суммы и валюты', { exact: true })).toHaveCount(2);
  await page.getByRole('button', { name: 'Подготовить импорт Wallet', exact: true }).click();
  await expect(page.getByText(/Доходов, расходов и переводов: 2\. Исключено строк: 0/)).toBeVisible();
});
