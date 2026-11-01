import { test, expect } from '@playwright/test';
const workspace = { id: '00000000-0000-4000-8000-000000000001', name: 'Финансы', sync_generation_id: 'generation' };
const accounts = [{ id: 'cash', name: 'Кошелек', type: 'cash', currency: 'EUR', posted_balance_minor: '10000', version: '1', balance_version: '1', archived_at: null }];
const categories = [
  { id: 'a', name: 'Питание', parent_id: null, archived_at: null },
  { id: 'b', name: 'Продукты', parent_id: 'a', archived_at: null },
  { id: 'c', name: 'Кофе', parent_id: 'a', archived_at: null },
  { id: 'd', name: 'Транспорт', parent_id: null, archived_at: null },
];
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'ui@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts')) body = { items: accounts, next_cursor: null };
    else if (path.endsWith('/categories')) body = { items: categories, next_cursor: null };
    return route.fulfill({ json: body });
  });
});
test('loading is centered and blocks navigation until all concurrent requests finish', async ({ page }) => {
  await page.goto('/#more');
  const loader = page.getByRole('dialog', { name: 'Загрузка', exact: true });
  await expect(loader).not.toBeVisible();
  let releaseAccounts!: () => void, releaseCategories!: () => void;
  const accountsGate = new Promise<void>(resolve => { releaseAccounts = resolve; });
  const categoriesGate = new Promise<void>(resolve => { releaseCategories = resolve; });
  await page.route('**/accounts?*', async route => { await accountsGate; return route.fallback(); });
  await page.route('**/categories?*', async route => { await categoriesGate; return route.fallback(); });
  await page.getByRole('button', { name: 'Обновить данные', exact: true }).click();
  await expect(loader).toBeVisible();
  const box = await loader.locator('.loading-spinner').boundingBox();
  const viewport = page.viewportSize()!;
  expect(Math.abs(box!.x + box!.width / 2 - viewport.width / 2)).toBeLessThan(2);
  expect(Math.abs(box!.y + box!.height / 2 - viewport.height / 2)).toBeLessThan(2);
  await page.keyboard.press('Escape');
  await expect(loader).toBeVisible();
  await expect(page.getByRole('link', { name: 'Счета', exact: true }).click({ timeout: 300 })).rejects.toThrow();
  await page.screenshot({ path: test.info().outputPath('centered-loading.png') });
  releaseAccounts();
  await expect(loader).toBeVisible();
  releaseCategories();
  await expect(loader).not.toBeVisible();
  await page.getByRole('link', { name: 'Счета', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Список счетов/ })).toBeVisible();
});
test('category icons inherit their group color and different groups have different colors', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Добавить операцию', exact: true }).click();
  const modal = page.getByRole('dialog', { name: 'Новая операция' });
  await modal.getByRole('button', { name: 'Категория', exact: true }).click();
  const options = modal.getByRole('listbox', { name: 'Категория', exact: true });
  const food = options.getByRole('option', { name: 'Питание / Продукты', exact: true });
  const coffee = options.getByRole('option', { name: 'Питание / Кофе', exact: true });
  const transport = options.getByRole('option', { name: 'Транспорт', exact: true });
  const color = (locator: typeof food) => locator.locator('.category-icon').evaluate(el => getComputedStyle(el).color);
  expect(await color(food)).toBe(await color(coffee));
  expect(await color(food)).not.toBe(await color(transport));
  expect(await food.locator('svg path').getAttribute('d')).not.toBe(await transport.locator('svg path').getAttribute('d'));
  await food.click();
  await expect(modal.getByRole('button', { name: 'Категория', exact: true }).locator('.category-icon svg')).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('category-icons.png') });
});
test('saving an operation blocks the editor until the write finishes', async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/workspaces/*/transactions', async route => {
    if (route.request().method() !== 'POST') return route.fallback();
    await gate;
    return route.fulfill({ status: 201, json: {} });
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Добавить операцию', exact: true }).click();
  const modal = page.getByRole('dialog', { name: 'Новая операция' });
  await modal.getByLabel('Сумма', { exact: true }).fill('12.34');
  await modal.getByRole('button', { name: 'Добавить', exact: true }).click();
  const loader = page.getByRole('dialog', { name: 'Загрузка', exact: true });
  await expect(loader).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(loader).toBeVisible();
  expect(await page.locator('.operation-modal').evaluate(el => (el as HTMLDialogElement).open)).toBe(true);
  release();
  await expect(loader).not.toBeVisible();
  await expect(modal).toHaveCount(0);
});
