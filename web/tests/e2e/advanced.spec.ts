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
  await page.getByRole('button', { name: `Изменить операцию ${transfers[0].id}`, exact: true }).click();
  await transferEditor.getByLabel('Добавить комиссию', { exact: true }).check();
  await transferEditor.getByLabel('Счет комиссии', { exact: true }).selectOption({ label: 'Комиссии · KWD' });
  await transferEditor.getByLabel('Комиссия, KWD', { exact: true }).fill('0,100');
  await transferEditor.getByRole('button', { name: 'Сохранить изменения перевода', exact: true }).click();
  await expect(transferEditor).toHaveCount(0);
  await expect(page.locator('.accounts li').filter({ hasText: 'Комиссии' })).toContainText('99,900 KWD');
  const withNewFee = await (await context.request.get(`${root}/transactions/${transfers[0].id}`)).json();
  expect(withNewFee.fee_transaction_id).not.toBe(fees[0].id);
  await page.getByRole('button', { name: `Удалить операцию ${transfers[0].id}`, exact: true }).click();
  const deletion = page.getByRole('heading', { name: 'Удаление операции', exact: true }).locator('..');
  await expect(deletion).toContainText('Комиссия будет удалена вместе с переводом');
  await deletion.getByRole('button', { name: 'Подтвердить удаление операции', exact: true }).click();
  await expect(deletion).toHaveCount(0);
  for (const [name, balance] of [['Евро', '100,00 EUR'], ['Доллары', '100,00 USD'], ['Комиссии', '100,000 KWD']]) {
    await expect(page.locator('.accounts li').filter({ hasText: name })).toContainText(balance);
  }
  expect((await context.request.get(`${root}/transactions/${transfers[0].id}`)).status()).toBe(404);
  expect((await context.request.get(`${root}/transactions/${withNewFee.fee_transaction_id}`)).status()).toBe(404);


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
  const comparison = page.getByRole('heading', { name: 'Конфликт операции', exact: true }).locator('..');
  await expect(comparison).toContainText('Моя устаревшая правка');
  await expect(comparison).toContainText('Правка другого устройства');
  await comparison.getByRole('button', { name: 'Загрузить серверную версию в редактор', exact: true }).click();
  await expect(comparison).toHaveCount(0);
  await expect(editor.getByLabel('Примечание', { exact: true })).toHaveValue('Правка другого устройства');
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

