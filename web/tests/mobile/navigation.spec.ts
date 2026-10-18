import { test, expect } from '@playwright/test';
import type { Account } from '../../src/api/client';
const workspace = { id: '00000000-0000-4000-8000-000000000001', name: 'Личные финансы', sync_generation_id: 'generation' };
const accounts: Account[] = [
  { id: 'bank', name: 'Основной счет', type: 'bank', currency: 'EUR', posted_balance_minor: '120050', archived_at: null, version: '1', balance_version: '1' },
  { id: 'cash', name: 'Наличные', type: 'cash', currency: 'EUR', posted_balance_minor: '-5050', archived_at: null, version: '1', balance_version: '1' },
  { id: 'archive', name: 'Старая карта', type: 'card', currency: 'USD', posted_balance_minor: '700', archived_at: '2026-01-01', version: '1', balance_version: '1' },
];
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'mobile@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts')) body = { items: accounts, next_cursor: null };
    await route.fulfill({ json: body });
  });
});
test('accounts match Android, filters and history navigation preserve SPA and browser back', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('navigation').getByRole('link', { name: 'Счета', exact: true })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByLabel('Итоги по валютам')).toContainText('1150,00 EUR');
  await expect(page.getByRole('button', { name: 'Изменить счет Старая карта' })).toBeHidden();
  await page.getByRole('button', { name: 'Настроить счета', exact: true }).click();
  await page.getByRole('button', { name: 'Архивные счета', exact: true }).click();
  await expect(page.getByLabel('Итоги по валютам')).toContainText('7,00 USD');
  await page.getByRole('button', { name: 'Операции счета Старая карта' }).click();
  await expect(page.getByRole('heading', { name: 'История операций' })).toBeVisible();
  await page.getByText('Фильтры операций', { exact: true }).click();
  await expect(page.getByLabel('Счет истории', { exact: true })).toHaveValue('archive');
  await page.getByRole('navigation').getByRole('link', { name: 'Еще', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Группы настроек', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Профиль', exact: true })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: 'История операций' })).toBeVisible();
  await page.getByRole('navigation').getByRole('link', { name: 'Счета', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Архивные счета', exact: true })).toHaveAttribute('aria-pressed', 'true');
});
test('account draft survives section navigation and submits through existing API', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Добавить счет', exact: true }).click();
  await page.getByLabel('Название счета', { exact: true }).fill('Мобильный счет');
  await page.getByRole('navigation').getByRole('link', { name: 'Операции', exact: true }).click();
  await page.getByRole('navigation').getByRole('link', { name: 'Счета', exact: true }).click();
  await expect(page.getByLabel('Название счета', { exact: true })).toHaveValue('Мобильный счет');
  const sent = page.waitForRequest(r => r.method() === 'POST' && r.url().endsWith('/accounts'));
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  const request = await sent;
  expect(request.postDataJSON()).toMatchObject({ name: 'Мобильный счет', currency: 'EUR', opening_balance_minor: '0' });
  expect(request.headers()['x-csrf-token']).toBe('csrf');
  expect(request.headers()['idempotency-key']).toBeTruthy();
  await expect(page.getByRole('button', { name: 'Добавить счет', exact: true })).toBeVisible();
});
for (const width of [320, 390, 768]) test(`layout fits ${width}px viewport with accessible touch navigation`, async ({ page }) => {
  await page.setViewportSize({ width, height: 844 });
  await page.goto('/');
  await expect(page.getByTestId('balance-bank')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  const nav = await page.getByRole('navigation').boundingBox();
  expect(nav!.y + nav!.height).toBeLessThanOrEqual(845);
  for (const link of await page.getByRole('navigation').getByRole('link').all()) expect((await link.boundingBox())!.height).toBeGreaterThanOrEqual(48);
  await page.screenshot({ path: test.info().outputPath(`accounts-${width}.png`), fullPage: true });
});
test('dark theme and direct section URL work', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.goto('/#operations');
  await expect(page.getByRole('heading', { name: 'История операций' })).toBeVisible();
  await page.getByRole('button', { name: 'Добавить операцию', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Новая операция' }).getByRole('button', { name: 'Добавить', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
test('uncertain account save keeps the same command and key after reload', async ({ page }) => {
  const commands: { body: string | null; key: string | undefined }[] = [];
  await page.route('**/api/v1/workspaces/*/accounts', async route => {
    if (route.request().method() !== 'POST') return route.fallback();
    commands.push({ body: route.request().postData(), key: route.request().headers()['idempotency-key'] });
    if (commands.length === 1) await route.abort('connectionfailed');
    else await route.fulfill({ json: {} });
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Добавить счет', exact: true }).click();
  await page.getByLabel('Название счета', { exact: true }).fill('Сохранить один раз');
  await page.getByRole('radio', { name: 'KWD', exact: true }).check();
  await page.getByLabel('Начальный остаток, KWD').fill('12,345');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Операция могла сохраниться');
  await page.reload();
  await expect(page.getByRole('button', { name: 'Добавить счет', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Повторить ту же команду', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Добавить счет', exact: true })).toBeEnabled();
  expect(commands).toHaveLength(2);
  expect(commands[1]).toEqual(commands[0]);
  expect(JSON.parse(commands[0].body!)).toMatchObject({ currency: 'KWD', opening_balance_minor: '12345' });
});
test('empty accounts explain the next action', async ({ page }) => {
  await page.route('**/api/v1/workspaces/*/accounts?*', route => route.fulfill({ json: { items: [], next_cursor: null } }));
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Создайте первый счет', exact: true })).toBeVisible();
  await expect(page.getByLabel('Итоги по валютам')).toHaveCount(0);
  await page.getByRole('button', { name: 'Добавить счет', exact: true }).click();
  await expect(page.getByLabel('Название счета', { exact: true })).toBeVisible();
});
