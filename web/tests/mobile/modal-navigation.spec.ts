import { test, expect } from '@playwright/test';
const workspace = { id: '00000000-0000-4000-8000-000000000001', name: 'Финансы', sync_generation_id: 'generation' };
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'navigation@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts/order')) body = { version: '0', account_ids: ['cash'] };
    else if (path.endsWith('/accounts')) body = { items: [{ id: 'cash', name: 'Наличные', type: 'cash', currency: 'EUR', version: '1', balance_version: '1', posted_balance_minor: '10000', archived_at: null }], next_cursor: null };
    return route.fulfill({ json: body });
  });
});
test('modal close buttons stay top right and close only their own window', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  await page.goto('/');
  await page.getByRole('button', { name: 'Добавить операцию', exact: true }).click();
  const operation = page.getByRole('dialog', { name: 'Новая операция', exact: true });
  await operation.getByRole('button', { name: 'Счет', exact: true }).click();
  const picker = page.getByRole('dialog', { name: 'Выбор: Счет', exact: true });
  const close = picker.getByRole('button', { name: 'Закрыть окно', exact: true });
  await expect(close).toBeInViewport();
  const pickerBox = (await picker.boundingBox())!, closeBox = (await close.boundingBox())!;
  expect(closeBox.x).toBeGreaterThan(pickerBox.x + pickerBox.width / 2);
  expect(closeBox.y).toBeLessThan(pickerBox.y + 60);
  await close.click();
  await expect(picker).toHaveCount(0);
  await expect(operation).toBeVisible();
  await operation.locator('.modal-content').evaluate(el => { el.scrollTop = el.scrollHeight; });
  const operationClose = operation.getByRole('button', { name: 'Закрыть окно', exact: true });
  await expect(operationClose).toBeInViewport();
  await page.screenshot({ path: test.info().outputPath('modal-close-320.png') });
  await operationClose.click();
  await expect(operation).toHaveCount(0);
  await expect(page.getByRole('navigation', { name: 'Основная навигация' }).getByRole('link', { name: 'Счета', exact: true })).toHaveAttribute('aria-current', 'page');
});
test('Back skips settings changes and returns through screens to the dashboard', async ({ page }) => {
  await page.goto('/');
  const nav = page.getByRole('navigation', { name: 'Основная навигация' });
  await nav.getByRole('link', { name: 'Операции', exact: true }).click();
  await nav.getByRole('link', { name: 'Настройки', exact: true }).click();
  for (const group of ['Счета', 'Категории и теги', 'Профиль']) {
    await page.getByRole('button', { name: 'Группы настроек', exact: true }).click();
    await page.getByRole('navigation', { name: 'Группы настроек' }).getByRole('button', { name: group, exact: true }).click();
  }
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await expect(nav.getByRole('link', { name: 'Операции', exact: true })).toHaveAttribute('aria-current', 'page');
  await page.getByRole('button', { name: 'Назад', exact: true }).click();
  await expect(nav.getByRole('link', { name: 'Счета', exact: true })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('region', { name: 'Структура расходов', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Назад', exact: true })).toHaveCount(0);
});
test('a deep link still has dashboard as its first screen, and Back dismisses the modal without replaying form steps', async ({ page }) => {
  await page.goto('/#operations');
  await page.getByRole('button', { name: 'Добавить операцию', exact: true }).click();
  const operation = page.getByRole('dialog', { name: 'Новая операция', exact: true });
  await operation.getByRole('combobox', { name: 'Тип операции', exact: true }).selectOption('income');
  await operation.getByRole('combobox', { name: 'Тип операции', exact: true }).selectOption('expense');
  await page.goBack();
  await expect(operation).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Структура расходов', exact: true })).toBeVisible();
});
