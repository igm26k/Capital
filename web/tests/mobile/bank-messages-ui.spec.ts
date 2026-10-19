import { test, expect, type Page, type BrowserContext } from '@playwright/test';
const workspace = { id: '00000000-0000-4000-8000-000000000001', name: 'Финансы', sync_generation_id: '00000000-0000-4000-8000-000000000099' };
const account = { id: '00000000-0000-4000-8000-000000000002', name: 'Евро', type: 'bank', currency: 'EUR', posted_balance_minor: '10000', version: '1', balance_version: '9', archived_at: null };
const raw = 'Оплата 12,34 EUR в Кофе, карта *1234';
const template = 'Оплата {amount} {currency} в {merchant}, карта *{card}';
async function mockAPI(context: BrowserContext, dropFirst = false) {
  const commands: { key: string; body: Record<string, unknown> }[] = [], events: Record<string, unknown>[] = [];
  const financial: string[] = [];
  await context.route('**/api/v1/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    if (req.method() === 'POST' && path.endsWith('/source-events')) {
      const data = req.postDataJSON(); commands.push({ key: req.headers()['idempotency-key'], body: data });
      let event = events.find(e => e.external_id === data.external_id && e.package_name === data.package_name);
      if (!event) { const value: Record<string, unknown> = { ...data, workspace_id: workspace.id, state: 'review', version: '1', transaction_id: null, error_code: null }; event = value; events.push(value); }
      if (dropFirst && commands.length === 1) return route.abort('connectionfailed');
      return route.fulfill({ status: 201, json: event });
    }
    if (req.method() !== 'GET') financial.push(path);
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'banks@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts')) body = { items: [account], next_cursor: null };
    else if (path.endsWith('/source-events')) body = { items: events, has_more: false, next_before_id: null };
    return route.fulfill({ json: body });
  });
  return { commands, events, financial };
}
async function settings(page: Page) {
  await page.goto('/#more');
  await page.getByRole('button', { name: 'Группы настроек', exact: true }).click();
  await page.getByRole('navigation', { name: 'Группы настроек' }).getByRole('button', { name: 'Банки и уведомления', exact: true }).click();
}
async function addRule(page: Page, channel: 'sms' | 'push' = 'sms') {
  await page.getByRole('button', { name: 'Добавить банк', exact: true }).click();
  const form = page.getByRole('form', { name: 'Правило банка', exact: true });
  await form.getByLabel('Название банка', { exact: true }).fill('Мой банк');
  await form.getByRole('combobox', { name: 'Тип сообщения', exact: true }).selectOption(channel);
  await form.getByLabel(channel === 'sms' ? 'Отправитель SMS' : 'Приложение-отправитель', { exact: true }).fill('BANK');
  await form.getByRole('textbox', { name: 'Шаблон сообщения', exact: true }).fill(template);
  await form.getByLabel('Последние 4 цифры карты', { exact: true }).fill('1234');
  await form.getByRole('button', { name: 'Сохранить правило', exact: true }).click();
  await expect(form).toHaveCount(0);
  await expect(page.getByRole('article', { name: 'Правило Мой банк' })).toBeVisible();
}
async function recognize(page: Page, time = '2026-10-19T12:00:01') {
  const form = page.getByRole('form', { name: 'Распознавание банковского сообщения', exact: true });
  await form.getByRole('combobox', { name: 'Банк и правило', exact: true }).selectOption({ index: 1 });
  await form.getByLabel('Дата и время сообщения', { exact: true }).fill(time);
  await form.getByRole('textbox', { name: 'Текст сообщения', exact: true }).fill(raw);
  await form.getByRole('button', { name: 'Распознать', exact: true }).click();
  await expect(form.getByLabel('Результат распознавания')).toContainText('12,34 EUR');
  return form;
}
for (const channel of ['sms', 'push'] as const) test(`${channel}: browser rules persist and recognized fields go to review without financial writes`, async ({ page, context }) => {
  const state = await mockAPI(context);
  await settings(page); await addRule(page, channel);
  const form = await recognize(page);
  expect(state.commands).toHaveLength(0);
  if (channel === 'sms') await page.screenshot({ path: '../ops/.runtime/bank-messages-mobile.png', fullPage: true });
  await form.getByRole('button', { name: 'Добавить в предложения', exact: true }).click();
  await expect(page.getByText('Сообщение принято.', { exact: false })).toBeVisible();
  await expect(page.getByRole('article', { name: 'Предложение Кофе', exact: true })).toBeVisible();
  expect(state.commands).toHaveLength(1); expect(state.financial).toEqual([]);
  expect(state.commands[0].body).toMatchObject({ account_id: account.id, amount_minor: '1234', currency: 'EUR', merchant: 'Кофе', card_last_four: '1234' });
  expect(state.commands[0].body.package_name).toMatch(new RegExp(`^web\\.${channel}\\.s[0-9a-f]{64}$`));
  expect(JSON.stringify(state.commands[0].body)).not.toContain(raw);
  await page.reload();
  await expect(page.getByRole('article', { name: 'Правило Мой банк' })).toBeVisible();
  await expect(form.getByRole('textbox', { name: 'Текст сообщения', exact: true })).toHaveValue('');
  await recognize(page);
  await form.getByRole('button', { name: 'Добавить в предложения', exact: true }).click();
  await expect.poll(() => state.commands.length).toBe(2);
  expect(state.commands[1].body.external_id).toBe(state.commands[0].body.external_id); expect(state.events).toHaveLength(1);
  await recognize(page, '2026-10-19T12:00:02');
  await form.getByRole('button', { name: 'Добавить в предложения', exact: true }).click();
  await expect.poll(() => state.events.length).toBe(2);
  expect(state.financial).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test('lost reply survives reload and reuses the exact saved payload and idempotency key', async ({ page, context }) => {
  const state = await mockAPI(context, true);
  await settings(page); await addRule(page); const form = await recognize(page);
  await form.getByRole('button', { name: 'Добавить в предложения', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Ответ не получен');
  await expect(page.getByRole('button', { name: 'Повторить отправку сообщения', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: 'Повторить отправку сообщения', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Повторить отправку сообщения', exact: true }).click();
  await expect(page.getByText('Сообщение принято.', { exact: false })).toBeVisible();
  expect(state.commands).toHaveLength(2); expect(state.commands[1]).toEqual(state.commands[0]); expect(state.events).toHaveLength(1); expect(state.financial).toEqual([]);
});
test('foreign sender and malformed amount cannot create an intake request', async ({ page, context }) => {
  const state = await mockAPI(context);
  await settings(page); await addRule(page);
  const form = await recognize(page);
  await form.getByLabel('Отправитель сообщения', { exact: true }).fill('OTHER');
  await expect(form.getByLabel('Результат распознавания')).toHaveCount(0);
  await form.getByRole('button', { name: 'Распознать', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Отправитель');
  await form.getByLabel('Отправитель сообщения', { exact: true }).fill('BANK');
  await form.getByRole('textbox', { name: 'Текст сообщения', exact: true }).fill(raw.replace('12,34', '1 23,45'));
  await form.getByRole('button', { name: 'Распознать', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('разделители');
  expect(state.commands).toEqual([]);
});
test('stale bank settings in another tab cannot overwrite the latest rules', async ({ page, context }) => {
  await mockAPI(context); await settings(page); await addRule(page);
  const other = await context.newPage(); await settings(other);
  const card = (p: Page) => p.getByRole('article', { name: 'Правило Мой банк' });
  await card(other).getByRole('button', { name: 'Изменить', exact: true }).click();
  await card(page).getByRole('button', { name: 'Изменить', exact: true }).click();
  await page.getByLabel('Название банка', { exact: true }).fill('Новая настройка');
  await page.getByRole('button', { name: 'Сохранить правило', exact: true }).click();
  await other.getByLabel('Название банка', { exact: true }).fill('Устаревшая настройка');
  await other.getByRole('button', { name: 'Сохранить правило', exact: true }).click();
  await expect(other.getByRole('alert')).toContainText('другой вкладке');
  await page.reload(); await expect(page.getByRole('article', { name: 'Правило Новая настройка' })).toBeVisible();
  await other.close();
});
test('bank settings and recognition fit a narrow dark screen and preserve the bank draft across groups', async ({ page, context }) => {
  await mockAPI(context); await page.setViewportSize({ width: 320, height: 568 }); await page.emulateMedia({ colorScheme: 'dark' });
  await settings(page); await addRule(page);
  const form = await recognize(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('article', { name: 'Правило Мой банк' }).getByRole('button', { name: 'Изменить', exact: true }).click();
  await page.getByLabel('Название банка', { exact: true }).fill('Черновик банка');
  const choose = async (name: string) => { await page.getByRole('button', { name: 'Группы настроек', exact: true }).click(); await page.getByRole('navigation', { name: 'Группы настроек' }).getByRole('button', { name, exact: true }).click(); };
  await choose('Профиль'); await choose('Банки и уведомления');
  await expect(page.getByLabel('Название банка', { exact: true })).toHaveValue('Черновик банка');
  await expect(form.getByRole('textbox', { name: 'Текст сообщения', exact: true })).toHaveValue(raw);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
