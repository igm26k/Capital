import { test, expect } from '@playwright/test';
const workspace = { id: '00000000-0000-4000-8000-000000000001', name: 'Финансы', sync_generation_id: 'generation' };
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'select@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts')) body = { items: Array.from({ length: 40 }, (_, i) => ({ id: 'account-' + i, name: 'Банковский счет ' + i, type: 'bank', currency: 'EUR', posted_balance_minor: '10000', version: '1', balance_version: '1', archived_at: null })), next_cursor: null };
    else if (path.endsWith('/categories')) body = { items: Array.from({ length: 80 }, (_, i) => ({ id: 'category-' + i, name: 'Категория с длинным названием ' + i, parent_id: null, archived_at: null })), next_cursor: null };
    return route.fulfill({ json: body });
  });
});
for (const width of [320, 390]) test(`selectors use the phone width and show a persistent proportional scrollbar at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 844 });
  await page.goto('/');
  await page.getByRole('button', { name: 'Добавить операцию', exact: true }).click();
  const operation = page.getByRole('dialog', { name: 'Новая операция' });
  for (const label of ['Счет', 'Категория']) {
    await operation.getByRole('button', { name: label, exact: true }).click();
    const picker = page.getByRole('dialog', { name: `Выбор: ${label}`, exact: true });
    const bounds = await picker.boundingBox();
    expect(bounds!.x).toBeGreaterThanOrEqual(8);
    expect(bounds!.width).toBeGreaterThanOrEqual(width - 32);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width - 8);
    const track = picker.locator('.select-scrollbar'), thumb = track.locator('span');
    await expect(track).toBeVisible();
    const trackBounds = (await track.boundingBox())!, thumbBounds = (await thumb.boundingBox())!;
    expect(thumbBounds.height).toBeLessThan(trackBounds.height);
    const list = picker.getByRole('listbox', { name: label, exact: true });
    await list.evaluate(el => { el.scrollTop = el.scrollHeight; });
    await expect.poll(async () => (await thumb.boundingBox())!.y).toBeGreaterThan(thumbBounds.y);
    await page.screenshot({ path: test.info().outputPath(`wide-${label}-${width}.png`) });
    await picker.getByLabel(`Поиск: ${label}`, { exact: true }).fill(label === 'Счет' ? '39' : '79');
    await expect(picker.getByRole('option')).toHaveCount(1);
    await expect(track).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(picker).toHaveCount(0);
    await expect(operation).toBeVisible();
    await expect(operation.getByRole('button', { name: label, exact: true })).toBeFocused();
  }
});
