import { test, expect, type Page } from '@playwright/test';
const walletFile = { name: 'wallet_records.csv', mimeType: 'text/csv', buffer: Buffer.from('account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels\nWallet cash;Groceries;EUR;1.23;1.23;Expense;Cash;Purchase one;2025-09-01T12:00:00.000Z;false;Same shop;\nWallet cash;Groceries;EUR;1.23;1.23;Expense;Cash;Purchase two;2025-09-01T12:00:00.000Z;false;Same shop;\n') };
async function register(page: Page) {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать профиль', exact: true }).click();
  await page.getByLabel('Электронная почта').fill(`wallet-export-${crypto.randomUUID()}@example.test`);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic-browser-password-2026');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  await page.getByRole('navigation').getByRole('link', { name: 'Еще', exact: true }).click();
  await page.locator('summary').filter({ hasText: /^Импорт из Wallet$/ }).click();
}
test('Wallet automatically creates accounts, repeat import reuses them, and CSV export downloads', async ({ page, context }) => {
  await register(page);
  for (let pass = 0; pass < 2; pass++) {
    await page.getByLabel('Файл Wallet CSV').setInputFiles(walletFile);
    await expect(page.getByLabel('Импорт Wallet', { exact: true }).getByRole('combobox')).toHaveCount(0);
    await page.getByRole('button', { name: 'Подготовить импорт Wallet', exact: true }).click();
    await expect(page.getByText(new RegExp(`Новых счетов: ${pass ? 0 : 1}\\.`))).toBeVisible();
    await page.getByRole('button', { name: 'Подтвердить импорт Wallet', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Импорт Wallet завершен' })).toBeVisible();
    await page.getByRole('button', { name: 'Закрыть завершенный импорт Wallet', exact: true }).click();
  }
  const auth = await (await context.request.get('/api/v1/auth/session')).json();
  const accounts = await (await context.request.get(`/api/v1/workspaces/${auth.workspace.id}/accounts`)).json();
  expect(accounts.items).toHaveLength(1);
  expect(accounts.items[0]).toMatchObject({ name: 'Wallet cash', currency: 'EUR', posted_balance_minor: '-246' });
  const history = await (await context.request.get(`/api/v1/workspaces/${auth.workspace.id}/transactions?kind=expense`)).json();
  expect(history.items).toHaveLength(2);
  await page.locator('summary').filter({ hasText: /^Экспорт CSV$/ }).click();
  await expect(page.getByLabel('Колонка даты')).toHaveCount(0);
  await page.getByLabel('Экспорт от').fill('2025-09-01');
  await page.getByLabel('Экспорт до').fill('2025-10-01');
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Скачать CSV', exact: true }).click();
  const result = await download;
  expect(result.suggestedFilename()).toBe('accounting.csv');
  expect(await result.failure()).toBeNull();
  const stream = await result.createReadStream();
  const chunks: Buffer[] = []; for await (const chunk of stream!) chunks.push(Buffer.from(chunk));
  const csv = Buffer.concat(chunks).toString('utf8');
  expect(csv).toContain('Purchase one'); expect(csv).toContain('Purchase two');
});
test('Wallet rejects a generic CSV before sending any financial command', async ({ page }) => {
  await register(page);
  let writes = 0;
  page.on('request', r => { if (r.method() === 'POST' && /\/(accounts|categories|transactions)$/.test(new URL(r.url()).pathname)) writes++; });
  await page.getByLabel('Файл Wallet CSV').setInputFiles({ name: 'other.csv', mimeType: 'text/csv', buffer: Buffer.from('date,amount,payee\n2025-09-01,-1.23,Store\n') });
  await expect(page.getByRole('alert')).toContainText('wallet_records.csv');
  await expect(page.getByRole('button', { name: 'Подготовить импорт Wallet', exact: true })).toHaveCount(0);
  expect(writes).toBe(0);
});
