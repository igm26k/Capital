import { test, expect } from '@playwright/test';
const workspace = { id: '00000000-0000-4000-8000-000000000001', name: 'Финансы', sync_generation_id: 'generation' };
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'settings@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    return route.fulfill({ json: body });
  });
  await page.goto('/#more');
});
test('settings menu switches one visible group, preserves drafts and remembers the group after reload', async ({ page }) => {
  const writes: string[] = []; page.on('request', r => { if (r.method() !== 'GET') writes.push(r.url()); });
  const trigger = page.getByRole('button', { name: 'Группы настроек', exact: true });
  const choose = async (name: string) => { await trigger.click(); await page.getByRole('navigation', { name: 'Группы настроек' }).getByRole('button', { name, exact: true }).click(); };
  await expect(page.getByRole('heading', { name: 'Профиль', exact: true })).toBeVisible();
  await choose('Категории и теги');
  await expect(page.getByRole('heading', { name: 'Профиль', exact: true })).toBeHidden();
  await page.getByLabel('Название категории', { exact: true }).fill('Черновик категории');
  await choose('Профиль'); await choose('Категории и теги');
  await expect(page.getByLabel('Название категории', { exact: true })).toHaveValue('Черновик категории');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Категории и теги', exact: true })).toBeVisible();
  await trigger.click();
  await expect(page.getByRole('navigation', { name: 'Группы настроек' }).getByRole('button', { name: 'Категории и теги', exact: true })).toHaveAttribute('aria-current', 'page');
  await page.keyboard.press('Escape'); await expect(trigger).toBeFocused(); await expect(trigger).toHaveAttribute('aria-expanded', 'false');
  await choose('Безопасность и данные');
  await expect(page.getByRole('heading', { name: 'Устройства', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Категории и теги', exact: true })).toBeHidden();
  expect(writes).toEqual([]);
});
test('settings menu fits 320px and closes outside without losing the selected import file', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 }); await page.emulateMedia({ colorScheme: 'dark' });
  const trigger = page.getByRole('button', { name: 'Группы настроек', exact: true });
  await trigger.click();
  const menu = page.getByRole('navigation', { name: 'Группы настроек' });
  expect(await menu.getByRole('button').count()).toBe(6);
  const bounds = await menu.boundingBox(); expect(bounds!.x).toBeGreaterThanOrEqual(0); expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(320);
  await page.screenshot({ path: test.info().outputPath('settings-menu-320.png') });
  await page.getByRole('heading', { name: 'Capital', exact: true }).click(); await expect(menu).toHaveCount(0);
  await trigger.click(); await menu.getByRole('button', { name: 'Импорт и экспорт', exact: true }).click();
  await page.locator('summary').filter({ hasText: /^Импорт из Wallet$/ }).click();
  await page.getByLabel('Файл Wallet CSV').setInputFiles({ name: 'wallet_records.csv', mimeType: 'text/csv', buffer: Buffer.from('account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels\nCash;Food;EUR;1;1;Expense;Cash;;2026-01-01T00:00:00Z;false;;\n') });
  await trigger.click(); await menu.getByRole('button', { name: 'Профиль', exact: true }).click();
  await trigger.click(); await menu.getByRole('button', { name: 'Импорт и экспорт', exact: true }).click();
  await expect(page.getByLabel('Файл Wallet CSV')).toHaveValue(/wallet_records\.csv$/);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
