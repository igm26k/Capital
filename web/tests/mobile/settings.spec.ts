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

test('settings groups preserve drafts and profile controls persist appearance', async ({ page }) => {
  const choose = async (name: string) => { await page.getByRole('button', { name: 'Назад к настройкам' }).click(); await page.getByRole('button', { name, exact: true }).click(); };
  await expect(page.getByRole('heading', { name: 'Настройки', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /Профиль settings@example.test/ })).toBeVisible();
  await page.getByRole('button', { name: 'Категории и теги', exact: true }).click();
  await page.getByLabel('Название категории', { exact: true }).fill('Черновик категории');
  await choose('Профиль и оформление');
  await page.getByLabel('Тема оформления').selectOption('dark');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(page.getByRole('button', { name: 'Продлить сессию' })).toBeVisible();
  await choose('Категории и теги');
  await expect(page.getByLabel('Название категории', { exact: true })).toHaveValue('Черновик категории');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.getByRole('button', { name: 'Назад к настройкам' }).click();
  await expect(page.getByRole('button', { name: 'Выйти из аккаунта' })).toBeVisible();
});
for (const width of [320, 390, 768]) test(`settings landing fits ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 844 }); await page.emulateMedia({ colorScheme: 'dark' });
  for (const name of ['Управление финансами', 'Интеграции и данные', 'Приложение']) await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: test.info().outputPath(`minimal-settings-${width}.png`), fullPage: true });
  await page.getByRole('button', { name: 'Банки и уведомления', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Банки и уведомления', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Добавить банк' })).toBeVisible();
  await expect(page.getByText('Автоприём в браузере недоступен')).toHaveCount(2);
});
