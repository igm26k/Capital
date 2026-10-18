import { test, expect } from '@playwright/test';
const workspace = { id: '00000000-0000-4000-8000-000000000001', name: 'Финансы', sync_generation_id: 'generation' };
const accounts = [{ id: 'cash', name: 'Евро', type: 'cash', currency: 'EUR', posted_balance_minor: '10000', version: '1', balance_version: '9', archived_at: null }, { id: 'bank', name: 'Доллары', type: 'bank', currency: 'USD', posted_balance_minor: '5000', version: '1', balance_version: '3', archived_at: null }];
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'modal@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts')) body = { items: accounts, next_cursor: null };
    else if (path.endsWith('/categories')) body = { items: [{ id: 'food', name: 'Продукты', parent_id: null, archived_at: null }], next_cursor: null };
    return route.fulfill({ json: body });
  });
  await page.goto('/#operations');
});
for (const kind of ['expense', 'income', 'transfer', 'adjustment']) test(`modal creates ${kind} with the existing command protocol`, async ({ page }) => {
  const writes: { path: string; body: Record<string, unknown>; key: string }[] = [];
  await page.route('**/api/v1/workspaces/**', route => {
    if (route.request().method() !== 'POST') return route.fallback();
    writes.push({ path: new URL(route.request().url()).pathname, body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] });
    return route.fulfill({ status: 201, json: {} });
  });
  await page.getByRole('button', { name: 'Добавить операцию' }).click();
  const modal = page.getByRole('dialog', { name: 'Новая операция' });
  await modal.getByRole('combobox', { name: 'Тип операции', exact: true }).selectOption(kind);
  if (kind === 'transfer') {
    await modal.getByLabel('Сумма списания, EUR', { exact: true }).fill('12.34');
    await modal.getByLabel('Сумма зачисления, USD', { exact: true }).fill('13.45');
  } else if (kind === 'adjustment') {
    await modal.getByLabel('Фактический остаток, EUR', { exact: true }).fill('120.00');
    await modal.getByLabel('Причина корректировки', { exact: true }).fill('Сверка наличных');
  } else {
    await modal.getByLabel('Сумма', { exact: true }).fill('12.34');
    await modal.getByRole('combobox', { name: 'Категория', exact: true }).selectOption('food');
  }
  await modal.getByRole('button', { name: 'Добавить', exact: true }).click();
  await expect(modal).toHaveCount(0);
  expect(writes).toHaveLength(1); expect(writes[0].key).toBeTruthy();
  if (kind === 'transfer') expect(writes[0].body).toMatchObject({ kind, source_account_id: 'cash', target_account_id: 'bank', source_amount_minor: '1234', target_amount_minor: '1345' });
  else if (kind === 'adjustment') { expect(writes[0].path).toMatch(/accounts\/cash\/adjustments$/); expect(writes[0].body).toMatchObject({ target_balance_minor: '12000', expected_balance_version: '9', reason: 'Сверка наличных' }); }
  else expect(writes[0].body).toMatchObject({ kind, account_id: 'cash', amount_minor: '1234', allocations: [expect.objectContaining({ category_id: 'food' })] });
});
test('modal preserves drafts between types, cancels without writes and restores focus', async ({ page }) => {
  let writes = 0; page.on('request', r => { if (r.method() === 'POST') writes++; });
  const add = page.getByRole('button', { name: 'Добавить операцию', exact: true });
  await add.click(); const modal = page.getByRole('dialog');
  await modal.getByLabel('Сумма', { exact: true }).fill('12.34');
  await modal.getByRole('combobox', { name: 'Тип операции', exact: true }).selectOption('transfer');
  await modal.getByRole('combobox', { name: 'Тип операции', exact: true }).selectOption('income');
  await expect(modal.getByLabel('Сумма', { exact: true })).toHaveValue('12.34');
  await modal.getByRole('button', { name: 'Отмена', exact: true }).click();
  await expect(modal).toHaveCount(0); await expect(add).toBeFocused();
  await add.click(); await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(writes).toBe(0);
});
test('modal keeps validation and uncertain request visible and replays the same command', async ({ page }) => {
  const writes: { key: string; body: string | null }[] = [];
  await page.route('**/api/v1/workspaces/*/transactions', route => {
    if (route.request().method() !== 'POST') return route.fallback();
    writes.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() });
    return writes.length === 1 ? route.abort('connectionfailed') : route.fulfill({ status: 201, json: {} });
  });
  await page.getByRole('button', { name: 'Добавить операцию' }).click(); const modal = page.getByRole('dialog');
  await modal.getByLabel('Сумма', { exact: true }).fill('-1');
  await modal.getByRole('button', { name: 'Добавить', exact: true }).click();
  await expect(modal.getByRole('alert')).toContainText('Сумма должна быть больше нуля'); expect(writes).toHaveLength(0);
  await modal.getByLabel('Сумма', { exact: true }).fill('12.34');
  await modal.getByRole('button', { name: 'Добавить', exact: true }).click();
  await expect(modal.getByRole('alert')).toContainText('Ответ не получен');
  await expect(modal.getByRole('button', { name: 'Добавить', exact: true })).toBeDisabled();
  await modal.getByRole('button', { name: 'Отмена', exact: true }).click();
  await page.getByRole('button', { name: 'Повторить ту же команду', exact: true }).click();
  await expect.poll(() => writes.length).toBe(2); expect(writes[1]).toEqual(writes[0]);
});
test('modal fits a narrow viewport with keyboard focus contained inside', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  await page.getByRole('button', { name: 'Добавить операцию' }).click(); const modal = page.getByRole('dialog');
  await modal.getByRole('combobox', { name: 'Тип операции', exact: true }).selectOption('transfer');
  await modal.getByLabel('Добавить комиссию', { exact: true }).check();
  const bounds = await modal.boundingBox(); expect(bounds!.x).toBeGreaterThanOrEqual(0); expect(bounds!.y).toBeGreaterThanOrEqual(0); expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(568);
  await expect(modal.getByRole('button', { name: 'Добавить', exact: true })).toBeInViewport();
  for (let i = 0; i < 25; i++) { await page.keyboard.press('Tab'); expect(await page.evaluate(() => document.querySelector('dialog')!.contains(document.activeElement))).toBe(true); }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: test.info().outputPath('operation-modal-320.png') });
});
test('modal without accounts permits cancellation but cannot submit', async ({ page }) => {
  await page.route('**/accounts?*', route => route.fulfill({ json: { items: [], next_cursor: null } }));
  await page.reload();
  await page.getByRole('button', { name: 'Добавить операцию' }).click();
  const modal = page.getByRole('dialog');
  await expect(modal.getByText('Сначала добавьте счет в разделе «Счета».')).toBeVisible();
  await expect(modal.getByRole('button', { name: 'Добавить', exact: true })).toBeDisabled();
  await modal.getByRole('button', { name: 'Отмена' }).click();
  await expect(modal).toHaveCount(0);
});
