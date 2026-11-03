import { test, expect, type Page } from '@playwright/test';
const workspace = { id: '00000000-0000-4000-8000-000000000001', name: 'Финансы', sync_generation_id: 'generation' };
const initial = [
  { id: 'a', name: 'Карта', archived_at: null },
  { id: 'archive', name: 'Архив', archived_at: '2026-01-01' },
  { id: 'b', name: 'Наличные', archived_at: null },
  { id: 'c', name: 'Вклад', archived_at: null },
].map(a => ({ ...a, currency: 'EUR', type: 'bank', version: '1', balance_version: '1', posted_balance_minor: '10000' }));
async function manage(page: Page) {
  await page.getByRole('navigation', { name: 'Основная навигация' }).getByRole('link', { name: 'Настройки', exact: true }).click();
  await page.getByRole('button', { name: 'Счета', exact: true }).click();
}
async function dragAccount(page: Page, name: string, targetName: string) {
  const handle = page.getByRole('button', { name: `Перетащить счет ${name}`, exact: true });
  const target = page.getByRole('list', { name: 'Порядок счетов', exact: true }).getByRole('listitem').filter({ has: page.locator('.account-list-copy strong').filter({ hasText: targetName }) });
  const from = (await handle.boundingBox())!, to = (await target.boundingBox())!;
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(from.x + from.width / 2, to.y + to.height / 2, { steps: 12 });
  await page.mouse.up();
}
test('saved account order reaches home and selectors and survives reload, while hidden archived slots stay in place', async ({ page }) => {
  let order = ['a', 'archive', 'b', 'c'], version = '0';
  const writes: Record<string, unknown>[] = [];
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'order@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts/order')) {
      if (route.request().method() === 'PUT') {
        const sent = route.request().postDataJSON(); writes.push(sent);
        expect(sent.expected_version).toBe(version);
        expect(route.request().headers()['idempotency-key']).toBeTruthy();
        order = sent.account_ids; version = String(Number(version) + 1);
        body = {};
      } else body = { version, account_ids: order };
    } else if (path.endsWith('/accounts')) body = { items: initial, next_cursor: null };
    return route.fulfill({ json: body });
  });
  await page.goto('/');
  await manage(page);
  const list = page.getByRole('list', { name: 'Порядок счетов', exact: true });
  await expect(list.locator('li').locator('.account-list-copy strong')).toHaveText(['Карта', 'Наличные', 'Вклад']);
  await expect(page.getByRole('button', { name: 'Поднять счет Карта', exact: true })).toHaveCount(0);
  await dragAccount(page, 'Карта', 'Наличные');
  await expect(list.locator('.account-list-copy strong')).toHaveText(['Наличные', 'Карта', 'Вклад']);
  await page.getByRole('button', { name: 'Сохранить порядок', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Сохранить порядок', exact: true })).toHaveCount(0);
  expect(writes).toEqual([{ expected_version: '0', account_ids: ['b', 'archive', 'a', 'c'] }]);
  await page.getByRole('link', { name: 'Счета', exact: true }).click();
  await expect(page.locator('.screen[aria-label="Счета"] .tile-name')).toHaveText(['Наличные', 'Карта', 'Вклад']);
  await page.getByRole('button', { name: 'Добавить операцию', exact: true }).click();
  await page.getByRole('dialog', { name: 'Новая операция' }).getByRole('button', { name: 'Счет', exact: true }).click();
  const picker = page.getByRole('dialog', { name: 'Выбор: Счет', exact: true });
  await expect(picker.getByRole('option')).toHaveText(['Наличные · EUR', 'Карта · EUR', 'Вклад · EUR']);
  await page.keyboard.press('Escape');
  await page.getByRole('dialog', { name: 'Новая операция' }).getByRole('button', { name: 'Закрыть окно', exact: true }).click();
  await page.reload();
  await expect(page.locator('.screen[aria-label="Счета"] .tile-name')).toHaveText(['Наличные', 'Карта', 'Вклад']);
  await manage(page);
  await page.getByRole('button', { name: 'Все счета', exact: true }).click();
  await expect(list.locator('.account-list-copy strong')).toHaveText(['Наличные', 'Архив', 'Карта', 'Вклад']);
  await dragAccount(page, 'Вклад', 'Карта');
  await page.getByRole('button', { name: 'Отменить порядок', exact: true }).click();
  await expect(list.locator('.account-list-copy strong')).toHaveText(['Наличные', 'Архив', 'Карта', 'Вклад']);
  expect(writes).toHaveLength(1);
});
test('reordering retries the identical saved command after a lost response and reload', async ({ page }) => {
  let order = ['a', 'archive', 'b', 'c'], version = '0';
  const writes: { body: unknown; key?: string }[] = [];
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'order@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts/order')) {
      if (route.request().method() === 'PUT') {
        const sent = route.request().postDataJSON();
        writes.push({ body: sent, key: route.request().headers()['idempotency-key'] });
        order = sent.account_ids; version = '1';
        if (writes.length === 1) return route.abort('connectionfailed');
        body = {};
      } else body = { version, account_ids: order };
    } else if (path.endsWith('/accounts')) body = { items: initial, next_cursor: null };
    return route.fulfill({ json: body });
  });
  await page.goto('/');
  await manage(page);
  await dragAccount(page, 'Карта', 'Наличные');
  await page.getByRole('button', { name: 'Сохранить порядок', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Ответ не получен');
  await page.reload();
  await page.getByRole('button', { name: 'Повторить ту же команду', exact: true }).click();
  await expect.poll(() => writes.length).toBe(2);
  expect(writes[1]).toEqual(writes[0]);
  await expect(page.getByRole('button', { name: 'Повторить ту же команду', exact: true })).toHaveCount(0);
});
test('a concurrent reorder refreshes the server order and preserves the draft until cancellation', async ({ page }) => {
  let version = '0', order = ['a', 'archive', 'b', 'c'];
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'order@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts/order')) {
      if (route.request().method() === 'PUT') {
        version = '1'; order = ['c', 'archive', 'b', 'a'];
        return route.fulfill({ status: 409, json: { code: 'version_conflict', message: 'Порядок изменен' } });
      }
      body = { version, account_ids: order };
    } else if (path.endsWith('/accounts')) body = { items: initial, next_cursor: null };
    return route.fulfill({ json: body });
  });
  await page.goto('/');
  await manage(page);
  await dragAccount(page, 'Карта', 'Наличные');
  await page.getByRole('button', { name: 'Сохранить порядок', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Отмените несохраненный порядок');
  await page.getByRole('button', { name: 'Отменить порядок', exact: true }).click();
  await expect(page.getByRole('list', { name: 'Порядок счетов' }).locator('.account-list-copy strong')).toHaveText(['Вклад', 'Наличные', 'Карта']);
});
async function mockDragAccounts(page: Page, entries = initial) {
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'drag@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts/order')) body = { version: '0', account_ids: entries.map(a => a.id) };
    else if (path.endsWith('/accounts')) body = { items: entries, next_cursor: null };
    return route.fulfill({ json: body });
  });
  await page.goto('/');
  await manage(page);
}
test('touch dragging moves across several rows and touch cancellation leaves the order untouched', async ({ page }) => {
  await mockDragAccounts(page);
  const list = page.getByRole('list', { name: 'Порядок счетов', exact: true });
  const client = await page.context().newCDPSession(page);
  async function touchDrag(cancel: boolean) {
    const handle = (await page.getByRole('button', { name: 'Перетащить счет Карта', exact: true }).boundingBox())!;
    const target = (await list.locator('[data-account-id="c"]').boundingBox())!;
    const x = handle.x + handle.width / 2, start = handle.y + handle.height / 2, end = target.y + target.height / 2;
    await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y: start }] });
    for (let step = 1; step <= 12; step++) await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: start + (end - start) * step / 12 }] });
    await expect(page.locator('.account-drag-ghost')).toBeVisible();
    if (!cancel) await page.screenshot({ path: test.info().outputPath('drag-in-progress.png') });
    await client.send('Input.dispatchTouchEvent', { type: cancel ? 'touchCancel' : 'touchEnd', touchPoints: [] });
  }
  await touchDrag(true);
  await expect(list.locator('.account-list-copy strong')).toHaveText(['Карта', 'Наличные', 'Вклад']);
  await expect(page.getByRole('button', { name: 'Сохранить порядок', exact: true })).toHaveCount(0);
  await touchDrag(false);
  await expect(list.locator('.account-list-copy strong')).toHaveText(['Наличные', 'Вклад', 'Карта']);
  await expect(page.locator('.account-drag-ghost')).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath('drag-touch.png') });
});
test('dragging near the bottom scrolls a long account list automatically', async ({ page }) => {
  const entries = Array.from({ length: 20 }, (_, i) => ({ ...initial[0], id: 'long-' + i, name: 'Счет ' + i }));
  await page.setViewportSize({ width: 320, height: 844 });
  await mockDragAccounts(page, entries);
  const handle = (await page.getByRole('button', { name: 'Перетащить счет Счет 0', exact: true }).boundingBox())!;
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2);
  await page.mouse.down();
  await page.mouse.move(handle.x + handle.width / 2, 744, { steps: 12 });
  await expect.poll(() => page.evaluate(() => scrollY)).toBeGreaterThan(300);
  await expect(page.locator('.account-drag-ghost')).toBeVisible();
  await page.mouse.up();
  await expect(page.getByRole('list', { name: 'Порядок счетов' }).locator('li').first()).not.toHaveAttribute('data-account-id', 'long-0');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test('the drag handle supports keyboard reordering without visible arrow buttons', async ({ page }) => {
  await mockDragAccounts(page);
  const handle = page.getByRole('button', { name: 'Перетащить счет Карта', exact: true });
  await handle.focus();
  await handle.press('ArrowDown');
  await expect(page.getByRole('list', { name: 'Порядок счетов' }).locator('.account-list-copy strong')).toHaveText(['Наличные', 'Карта', 'Вклад']);
  await expect(handle).toBeFocused();
});