test('account archive keeps history and balances, restore enables new writes', async ({ page, context }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`archive-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.getByLabel('Название счета').fill('Сохраняемый');
  await page.getByLabel('Начальный остаток, EUR').fill('100');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('100,00 EUR');
  await page.getByLabel('Сумма', { exact: true }).fill('10');
  await page.getByLabel('Примечание', { exact: true }).fill('Исторический расход');
  await page.getByRole('button', { name: 'Сохранить операцию', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('90,00 EUR');
  const auth = await (await context.request.get('/api/v1/auth/session')).json();
  const root = `/api/v1/workspaces/${auth.workspace.id}`;
  const before = (await (await context.request.get(`${root}/accounts`)).json()).items[0];
  await page.getByRole('button', { name: 'Изменить счет Сохраняемый', exact: true }).click();
  await page.getByLabel('Новое название счета').fill('Архивный');
  await page.getByLabel('Тип выбранного счета', { exact: true }).selectOption('cash');
  await page.getByLabel('Счет в архиве', { exact: true }).check();
  await page.getByRole('button', { name: 'Сохранить счет', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('Архивный · В архиве');
  await expect(page.locator('.accounts')).toContainText('90,00 EUR');
  await expect(page.getByRole('heading', { name: 'Новая операция' }).locator('..').getByRole('option', { name: 'Архивный · EUR', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Сохранить операцию', exact: true })).toBeDisabled();
  await expect(page.locator('.history')).toContainText('Исторический расход');
  await expect(page.locator('.history')).toContainText('Архивный · −10,00 EUR');
  const archived = await (await context.request.get(`${root}/accounts/${before.id}`)).json();
  expect(archived.archived_at).not.toBeNull(); expect(archived.type).toBe('cash');
  expect(archived.balance_version).toBe(before.balance_version);
  expect(archived.posted_balance_minor).toBe(before.posted_balance_minor);
  await page.reload();
  await page.getByRole('button', { name: 'Изменить счет Архивный', exact: true }).click();
  await page.getByLabel('Счет в архиве', { exact: true }).uncheck();
  await page.getByRole('button', { name: 'Сохранить счет', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Сохранить операцию', exact: true })).toBeEnabled();
  await expect(page.locator('.accounts')).not.toContainText('В архиве');
  await page.getByLabel('Сумма', { exact: true }).fill('1');
  await page.getByRole('button', { name: 'Сохранить операцию', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('89,00 EUR');
});

test('device revoke retries after lost response and invalidates the other cookie', async ({ page, context, browser }) => {
  const email = `devices-${crypto.randomUUID()}@example.test`, password = 'synthetic-browser-password-2026';
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(email);
  await page.getByLabel('Пароль', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByText('Текущее устройство', { exact: true })).toBeVisible();
  const other = await browser.newContext({ ignoreHTTPSErrors: true });
  try {
    const device = '<img src=x onerror="window.accountingXss=true">';
    const login = await other.request.post('https://localhost:8444/api/v1/auth/login', { headers: { Origin: 'https://localhost:8444' }, data: { email, password, device_name: device, transport: 'cookie' } });
    expect(login.status()).toBe(200);
    const auth = await login.json();
    expect((await other.request.get('https://localhost:8444/api/v1/currencies')).status()).toBe(200);
    await page.getByRole('button', { name: 'Обновить устройства', exact: true }).click();
    const row = page.getByTestId(`session-${auth.session.id}`);
    await expect(row).toContainText(device);
    await expect(page.locator('img')).toHaveCount(0);
    let dropped = false;
    const ids: string[] = [];
    await page.route('**/api/v1/sessions/*', async route => {
      if (route.request().method() !== 'DELETE') { await route.continue(); return; }
      ids.push(route.request().url());
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      if (!dropped) { dropped = true; await route.abort('connectionfailed'); }
      else await route.fulfill({ response });
    });
    await row.getByRole('button', { name: `Отключить устройство ${device}`, exact: true }).click();
    await expect(page.getByRole('button', { name: 'Повторить отзыв устройства', exact: true })).toBeVisible();
    expect((await other.request.get('https://localhost:8444/api/v1/currencies')).status()).toBe(401);
    await page.getByRole('button', { name: 'Повторить отзыв устройства', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Повторить отзыв устройства', exact: true })).toHaveCount(0);
    await expect(row).toHaveCount(0);
    expect(ids).toHaveLength(2); expect(ids[1]).toBe(ids[0]);
    expect((await context.request.get('/api/v1/auth/session')).status()).toBe(200);
    await page.unroute('**/api/v1/sessions/*');
    await page.getByRole('button', { name: 'Отключить устройство Веб-браузер (текущее)', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Войти', exact: true })).toBeVisible();
    expect((await context.request.get('/api/v1/auth/session')).status()).toBe(401);
  } finally { await other.close(); }
});

test('history pages and combined filters read the real ledger', async ({ page, context }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`history-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.getByLabel('Название счета').fill('История');
  await page.getByLabel('Начальный остаток, EUR').fill('100');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('100,00 EUR');
  await page.getByLabel('Название категории', { exact: true }).fill('Покупки');
  await page.getByRole('button', { name: 'Создать категорию', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Изменить категорию Покупки', exact: true })).toBeVisible();
  await page.getByLabel('Название тега', { exact: true }).fill('Четные');
  await page.getByRole('button', { name: 'Создать тег', exact: true }).click();
  await expect(page.getByLabel('Четные', { exact: true })).toBeVisible();
  const auth = await (await context.request.get('/api/v1/auth/session')).json();
  const root = `/api/v1/workspaces/${auth.workspace.id}`;
  const account = (await (await context.request.get(`${root}/accounts`)).json()).items[0];
  const category = (await (await context.request.get(`${root}/categories`)).json()).items[0];
  const tag = (await (await context.request.get(`${root}/tags`)).json()).items[0];
  for (let i = 0; i < 12; i++) {
    const response = await context.request.post(`${root}/transactions`, { headers: { Origin: 'https://localhost:8444', 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': auth.workspace.sync_generation_id, 'Idempotency-Key': crypto.randomUUID() }, data: { id: crypto.randomUUID(), kind: 'expense', account_id: account.id, amount_minor: '100', occurred_at: new Date().toISOString(), occurred_timezone: 'UTC', note: `Покупка ${i + 1}`, payee: '', tag_ids: i % 2 === 0 ? [tag.id] : [], allocations: [{ id: crypto.randomUUID(), category_id: category.id, amount_minor: '100' }] } });
    expect(response.status()).toBe(201);
  }
  await page.getByRole('button', { name: 'Обновить', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('88,00 EUR');
  const history = page.getByRole('heading', { name: 'История операций', exact: true }).locator('..');
  await history.getByLabel('Тип в истории', { exact: true }).selectOption('expense');
  await history.getByLabel('Поиск в истории', { exact: true }).fill('Покупка');
  await history.getByLabel('Операций на странице', { exact: true }).selectOption('10');
  await history.getByRole('button', { name: 'Применить фильтры', exact: true }).click();
  await expect(history.locator('.history > li')).toHaveCount(10);
  await history.getByRole('button', { name: 'Загрузить еще операции', exact: true }).click();
  await expect(history.locator('.history > li')).toHaveCount(12);
  expect(new Set(await history.locator('.history > li').evaluateAll(rows => rows.map(row => row.getAttribute('data-testid')))).size).toBe(12);
  await expect(history.getByRole('button', { name: 'Загрузить еще операции', exact: true })).toHaveCount(0);
  await history.getByLabel('Счет истории', { exact: true }).selectOption(account.id);
  await history.getByLabel('Категория истории', { exact: true }).selectOption(category.id);
  await history.getByLabel('Тег истории', { exact: true }).selectOption(tag.id);
  const today = await page.evaluate(() => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; });
  await history.getByLabel('С даты', { exact: true }).fill(today);
  await history.getByLabel('По дату включительно', { exact: true }).fill(today);
  await history.getByRole('button', { name: 'Применить фильтры', exact: true }).click();
  await expect(history.locator('.history > li')).toHaveCount(6);
  await history.getByLabel('Статус в истории', { exact: true }).selectOption('pending');
  await history.getByRole('button', { name: 'Применить фильтры', exact: true }).click();
  await expect(history.getByText('Операций по выбранным условиям нет.', { exact: true })).toBeVisible();
  await history.getByRole('button', { name: 'Сбросить фильтры', exact: true }).click();
  await expect(history.locator('.history > li')).toHaveCount(13);
});

test('partial refund inherits archived categories, retries once and retains part IDs on edit', async ({ page, context }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`refund-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.getByLabel('Название счета').fill('Возвраты');
  await page.getByLabel('Начальный остаток, EUR').fill('100');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('100,00 EUR');
  await page.getByLabel('Название категории', { exact: true }).fill('Старая категория');
  await page.getByRole('button', { name: 'Создать категорию', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Изменить категорию Старая категория', exact: true })).toBeVisible();
  await page.getByLabel('Сумма', { exact: true }).fill('10');
  await page.getByLabel('Категория', { exact: true }).selectOption({ label: 'Старая категория' });
  await page.getByRole('button', { name: 'Добавить часть', exact: true }).click();
  await page.getByLabel('Сумма части 1').fill('4');
  await page.getByLabel('Сумма части 2').fill('6');
  await page.getByLabel('Примечание', { exact: true }).fill('Исходная покупка');
  await page.getByRole('button', { name: 'Сохранить операцию', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('90,00 EUR');
  await page.getByRole('button', { name: 'Изменить категорию Старая категория', exact: true }).click();
  await page.getByLabel('Категория в архиве').check();
  await page.getByRole('button', { name: 'Сохранить категорию', exact: true }).click();
  await expect(page.getByLabel('Категория', { exact: true }).getByRole('option', { name: 'Старая категория', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Возврат по операции Исходная покупка', exact: true }).click();
  const form = page.getByRole('heading', { name: 'Новый возврат', exact: true }).locator('..');
  await form.getByLabel('Возврат части 1').fill('100');
  await form.getByRole('button', { name: 'Сохранить возврат', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('не превышать доступный остаток');
  await form.getByLabel('Возврат части 1').fill('2');
  await form.getByLabel('Возврат части 2').fill('3');
  await form.getByLabel('Описание возврата', { exact: true }).fill('Частичный возврат');
  const commands: { key: string | undefined; body: string | null }[] = [];
  let dropped = false, replayed = false;
  await page.route('**/api/v1/workspaces/*/transactions', async route => {
    if (route.request().method() !== 'POST') { await route.continue(); return; }
    commands.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() });
    const response = await route.fetch(); expect(response.status()).toBe(201);
    if (!dropped) { dropped = true; await route.abort('connectionfailed'); }
    else { replayed = response.headers()['idempotency-replayed'] === 'true'; await route.fulfill({ response }); }
  });
  await form.getByRole('button', { name: 'Сохранить возврат', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Операция могла сохраниться');
  await page.reload();
  await page.getByRole('button', { name: 'Повторить ту же команду', exact: true }).click();
  await expect.poll(() => replayed).toBe(true);
  await expect(page.getByRole('heading', { name: 'Команда ожидает подтверждения' })).toHaveCount(0);
  expect(commands).toHaveLength(2); expect(commands[1]).toEqual(commands[0]);
  await expect(page.locator('.accounts')).toContainText('95,00 EUR');
  const auth = await (await context.request.get('/api/v1/auth/session')).json();
  const root = `/api/v1/workspaces/${auth.workspace.id}`;
  const history = (await (await context.request.get(`${root}/transactions`)).json()).items;
  const refund = history.find((t: {kind: string}) => t.kind === 'refund');
  const expense = history.find((t: {kind: string}) => t.kind === 'expense');
  expect(history.filter((t: {kind: string}) => t.kind === 'refund')).toHaveLength(1);
  for (const allocation of refund.allocations) {
    expect(allocation.category_id).toBe(expense.allocations.find((p: {id: string}) => p.id === allocation.original_allocation_id).category_id);
  }
  await page.getByRole('button', { name: 'Изменить операцию Частичный возврат', exact: true }).click();
  const editor = page.getByRole('heading', { name: 'Изменить возврат', exact: true }).locator('..');
  await editor.getByLabel('Возврат части 1').fill('1');
  await editor.getByLabel('Возврат части 2').fill('2');
  await editor.getByRole('button', { name: 'Сохранить изменения возврата', exact: true }).click();
  await expect(editor).toHaveCount(0);
  await expect(page.locator('.accounts')).toContainText('93,00 EUR');
  const after = await (await context.request.get(`${root}/transactions/${refund.id}`)).json();
  expect(after.allocations.map((p: {id: string}) => p.id).sort()).toEqual(refund.allocations.map((p: {id: string}) => p.id).sort());
  const parent = await (await context.request.get(`${root}/transactions/${expense.id}`)).json();
  expect(parent.allocations.reduce((sum: bigint, p: {remaining_refundable_minor: string}) => sum + BigInt(p.remaining_refundable_minor), 0n)).toBe(700n);
  await page.getByRole('button', { name: 'Удалить операцию Исходная покупка', exact: true }).click();
  const deletion = page.getByRole('heading', { name: 'Удаление операции', exact: true }).locator('..');
  await deletion.getByRole('button', { name: 'Подтвердить удаление операции', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Конфликт');
  expect((await context.request.get(`${root}/transactions/${expense.id}`)).status()).toBe(200);
  await expect(page.locator('.accounts')).toContainText('93,00 EUR');
  await deletion.getByRole('button', { name: 'Отменить удаление', exact: true }).click();
  const deletions: {key: string | undefined; body: string | null}[] = [];
  let deleteDropped = false, deleteReplayed = false;
  await page.route('**/api/v1/workspaces/*/transactions/*', async route => {
    if (route.request().method() !== 'DELETE') { await route.continue(); return; }
    deletions.push({key: route.request().headers()['idempotency-key'], body: route.request().postData()});
    const response = await route.fetch(); expect(response.status()).toBe(200);
    if (!deleteDropped) { deleteDropped = true; await route.abort('connectionfailed'); }
    else { deleteReplayed = response.headers()['idempotency-replayed'] === 'true'; await route.fulfill({response}); }
  });
  await page.getByRole('button', { name: 'Удалить операцию Частичный возврат', exact: true }).click();
  await deletion.getByRole('button', { name: 'Подтвердить удаление операции', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Операция могла сохраниться');
  await page.reload();
  await page.getByRole('button', { name: 'Повторить ту же команду', exact: true }).click();
  await expect.poll(() => deleteReplayed).toBe(true);
  await expect(page.getByRole('heading', { name: 'Команда ожидает подтверждения' })).toHaveCount(0);
  expect(deletions).toHaveLength(2); expect(deletions[1]).toEqual(deletions[0]);
  await expect(page.locator('.accounts')).toContainText('90,00 EUR');
  expect((await context.request.get(`${root}/transactions/${refund.id}`)).status()).toBe(404);
  const released = await (await context.request.get(`${root}/transactions/${expense.id}`)).json();
  expect(released.allocations.reduce((sum: bigint, p: {remaining_refundable_minor: string}) => sum + BigInt(p.remaining_refundable_minor), 0n)).toBe(1000n);
  await page.unroute('**/api/v1/workspaces/*/transactions/*');
  await page.getByRole('button', { name: 'Удалить операцию Исходная покупка', exact: true }).click();
  await deletion.getByRole('button', { name: 'Подтвердить удаление операции', exact: true }).click();
  await expect(deletion).toHaveCount(0);
  await expect(page.locator('.accounts')).toContainText('100,00 EUR');

});

test('adjustment rejects stale balance and retries the same target after reload', async ({ page, context }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`adjustment-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.getByLabel('Название счета').fill('Сверка');
  await page.getByLabel('Начальный остаток, EUR').fill('100');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('100,00 EUR');
  await page.getByLabel('Фактический остаток, EUR').fill('80');
  await page.getByLabel('Причина корректировки', { exact: true }).fill('Сверка наличных');
  const auth = await (await context.request.get('/api/v1/auth/session')).json();
  const root = `/api/v1/workspaces/${auth.workspace.id}`;
  const account = (await (await context.request.get(`${root}/accounts`)).json()).items[0];
  const income = await context.request.post(`${root}/transactions`, { headers: { Origin: 'https://localhost:8444', 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': auth.workspace.sync_generation_id, 'Idempotency-Key': crypto.randomUUID() }, data: { id: crypto.randomUUID(), kind: 'income', account_id: account.id, amount_minor: '100', occurred_at: new Date().toISOString(), occurred_timezone: 'UTC', note: '', payee: '', tag_ids: [], allocations: [{ id: crypto.randomUUID(), category_id: null, amount_minor: '100' }] } });
  expect(income.status()).toBe(201);
  await page.getByRole('button', { name: 'Сохранить корректировку', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Конфликт');
  const staleHistory = (await (await context.request.get(`${root}/transactions`)).json()).items;
  expect(staleHistory.filter((t: {kind: string}) => t.kind === 'adjustment')).toHaveLength(0);
  await page.getByRole('button', { name: 'Обновить', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('101,00 EUR');
  await page.getByLabel('Фактический остаток, EUR').fill('80');
  await page.getByLabel('Примечание корректировки', { exact: true }).fill('Проверенная корректировка');
  const commands: { key: string | undefined; body: string | null }[] = [];
  let dropped = false, replayed = false;
  await page.route('**/api/v1/workspaces/*/accounts/*/adjustments', async route => {
    commands.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() });
    const response = await route.fetch(); expect(response.status()).toBe(201);
    if (!dropped) { dropped = true; await route.abort('connectionfailed'); }
    else { replayed = response.headers()['idempotency-replayed'] === 'true'; await route.fulfill({ response }); }
  });
  await page.getByRole('button', { name: 'Сохранить корректировку', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Операция могла сохраниться');
  await page.reload();
  await page.getByRole('button', { name: 'Повторить ту же команду', exact: true }).click();
  await expect.poll(() => replayed).toBe(true);
  await expect(page.getByRole('heading', { name: 'Команда ожидает подтверждения' })).toHaveCount(0);
  expect(commands).toHaveLength(2); expect(commands[1]).toEqual(commands[0]);
  await expect(page.locator('.accounts')).toContainText('80,00 EUR');
  const history = (await (await context.request.get(`${root}/transactions`)).json()).items;
  const adjustments = history.filter((t: {kind: string}) => t.kind === 'adjustment');
  expect(adjustments).toHaveLength(1); expect(adjustments[0].entries[0].amount_minor).toBe('-2100');
  const beforeMetadata = await (await context.request.get(`${root}/accounts/${account.id}`)).json();
  await page.getByRole('button', { name: 'Изменить операцию Проверенная корректировка', exact: true }).click();
  const editor = page.getByRole('heading', { name: 'Изменить примечание корректировки', exact: true }).locator('..');
  await editor.getByLabel('Примечание корректировки', { exact: true }).fill('Уточненное описание');
  await editor.getByRole('button', { name: 'Сохранить примечание корректировки', exact: true }).click();
  await expect(editor).toHaveCount(0);
  const final = await (await context.request.get(`${root}/transactions/${adjustments[0].id}`)).json();
  const afterMetadata = await (await context.request.get(`${root}/accounts/${account.id}`)).json();
  expect(final.reason).toBe('Сверка наличных'); expect(final.note).toBe('Уточненное описание');
  expect(final.entries).toEqual(adjustments[0].entries); expect(afterMetadata.balance_version).toBe(beforeMetadata.balance_version);
});

test('bulk classification rejects the whole stale package and replays one atomic write', async ({ page, context }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`bulk-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.getByLabel('Название счета').fill('Пакет');
  await page.getByLabel('Начальный остаток, EUR').fill('100');
  await page.getByRole('button', { name: 'Создать счет', exact: true }).click();
  await expect(page.locator('.accounts')).toContainText('100,00 EUR');
  for (let i = 1; i <= 2; i++) {
    await page.getByLabel('Сумма', { exact: true }).fill('1');
    await page.getByLabel('Примечание', { exact: true }).fill(`Пакет ${i}`);
    await page.getByRole('button', { name: 'Сохранить операцию', exact: true }).click();
    await expect(page.locator('.accounts')).toContainText(`${100 - i},00 EUR`);
  }
  await page.getByLabel('Название категории', { exact: true }).fill('Массовая категория');
  await page.getByRole('button', { name: 'Создать категорию', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Изменить категорию Массовая категория', exact: true })).toBeVisible();
  await page.getByLabel('Название тега', { exact: true }).fill('Пакетный');
  await page.getByRole('button', { name: 'Создать тег', exact: true }).click();
  await expect(page.getByLabel('Пакетный', { exact: true })).toBeVisible();
  const auth = await (await context.request.get('/api/v1/auth/session')).json();
  const root = `/api/v1/workspaces/${auth.workspace.id}`;
  const source = (await (await context.request.get(`${root}/transactions`)).json()).items.filter((t: {kind: string}) => t.kind === 'expense');
  const accountBefore = (await (await context.request.get(`${root}/accounts`)).json()).items[0];
  await page.getByLabel('Выбрать операцию Пакет 1', { exact: true }).check();
  await page.getByLabel('Выбрать операцию Пакет 2', { exact: true }).check();
  await page.getByLabel('Категория выбранных операций', { exact: true }).selectOption({ label: 'Массовая категория' });
  await page.getByLabel('Массовый тег Пакетный', { exact: true }).check();
  const changed = source[0];
  const concurrent = await context.request.put(`${root}/transactions/${changed.id}`, { headers: { Origin: 'https://localhost:8444', 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': auth.workspace.sync_generation_id, 'Idempotency-Key': crypto.randomUUID() }, data: { kind: 'expense', expected_version: changed.version, expected_parent_version: null, account_id: changed.entries[0].account_id, amount_minor: '100', occurred_at: changed.occurred_at, occurred_timezone: changed.occurred_timezone, note: 'Чужая правка пакета', payee: '', allocations: changed.allocations.map((p: {id: string}) => ({id: p.id, category_id: null, amount_minor: '100'})), tag_ids: [] } });
  expect(concurrent.status()).toBe(200);
  await page.getByRole('button', { name: 'Применить ко всем выбранным', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Конфликт');
  const rejected = (await (await context.request.get(`${root}/transactions`)).json()).items.filter((t: {kind: string}) => t.kind === 'expense');
  for (const transaction of rejected) {
    expect(transaction.allocations[0].category_id).toBeNull(); expect(transaction.tag_ids).toEqual([]);
    const original = source.find((t: {id: string}) => t.id === transaction.id);
    expect(BigInt(transaction.version)).toBe(BigInt(original.version) + (transaction.id === changed.id ? 1n : 0n));
  }
  await page.getByRole('button', { name: 'Обновить', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Массовая классификация', exact: true })).toHaveCount(0);
  for (const transaction of rejected) await page.getByTestId(`transaction-${transaction.id}`).getByRole('checkbox').check();
  await page.getByLabel('Категория выбранных операций', { exact: true }).selectOption({ label: 'Массовая категория' });
  await page.getByLabel('Массовый тег Пакетный', { exact: true }).check();
  const commands: {key: string | undefined; body: string | null}[] = [];
  let dropped = false, replayed = false;
  await page.route('**/api/v1/workspaces/*/transactions/classification', async route => {
    commands.push({key: route.request().headers()['idempotency-key'], body: route.request().postData()});
    const response = await route.fetch(); expect(response.status()).toBe(200);
    if (!dropped) { dropped = true; await route.abort('connectionfailed'); }
    else { replayed = response.headers()['idempotency-replayed'] === 'true'; await route.fulfill({response}); }
  });
  await page.getByRole('button', { name: 'Применить ко всем выбранным', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Операция могла сохраниться');
  await page.reload();
  await page.getByRole('button', { name: 'Повторить ту же команду', exact: true }).click();
  await expect.poll(() => replayed).toBe(true);
  await expect(page.getByRole('heading', { name: 'Команда ожидает подтверждения' })).toHaveCount(0);
  expect(commands).toHaveLength(2); expect(commands[1]).toEqual(commands[0]);
  const final = (await (await context.request.get(`${root}/transactions`)).json()).items.filter((t: {kind: string}) => t.kind === 'expense');
  const category = (await (await context.request.get(`${root}/categories`)).json()).items[0];
  const tag = (await (await context.request.get(`${root}/tags`)).json()).items[0];
  for (const transaction of final) {
    expect(transaction.allocations[0].category_id).toBe(category.id); expect(transaction.tag_ids).toEqual([tag.id]);
    expect(BigInt(transaction.version)).toBe(BigInt(rejected.find((t: {id: string}) => t.id === transaction.id).version) + 1n);
  }
  const accountAfter = (await (await context.request.get(`${root}/accounts`)).json()).items[0];
  expect(accountAfter.balance_version).toBe(accountBefore.balance_version);
  expect(accountAfter.posted_balance_minor).toBe('9800');
});
