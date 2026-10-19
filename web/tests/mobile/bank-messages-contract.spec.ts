import { test, expect } from '@playwright/test';

test('recognized browser message is accepted by the real source API and confirmed exactly once', async ({ page, context }) => {
  test.skip(process.env.BANK_MESSAGES_BACKEND_TEST !== '1', 'Opt-in integration against the isolated accounting-test server.');
  const origin = 'https://localhost:8444';
  const registered = await context.request.post(`${origin}/api/v1/auth/register`, { ignoreHTTPSErrors: true, headers: { Origin: origin }, data: { email: `bank-web-${crypto.randomUUID()}@example.test`, password: 'synthetic-browser-password-2026', device_name: 'Bank message web test', transport: 'cookie', timezone: 'UTC' } });
  expect(registered.status()).toBe(201);
  const auth = await registered.json(), root = `/api/v1/workspaces/${auth.workspace.id}`;
  const headers = { Origin: origin, 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': auth.workspace.sync_generation_id, 'Idempotency-Key': crypto.randomUUID() };
  const created = await context.request.post(origin + root + '/accounts', { ignoreHTTPSErrors: true, headers, data: { id: crypto.randomUUID(), name: 'Test message EUR', type: 'bank', currency: 'EUR', opening_balance_minor: '10000', opened_at: '2026-01-01T00:00:00Z', occurred_timezone: 'UTC' } });
  expect(created.status()).toBe(201); const account = (await (await context.request.get(origin + root + '/accounts', { ignoreHTTPSErrors: true })).json()).items[0];
  expect(account.id).toBeTruthy();
  await context.route('**/api/v1/**', async route => {
    const req = route.request(), url = new URL(req.url());
    const forwarded = req.headers(); for (const key of ['origin', 'host', 'cookie', 'content-length']) delete forwarded[key];
    const response = await context.request.fetch(origin + url.pathname + url.search, { ignoreHTTPSErrors: true, method: req.method(), headers: { ...forwarded, Origin: origin }, data: req.postDataBuffer() ?? undefined });
    // Cookies stay on the isolated backend origin; the development page receives only the JSON body.
    await route.fulfill({ status: response.status(), contentType: 'application/json', body: await response.body() });
  });
  await page.goto('/#more'); await page.getByRole('button', { name: 'Группы настроек', exact: true }).click();
  await page.getByRole('navigation', { name: 'Группы настроек' }).getByRole('button', { name: 'Банки и уведомления', exact: true }).click();
  await page.getByRole('button', { name: 'Добавить банк', exact: true }).click();
  await page.getByLabel('Название банка', { exact: true }).fill('Test real source');
  await page.getByLabel('Отправитель SMS', { exact: true }).fill('TESTBANK');
  await page.getByRole('textbox', { name: 'Шаблон сообщения', exact: true }).fill('Оплата {amount} {currency} в {merchant}');
  await page.getByRole('button', { name: 'Сохранить правило', exact: true }).click();
  const form = page.getByRole('form', { name: 'Распознавание банковского сообщения', exact: true });
  await form.getByRole('combobox', { name: 'Банк и правило', exact: true }).selectOption({ index: 1 });
  await form.getByLabel('Дата и время сообщения', { exact: true }).fill('2026-10-19T10:00:01');
  await form.getByRole('textbox', { name: 'Текст сообщения', exact: true }).fill('Оплата 12,34 EUR в Synthetic shop');
  await form.getByRole('button', { name: 'Распознать', exact: true }).click();
  await form.getByRole('button', { name: 'Добавить в предложения', exact: true }).click();
  const eventCard = page.getByRole('article', { name: 'Предложение Synthetic shop', exact: true });
  await expect(eventCard).toBeVisible();
  const balance = async () => await (await context.request.get(origin + root + '/accounts/' + account.id, { ignoreHTTPSErrors: true })).json();
  expect((await balance()).posted_balance_minor).toBe('10000'); expect((await balance()).pending_delta_minor).toBe('0');
  await eventCard.getByRole('button', { name: 'Создать предварительную операцию', exact: true }).click();
  await page.getByRole('button', { name: 'Подтвердить действие предложения', exact: true }).click();
  await expect.poll(async () => (await balance()).pending_delta_minor).toBe('-1234');
  await eventCard.getByRole('button', { name: 'Проверить связанную операцию', exact: true }).click();
  await eventCard.getByRole('button', { name: 'Подтвердить предварительную операцию', exact: true }).click();
  await page.getByRole('button', { name: 'Подтвердить действие предложения', exact: true }).click();
  await expect.poll(async () => (await balance()).posted_balance_minor).toBe('8766');
  expect((await balance()).pending_delta_minor).toBe('0');
});
