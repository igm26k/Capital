import { test, expect } from '@playwright/test';

test('real category tree, tags, exact splits, archive and immutable PUT retry', async ({ page, context }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`classification-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.getByLabel('Название счета').fill('Кошелек');
  await page.getByLabel('Начальный остаток, EUR').fill('100,00');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.locator('.accounts strong')).toHaveText('100,00 EUR');
  await page.getByLabel('Название категории', { exact: true }).fill('Дом');
  await page.getByRole('button', { name: 'Создать категорию', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Изменить категорию Дом', exact: true })).toBeVisible();
  await page.getByLabel('Название категории', { exact: true }).fill('Продукты');
  await page.getByLabel('Родительская категория').selectOption({ label: 'Дом' });
  await page.getByRole('button', { name: 'Создать категорию', exact: true }).click();
  await expect(page.getByRole('option', { name: 'Дом / Продукты', exact: true }).first()).toBeAttached();
  const text = '<img src=x onerror="window.accountingXss=true">';
  await page.getByLabel('Название тега', { exact: true }).fill(text);
  await page.getByRole('button', { name: 'Создать тег', exact: true }).click();
  await expect(page.getByLabel(text, { exact: true })).toBeVisible();
  await page.getByLabel('Сумма', { exact: true }).fill('10,01');
  await page.getByLabel('Категория', { exact: true }).selectOption({ label: 'Дом / Продукты' });
  await page.getByRole('button', { name: 'Добавить часть', exact: true }).click();
  await page.getByLabel('Сумма части 1').fill('4,00');
  await page.getByLabel('Сумма части 2').fill('6,00');
  await page.getByLabel('Категория части 2').selectOption({ label: 'Дом' });
  await page.getByLabel(text, { exact: true }).check();
  await page.getByRole('button', { name: 'Сохранить операцию', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Сумма частей должна точно совпадать');
  await expect(page.locator('.accounts strong')).toHaveText('100,00 EUR');
  await page.getByLabel('Сумма части 2').fill('6,01');
  await page.getByRole('button', { name: 'Сохранить операцию', exact: true }).click();
  await expect(page.locator('.accounts strong')).toHaveText('89,99 EUR');
  await expect(page.locator('.history')).toContainText('Дом / Продукты · 4,00 EUR');
  await expect(page.locator('.history')).toContainText('Дом · 6,01 EUR');
  const auth = await (await context.request.get('/api/v1/auth/session')).json();
  const root = `/api/v1/workspaces/${auth.workspace.id}`;
  const history = await (await context.request.get(`${root}/transactions`)).json();
  const expense = history.items.find((item: { kind: string }) => item.kind === 'expense');
  expect(expense.allocations.map((item: { amount_minor: string }) => item.amount_minor).sort()).toEqual(['400', '601']);
  expect(expense.tag_ids).toHaveLength(1);
  await page.getByRole('button', { name: 'Изменить категорию Продукты', exact: true }).click();
  await page.getByLabel('Категория в архиве').check();
  const commands: { key: string | undefined; body: string | null }[] = [];
  let dropped = false, replayed = false;
  await page.route('**/api/v1/workspaces/*/categories/*', async route => {
    if (route.request().method() !== 'PUT') { await route.continue(); return; }
    commands.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() });
    const response = await route.fetch();
    expect(response.status()).toBe(200);
    if (!dropped) { dropped = true; await route.abort('connectionfailed'); }
    else { replayed = response.headers()['idempotency-replayed'] === 'true'; await route.fulfill({ response }); }
  });
  await page.getByRole('button', { name: 'Сохранить категорию', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Повторить ту же команду', exact: true })).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('Операция могла сохраниться');
  await page.reload();
  await page.getByRole('button', { name: 'Повторить ту же команду', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Повторить ту же команду', exact: true })).toHaveCount(0);
  await expect.poll(() => replayed).toBe(true);
  await expect(page.getByRole('heading', { name: 'Команда ожидает подтверждения' })).toHaveCount(0);
  expect(commands).toHaveLength(2); expect(commands[1]).toEqual(commands[0]);
  await expect(page.getByLabel('Категория', { exact: true }).getByRole('option', { name: 'Дом / Продукты', exact: true })).toHaveCount(0);
  await expect(page.locator('.history')).toContainText('Дом / Продукты · 4,00 EUR');
  await page.getByRole('button', { name: `Изменить тег ${text}`, exact: true }).click();
  await page.getByLabel('Тег в архиве').check();
  await page.getByRole('button', { name: 'Сохранить тег', exact: true }).click();
  await expect(page.getByLabel(text, { exact: true })).toHaveCount(0);
  await expect(page.locator('.history')).toContainText(text);
  await expect(page.locator('img')).toHaveCount(0);
  expect(await page.evaluate(() => Reflect.get(window, 'accountingXss'))).toBeUndefined();
  await page.getByRole('button', { name: 'Изменить категорию Продукты', exact: true }).click();
  await page.getByLabel('Категория в архиве').uncheck();
  await page.getByRole('button', { name: 'Сохранить категорию', exact: true }).click();
  await expect(page.getByLabel('Категория', { exact: true }).getByRole('option', { name: 'Дом / Продукты', exact: true })).toBeAttached();
  await expect(page.locator('.accounts strong')).toHaveText('89,99 EUR');
});

test('real transfer keeps exact balances and survives reload', async ({ page, context }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`transfer-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  for (const name of ['Основной', 'Резерв']) {
    await page.getByLabel('Название счета').fill(name);
    await page.getByLabel('Начальный остаток, EUR').fill('100,00');
    await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
    await expect(page.locator('.accounts')).toContainText(name);
  }
  await page.getByLabel('Со счета', { exact: true }).selectOption({ label: 'Основной · EUR' });
  await page.getByLabel('На счет', { exact: true }).selectOption({ label: 'Резерв · EUR' });
  await page.getByLabel('Сумма списания, EUR').fill('10,01');
  await page.getByRole('button', { name: 'Сохранить перевод', exact: true }).click();
  await expect(page.locator('.accounts li').filter({ hasText: 'Основной' })).toContainText('89,99 EUR');
  await expect(page.locator('.accounts li').filter({ hasText: 'Резерв' })).toContainText('110,01 EUR');
  await page.reload();
  await expect(page.locator('.history')).toContainText('Перевод');
  const auth = await (await context.request.get('/api/v1/auth/session')).json();
  const history = await (await context.request.get(`/api/v1/workspaces/${auth.workspace.id}/transactions`)).json();
  expect(history.items.filter((t: {kind: string}) => t.kind === 'transfer')).toHaveLength(1);
  expect(history.items.filter((t: {kind: string}) => ['income', 'expense'].includes(t.kind))).toHaveLength(0);
});
