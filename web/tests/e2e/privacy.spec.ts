import { test, expect } from '@playwright/test';

test('full JSON export excludes credentials and explicit profile deletion revokes access', async ({ page, context }) => {
  const email = `privacy-${crypto.randomUUID()}@example.test`, password = 'synthetic-browser-password-2026';
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(email);
  await page.getByLabel('Пароль', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.getByLabel('Название счета').fill('Privacy cash');
  await page.getByLabel('Начальный остаток, EUR').fill('100,00');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.locator('.accounts strong')).toHaveText('100,00 EUR');
  await page.getByText('Экспорт и удаление профиля', { exact: true }).click();
  const pending = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Скачать все финансовые данные JSON', exact: true }).click();
  const download = await pending;
  expect(download.suggestedFilename()).toBe('accounting-user-data.json');
  const stream = await download.createReadStream();
  if (!stream) throw new Error('Export stream unavailable');
  let raw = ''; for await (const chunk of stream) raw += chunk.toString();
  const data = JSON.parse(raw);
  expect(data.format).toBe('accounting-user-data-v1');
  expect(data.profile.email).toBe(email);
  expect(data.workspaces).toHaveLength(1);
  expect(data.workspaces[0].accounts[0].name).toBe('Privacy cash');
  expect(data.workspaces[0].entries[0].amount_minor).toBe('10000');
  expect(raw).not.toContain('password_hash'); expect(raw).not.toContain('token_hash');
  await page.getByRole('button', { name: 'Удалить профиль и личные данные', exact: true }).click();
  await page.getByLabel('Пароль для удаления').fill('wrong-password');
  await page.getByLabel('Введите DELETE для подтверждения').fill('DELETE');
  await page.getByRole('button', { name: 'Подтвердить удаление профиля', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.locator('.accounts strong')).toHaveText('100,00 EUR');
  await page.getByLabel('Пароль для удаления').fill(password);
  await page.getByRole('button', { name: 'Подтвердить удаление профиля', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Войти', exact: true })).toBeVisible();
  expect((await context.request.get('/api/v1/auth/session')).status()).toBe(401);
});

for (const screen of ['analytics', 'csv', 'privacy'] as const) {
  test(`actual401 in ${screen} hides cached financial data`, async ({ page, context }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
    await page.getByLabel('Электронная почта').fill(`expired-${screen}-${crypto.randomUUID()}@example.test`);
    await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
    await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
    await page.getByLabel('Название счета').fill('Private cached cash');
    await page.getByLabel('Начальный остаток, EUR').fill('100,00');
    await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
    await expect(page.locator('.accounts strong')).toHaveText('100,00 EUR');
    const auth = await (await context.request.get('/api/v1/auth/session')).json();
    const logout = await context.request.post('/api/v1/auth/logout', { headers: { Origin: 'https://localhost:8444', 'X-CSRF-Token': auth.csrf_token } });
    expect(logout.status()).toBe(200);
    // The page still has cached finance; only the next actual API401 invalidates it.
    await expect(page.locator('.accounts strong')).toHaveText('100,00 EUR');
    if (screen === 'analytics') await page.getByText('Бюджеты, отчеты и правила', { exact: true }).click();
    else if (screen === 'csv') {
      await page.getByText('Импорт и экспорт CSV', { exact: true }).click();
      await page.getByRole('button', { name: 'Скачать CSV', exact: true }).click();
    } else {
      await page.getByText('Экспорт и удаление профиля', { exact: true }).click();
      await page.getByRole('button', { name: 'Скачать все финансовые данные JSON', exact: true }).click();
    }
    await expect(page.getByRole('button', { name: 'Войти', exact: true })).toBeVisible();
    await expect(page.locator('.accounts')).toHaveCount(0);
  });
}
