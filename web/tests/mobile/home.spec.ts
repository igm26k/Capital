import { test, expect } from '@playwright/test';
const workspace = { id: '00000000-0000-4000-8000-000000000001', name: 'Личные финансы', sync_generation_id: 'generation' };
const accounts = [
  { id: 'eur', name: 'Евро карта', currency: 'EUR', type: 'card', posted_balance_minor: '120050', version: '1', balance_version: '1', archived_at: null },
  { id: 'rub', name: 'Рубли', currency: 'RUB', type: 'bank', posted_balance_minor: '5000000', version: '1', balance_version: '1', archived_at: null },
];
const report = { balances: [], daily_balances: [], currencies: [{ currency: 'EUR', expense_minor: '12345', income_minor: '30000' }, { currency: 'RUB', expense_minor: '500000', income_minor: '0' }], categories: [{ currency: 'EUR', category_id: 'food', expense_minor: '10000' }, { currency: 'EUR', category_id: null, expense_minor: '2345' }, { currency: 'RUB', category_id: 'food', expense_minor: '500000' }] };
const transaction = { id: 'expense', kind: 'expense', occurred_at: '2026-10-17T10:00:00Z', payee: 'Супермаркет', entries: [{ account_id: 'eur', amount_minor: '-2345', currency: 'EUR' }], allocations: [{ category_id: 'food' }] };
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', route => {
    const url = new URL(route.request().url()), path = url.pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'home@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts')) body = { items: accounts, next_cursor: null };
    else if (path.endsWith('/categories')) body = { items: [{ id: 'food', name: 'Продукты', parent_id: null, archived_at: null }], next_cursor: null };
    else if (path.endsWith('/reports')) body = report;
    else if (path.endsWith('/transactions') && url.searchParams.get('limit') === '5') body = { items: [transaction], next_cursor: null };
    return route.fulfill({ json: body });
  });
});
test('Wallet home shows live per-currency overview and category shares, with recent records', async ({ page }) => {
  const writes: string[] = [];
  page.on('request', r => { if (r.method() !== 'GET') writes.push(r.url()); });
  await page.goto('/');
  const overview = page.getByRole('region', { name: 'Структура расходов', exact: true });
  await expect(overview).toContainText('123,45 EUR');
  await expect(overview).toContainText('300,00 EUR');
  await expect(overview.getByRole('img')).toHaveAttribute('aria-label', 'Расходы по категориям, EUR');
  await expect(overview.locator('.chart-legend')).toContainText('Продукты');
  await expect(page.getByRole('region', { name: 'Последние записи' })).toContainText('Супермаркет');
  await expect(page.getByRole('region', { name: 'Последние записи' })).toContainText('−23,45 EUR');
  await page.getByRole('button', { name: 'RUB', exact: true }).click();
  await expect(overview).toContainText('5000,00 RUB');
  await expect(overview).not.toContainText('123,45 EUR');
  expect(writes).toEqual([]);
  await page.screenshot({ path: test.info().outputPath('wallet-home-390.png'), fullPage: true });
});
test('Wallet home shortcuts open the working forms, history filters and reports', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Добавить операцию', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Новая операция' }).getByRole('button', { name: 'Добавить', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Отмена', exact: true }).click();
  await page.getByRole('navigation').getByRole('link', { name: 'Счета', exact: true }).click();
  await page.getByRole('button', { name: 'Операции счета Евро карта', exact: true }).click();
  await page.getByText('Фильтры операций', { exact: true }).click();
  await expect(page.getByLabel('Счет истории', { exact: true })).toHaveValue('eur');
  await page.getByRole('navigation').getByRole('link', { name: 'Счета', exact: true }).click();
  await page.getByRole('button', { name: 'Все записи', exact: true }).click();
  await expect(page.getByLabel('Счет истории', { exact: true })).toHaveValue('');
  await page.getByRole('navigation').getByRole('link', { name: 'Счета', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Открыть меню', exact: true })).toHaveCount(0);
  await expect(page.locator('.home-tabs')).toHaveCount(0);
  await page.getByRole('navigation').getByRole('link', { name: 'Настройки', exact: true }).click();
  await page.getByRole('button', { name: 'Группы настроек', exact: true }).click();
  await page.getByRole('navigation', { name: 'Группы настроек' }).getByRole('button', { name: 'Бюджеты и отчеты', exact: true }).click();
  await page.getByText('Бюджеты, отчеты и правила', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Бюджеты и отчеты', exact: true })).toBeVisible();
});
test('overview period requests confirmed operations and can recover from an API error', async ({ page }) => {
  let fail = true;
  await page.route('**/reports?*', route => fail ? route.fulfill({ status: 503, json: {} }) : route.fulfill({ json: report }));
  await page.goto('/');
  await expect(page.getByRole('alert')).toContainText('Не удалось загрузить обзор');
  await expect(page.getByRole('region', { name: 'Структура расходов' })).not.toContainText('0,00 EUR');
  fail = false;
  await page.getByRole('button', { name: 'Повторить загрузку обзора' }).click();
  await expect(page.getByRole('region', { name: 'Структура расходов' })).toContainText('123,45 EUR');
  const sent = page.waitForRequest(r => new URL(r.url()).pathname.endsWith('/reports') && new URL(r.url()).searchParams.get('from')?.slice(8, 10) === '01');
  await page.getByLabel('Период обзора').selectOption('month');
  const url = new URL((await sent).url());
  expect(url.searchParams.get('status')).toBe('posted');
  expect(url.searchParams.get('to')).toMatch(/Z$/);
});
test('Wallet home supports dark mode and long names on a narrow screen', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.setViewportSize({ width: 320, height: 844 });
  await page.route('**/accounts?*', route => route.fulfill({ json: { items: [{ ...accounts[0], name: 'Накопительный счёт с очень длинным названием', posted_balance_minor: '9000000000000000' }, accounts[1]], next_cursor: null } }));
  await page.goto('/');
  await expect(page.getByRole('region', { name: 'Структура расходов' })).toContainText('123,45 EUR');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  for (const selector of ['.tile-main', '.add-account-tile button', '.add-record-fab']) {
    for (const element of await page.locator(selector).all()) expect((await element.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  }
  await page.screenshot({ path: test.info().outputPath('wallet-home-dark-320.png'), fullPage: true });
});
test('reset clears every history filter and pagination, including unapplied fields', async ({ page }) => {
  const requests: string[] = [];
  await page.route('**/tags?*', route => route.fulfill({ json: { items: [{ id: 'tag', name: 'Личное', archived_at: null }], next_cursor: null } }));
  await page.route('**/transactions?*', route => {
    const url = new URL(route.request().url()); requests.push(url.search.slice(1));
    return route.fulfill({ json: { items: [], next_cursor: url.searchParams.has('q') && !url.searchParams.has('cursor') ? 'next-page' : null } });
  });
  await page.goto('/#operations');
  await expect(page.getByRole('button', { name: 'Сбросить фильтры', exact: true })).toBeVisible();
  await page.getByText('Фильтры операций', { exact: true }).click();
  await page.getByLabel('Счет истории', { exact: true }).selectOption('eur');
  await page.getByLabel('Категория истории', { exact: true }).selectOption('food');
  await page.getByLabel('Тег истории', { exact: true }).selectOption('tag');
  await page.getByLabel('Тип в истории', { exact: true }).selectOption('expense');
  await page.getByLabel('Статус в истории', { exact: true }).selectOption('posted');
  await page.getByLabel('С даты', { exact: true }).fill('2026-01-01');
  await page.getByLabel('По дату включительно', { exact: true }).fill('2026-10-01');
  await page.getByLabel('Поиск в истории', { exact: true }).fill('Магазин');
  await page.getByLabel('Операций на странице', { exact: true }).selectOption('100');
  await page.getByRole('button', { name: 'Применить фильтры', exact: true }).click();
  await page.getByRole('button', { name: 'Загрузить еще операции', exact: true }).click();
  await expect.poll(() => requests.some(q => q.includes('cursor=next-page'))).toBe(true);
  await page.getByRole('button', { name: 'Сбросить фильтры', exact: true }).click();
  await expect.poll(() => requests.at(-1)).toBe('limit=25');
  for (const name of ['Счет истории', 'Категория истории', 'Тег истории', 'Тип в истории', 'Статус в истории']) await expect(page.getByLabel(name, { exact: true })).toHaveValue('');
  for (const name of ['С даты', 'По дату включительно', 'Поиск в истории']) await expect(page.getByLabel(name, { exact: true })).toHaveValue('');
  await expect(page.getByLabel('Операций на странице', { exact: true })).toHaveValue('25');
  await expect(page.getByRole('button', { name: 'Загрузить еще операции' })).toHaveCount(0);
  // Also clear a draft while the applied query is already the default.
  await page.getByLabel('Поиск в истории', { exact: true }).fill('Несохраненный фильтр');
  await page.getByLabel('С даты', { exact: true }).fill('2026-01-01');
  const count = requests.length;
  await page.getByRole('button', { name: 'Сбросить фильтры', exact: true }).click();
  await expect.poll(() => requests.length).toBeGreaterThan(count);
  await expect(page.getByLabel('Поиск в истории', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('С даты', { exact: true })).toHaveValue('');
});
