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

test('FX transfer and third-currency fee commit once after lost response', async ({ page, context }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`fx-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  for (const [currency, name, type] of [['EUR', 'Евро', 'bank'], ['USD', 'Доллары', 'card'], ['KWD', 'Комиссии', 'cash']]) {
    await page.getByLabel('Название счета').fill(name);
    await page.getByLabel('Валюта счета', { exact: true }).selectOption(currency);
    await page.getByLabel('Тип счета', { exact: true }).selectOption(type);
    await page.getByLabel(`Начальный остаток, ${currency}`).fill('100');
    await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
    await expect(page.locator('.accounts')).toContainText(name);
  }
  await page.getByLabel('Со счета', { exact: true }).selectOption({ label: 'Евро · EUR' });
  await page.getByLabel('На счет', { exact: true }).selectOption({ label: 'Доллары · USD' });
  await page.getByLabel('Сумма списания, EUR').fill('3');
  await page.getByLabel('Сумма зачисления, USD').fill('1');
  await page.getByLabel('Добавить комиссию', { exact: true }).check();
  await page.getByLabel('Счет комиссии', { exact: true }).selectOption({ label: 'Комиссии · KWD' });
  await page.getByLabel('Комиссия, KWD', { exact: true }).fill('0');
  await page.getByRole('button', { name: 'Сохранить перевод', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Комиссия должна быть больше нуля');
  await expect(page.locator('.accounts li').filter({ hasText: 'Евро' })).toContainText('100,00 EUR');
  await page.getByLabel('Комиссия, KWD', { exact: true }).fill('0,123');
  const commands: { key: string | undefined; body: string | null }[] = [];
  let dropped = false, replayed = false;
  await page.route('**/api/v1/workspaces/*/transactions', async route => {
    if (route.request().method() !== 'POST') { await route.continue(); return; }
    commands.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() });
    const response = await route.fetch();
    expect(response.status()).toBe(201);
    if (!dropped) { dropped = true; await route.abort('connectionfailed'); }
    else { replayed = response.headers()['idempotency-replayed'] === 'true'; await route.fulfill({ response }); }
  });
  await page.getByRole('button', { name: 'Сохранить перевод', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Операция могла сохраниться');
  await page.reload();
  await page.getByRole('button', { name: 'Повторить ту же команду', exact: true }).click();
  await expect.poll(() => replayed).toBe(true);
  await expect(page.getByRole('heading', { name: 'Команда ожидает подтверждения' })).toHaveCount(0);
  expect(commands).toHaveLength(2); expect(commands[1]).toEqual(commands[0]);
  for (const [name, balance] of [['Евро', '97,00 EUR'], ['Доллары', '101,00 USD'], ['Комиссии', '99,877 KWD']]) {
    await expect(page.locator('.accounts li').filter({ hasText: name })).toContainText(balance);
  }
  await expect(page.locator('.history')).toContainText('Комиссия перевода');
  const auth = await (await context.request.get('/api/v1/auth/session')).json();
  const root = `/api/v1/workspaces/${auth.workspace.id}`;
  const history = await (await context.request.get(`${root}/transactions`)).json();
  const transfers = history.items.filter((t: {kind: string}) => t.kind === 'transfer');
  const fees = history.items.filter((t: {kind: string}) => t.kind === 'expense');
  expect(transfers).toHaveLength(1); expect(fees).toHaveLength(1);
  expect(transfers[0].rate).toEqual({ numerator: '1', denominator: '3' });
  expect(transfers[0].fee_transaction_id).toBe(fees[0].id);
  expect(fees[0].parent_transaction_id).toBe(transfers[0].id);
  expect(fees[0].allocations[0].amount_minor).toBe('123');
  const accounts = await (await context.request.get(`${root}/accounts`)).json();
  expect(accounts.items.find((a: {name: string}) => a.name === 'Доллары').type).toBe('card');
  expect(accounts.items.find((a: {name: string}) => a.name === 'Комиссии').type).toBe('cash');
  await page.getByRole('button', { name: `Изменить операцию ${transfers[0].id}`, exact: true }).click();
  const transferEditor = page.getByRole('heading', { name: 'Изменить перевод', exact: true }).locator('..');
  await transferEditor.getByLabel('Сумма списания, EUR').fill('6');
  await transferEditor.getByLabel('Сумма зачисления, USD').fill('2');
  await transferEditor.getByLabel('Комиссия, KWD', { exact: true }).fill('0,246');
  await transferEditor.getByRole('button', { name: 'Добавить часть комиссии', exact: true }).click();
  await transferEditor.getByLabel('Сумма части комиссии 1').fill('0,100');
  await transferEditor.getByLabel('Сумма части комиссии 2').fill('0,146');
  await transferEditor.getByRole('button', { name: 'Сохранить изменения перевода', exact: true }).click();
  await expect(transferEditor).toHaveCount(0);
  await expect(page.locator('.accounts li').filter({ hasText: 'Евро' })).toContainText('94,00 EUR');
  await expect(page.locator('.accounts li').filter({ hasText: 'Доллары' })).toContainText('102,00 USD');
  await expect(page.locator('.accounts li').filter({ hasText: 'Комиссии' })).toContainText('99,754 KWD');
  const replaced = await (await context.request.get(`${root}/transactions/${transfers[0].id}`)).json();
  const replacedFee = await (await context.request.get(`${root}/transactions/${fees[0].id}`)).json();
  expect(replaced.fee_transaction_id).toBe(fees[0].id);
  expect(replacedFee.allocations.map((p: {id: string}) => p.id)).toContain(fees[0].allocations[0].id);
  expect(replacedFee.allocations).toHaveLength(2);
  expect(replaced.entries.map((e: {id: string}) => e.id).sort()).toEqual(transfers[0].entries.map((e: {id: string}) => e.id).sort());
  await page.getByRole('button', { name: `Изменить операцию ${fees[0].id}`, exact: true }).click();
  const feeEditor = page.getByRole('heading', { name: 'Изменить операцию', exact: true }).locator('..');
  await feeEditor.getByLabel('Сумма', { exact: true }).fill('0,300');
  await feeEditor.getByLabel('Сумма части 1').fill('0,100');
  await feeEditor.getByLabel('Сумма части 2').fill('0,200');
  await feeEditor.getByRole('button', { name: 'Сохранить изменения операции', exact: true }).click();
  await expect(feeEditor).toHaveCount(0);
  await expect(page.locator('.accounts li').filter({ hasText: 'Комиссии' })).toContainText('99,700 KWD');
  const parentAfterFee = await (await context.request.get(`${root}/transactions/${transfers[0].id}`)).json();
  expect(BigInt(parentAfterFee.version)).toBe(BigInt(replaced.version) + 1n);
  await page.getByRole('button', { name: `Изменить операцию ${transfers[0].id}`, exact: true }).click();
  await transferEditor.getByLabel('Добавить комиссию', { exact: true }).uncheck();
  await transferEditor.getByRole('button', { name: 'Сохранить изменения перевода', exact: true }).click();
  await expect(transferEditor).toHaveCount(0);
  await expect(page.locator('.accounts li').filter({ hasText: 'Комиссии' })).toContainText('100,000 KWD');
  const withoutFee = await (await context.request.get(`${root}/transactions/${transfers[0].id}`)).json();
  expect(withoutFee.fee_transaction_id).toBeNull();

});

test('expense editor preserves parts, retries PUT and rejects stale versions', async ({ page, context }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`editor-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.getByLabel('Название счета').fill('Редактор');
  await page.getByLabel('Начальный остаток, EUR').fill('100');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('100,00 EUR');
  await page.getByLabel('Сумма', { exact: true }).fill('10');
  await page.getByLabel('Примечание', { exact: true }).fill('Редактируемый расход');
  await page.getByRole('button', { name: 'Добавить часть', exact: true }).click();
  await page.getByLabel('Сумма части 1').fill('4');
  await page.getByLabel('Сумма части 2').fill('6');
  await page.getByRole('button', { name: 'Сохранить операцию', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('90,00 EUR');
  const auth = await (await context.request.get('/api/v1/auth/session')).json();
  const root = `/api/v1/workspaces/${auth.workspace.id}`;
  const before = (await (await context.request.get(`${root}/transactions`)).json()).items.find((t: {kind: string}) => t.kind === 'expense');
  await page.getByRole('button', { name: 'Изменить операцию Редактируемый расход', exact: true }).click();
  const editor = page.getByRole('heading', { name: 'Изменить операцию', exact: true }).locator('..');
  await expect(editor.getByLabel('Тип операции', { exact: true })).toBeDisabled();
  await editor.getByLabel('Сумма', { exact: true }).fill('12');
  await editor.getByLabel('Сумма части 1').fill('4');
  await editor.getByLabel('Сумма части 2').fill('8');
  const commands: { key: string | undefined; body: string | null }[] = [];
  let dropped = false, replayed = false;
  await page.route('**/api/v1/workspaces/*/transactions/*', async route => {
    if (route.request().method() !== 'PUT') { await route.continue(); return; }
    commands.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() });
    const response = await route.fetch();
    expect(response.status()).toBe(200);
    if (!dropped) { dropped = true; await route.abort('connectionfailed'); }
    else { replayed = response.headers()['idempotency-replayed'] === 'true'; await route.fulfill({ response }); }
  });
  await editor.getByRole('button', { name: 'Сохранить изменения операции', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Операция могла сохраниться');
  await page.reload();
  await page.getByRole('button', { name: 'Повторить ту же команду', exact: true }).click();
  await expect.poll(() => replayed).toBe(true);
  await expect(page.getByRole('heading', { name: 'Команда ожидает подтверждения' })).toHaveCount(0);
  expect(commands).toHaveLength(2); expect(commands[1]).toEqual(commands[0]);
  const after = await (await context.request.get(`${root}/transactions/${before.id}`)).json();
  expect(after.allocations.map((p: {id: string}) => p.id).sort()).toEqual(before.allocations.map((p: {id: string}) => p.id).sort());
  expect(after.entries[0].id).toBe(before.entries[0].id);
  expect(after.occurred_at).toBe(before.occurred_at);
  await expect(page.locator('.accounts')).toContainText('88,00 EUR');
  await page.unroute('**/api/v1/workspaces/*/transactions/*');
  await page.getByRole('button', { name: 'Изменить операцию Редактируемый расход', exact: true }).click();
  await expect(editor.getByLabel('Сумма', { exact: true })).toHaveValue('12.00');
  const body = { ...JSON.parse(commands[0].body!), expected_version: after.version, note: 'Правка другого устройства' };
  const concurrent = await context.request.put(`${root}/transactions/${before.id}`, { headers: { Origin: 'https://localhost:8444', 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': auth.workspace.sync_generation_id, 'Idempotency-Key': crypto.randomUUID() }, data: body });
  expect(concurrent.status()).toBe(200);
  await editor.getByLabel('Примечание', { exact: true }).fill('Моя устаревшая правка');
  await editor.getByRole('button', { name: 'Сохранить изменения операции', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Конфликт');
  const final = await (await context.request.get(`${root}/transactions/${before.id}`)).json();
  expect(final.note).toBe('Правка другого устройства');
  await expect(editor.getByLabel('Примечание', { exact: true })).toHaveValue('Моя устаревшая правка');
  await editor.getByRole('button', { name: 'Отменить редактирование', exact: true }).click();
  const creator = page.getByRole('heading', { name: 'Новая операция', exact: true }).locator('..');
  await creator.getByLabel('Тип операции', { exact: true }).selectOption('income');
  await creator.getByLabel('Сумма', { exact: true }).fill('5');
  await creator.getByLabel('Примечание', { exact: true }).fill('Редактируемый доход');
  await creator.getByRole('button', { name: 'Сохранить операцию', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('93,00 EUR');
  await page.getByRole('button', { name: 'Изменить операцию Редактируемый доход', exact: true }).click();
  await editor.getByLabel('Сумма', { exact: true }).fill('7');
  await editor.getByRole('button', { name: 'Сохранить изменения операции', exact: true }).click();
  await expect(editor).toHaveCount(0);
  await expect(page.locator('.accounts')).toContainText('95,00 EUR');

});
