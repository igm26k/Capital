import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { snapshot, processes } from '../e2e/environment';

test('Chrome reads exact account and expense created through native Android forms', async ({ page, context }) => {
  const proof = JSON.parse(readFileSync(process.env.ANDROID_WEB_PROOF!, 'utf8'));
  expect(proof.native_ui).toBe(true);
  for (const key of ['workspace', 'account', 'expense', 'action']) expect(proof[key]).toMatch(/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);
  expect(proof.email).toMatch(/^android-web-[a-f0-9-]+@example\.test$/);
  const before = snapshot(proof.workspace, proof.account, proof.action);
  expect(before).toMatchObject({ posted_balance_minor: '87655', opening_count: '1', expense_count: '1', entry_count: '2', allocation_count: '1', receipt_count: '2', expense_receipt_count: '1' });
  await page.goto('/');
  await page.getByLabel('Электронная почта').fill(proof.email);
  await page.getByLabel('Пароль', { exact: true }).fill('Android web acceptance password');
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  const session = await (await context.request.get('/api/v1/auth/session')).json();
  expect(session.workspace.id).toBe(proof.workspace);
  const checkUI = async () => {
    await expect(page.locator('.accounts li').filter({ hasText: 'Native KWD cash' })).toContainText('87,655 KWD');
    await expect(page.getByText('Native to Chrome expense', { exact: true })).toBeVisible();
    await expect(page.getByTestId(`transaction-${proof.expense}`)).toContainText('Native KWD cash · −12,345 KWD');
  };
  await checkUI();
  const response = await context.request.get(`/api/v1/workspaces/${proof.workspace}/transactions/${proof.expense}`);
  expect(response.status()).toBe(200);
  const transaction = await response.json();
  expect(transaction.id).toBe(proof.expense);
  expect(transaction.version).toBe('1');
  expect(transaction.entries[0].amount_minor).toBe('-12345');
  await page.reload(); await checkUI();
  expect(snapshot(proof.workspace, proof.account, proof.action)).toEqual(before);
  writeFileSync(process.env.ANDROID_WEB_RESULT!, JSON.stringify({ native_ui: true, chrome_ui: true, reload: true, exact_minor: true, sql_unchanged: true, processes: processes() }), { mode: 0o600 });
  await page.getByRole('button', { name: 'Выйти', exact: true }).click();
});
