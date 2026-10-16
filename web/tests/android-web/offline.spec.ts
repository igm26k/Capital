import { test, expect } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { snapshot, processes } from '../e2e/environment';

test('Chrome reads the ledger after offline Android CRUD and cold queue recovery', async ({ page, context }) => {
  test.skip(!process.env.ANDROID_OFFLINE_WEB_PROOF, 'Requires the native offline host driver');
  const proof = JSON.parse(readFileSync(process.env.ANDROID_OFFLINE_WEB_PROOF!, 'utf8'));
  for (const key of ['workspace', 'account', 'income', 'income_action']) {
    expect(proof[key]).toMatch(/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);
  }
  expect(proof.email).toMatch(/^offline-tag-[a-f0-9-]+@example\.test$/);
  expect(proof.stage).toBe('verified');
  for (const key of ['local_ui', 'cold_recovered', 'command_preserved', 'pull_covered', 'sql_no_duplicate']) expect(proof[key]).toBe(true);
  expect(proof.actions).toHaveLength(36);
  const before = snapshot(proof.workspace, proof.account, proof.income_action);
  expect(before).toMatchObject({ posted_balance_minor: '124456', account_version: '24', balance_version: '21', opening_count: '2', receipt_count: '37', group_count: '37', head_sequence: '37', expense_receipt_count: '1' });

  await page.goto('/');
  await page.getByLabel('Электронная почта').fill(proof.email);
  await page.getByLabel('Пароль', { exact: true }).fill('synthetic offline tag password');
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
  const authResponse = await context.request.get('/api/v1/auth/session');
  expect(authResponse.status()).toBe(200);
  expect((await authResponse.json()).workspace.id).toBe(proof.workspace);
  const root = `/api/v1/workspaces/${proof.workspace}`;
  const checkUI = async () => {
    await expect(page.locator('.accounts li').filter({ hasText: 'Renamed offline cash' })).toContainText('124,456 KWD');
    await expect(page.locator('.accounts li').filter({ hasText: 'Offline target' })).toContainText('10,000 KWD');
    await expect(page.locator('.history > li')).toHaveCount(3);
    const income = page.getByTestId(`transaction-${proof.income}`);
    await expect(income).toContainText('Income after adjustment');
    await expect(income).toContainText('Renamed offline cash · 1,000 KWD');
    await expect(income).toContainText('Offline parent · 1,000 KWD');
    await expect(income).toContainText('Теги: Offline second tag');
    await expect(page.locator('.history')).not.toContainText('Edited offline expense');
    await expect(page.locator('.history')).not.toContainText('Edited offline income');
    await expect(page.locator('.history')).not.toContainText('Offline transfer');
    await expect(page.locator('.history')).not.toContainText('Edited offline adjustment');
  };
  await checkUI();
  const response = await context.request.get(`${root}/transactions?limit=100`);
  expect(response.status()).toBe(200);
  const records = await response.json();
  expect(records.next_cursor).toBeNull();
  expect(records.items.map((item: { kind: string }) => item.kind).sort()).toEqual(['income', 'opening', 'opening']);
  const income = records.items.find((item: { id: string }) => item.id === proof.income);
  expect(income.version).toBe('2');
  expect(income.entries).toHaveLength(1);
  expect(income.entries[0]).toMatchObject({ account_id: proof.account, amount_minor: '1000', currency: 'KWD' });
  expect(income.allocations).toHaveLength(1);
  expect(income.allocations[0].amount_minor).toBe('1000');
  expect(income.tag_ids).toHaveLength(1);
  await page.reload();
  await checkUI();
  expect(snapshot(proof.workspace, proof.account, proof.income_action)).toEqual(before);
  writeFileSync(process.env.ANDROID_OFFLINE_WEB_RESULT!, JSON.stringify({ workspace: proof.workspace, chrome_ui: true, reload: true, exact_minor: true, sql_unchanged: true, deleted_absent: true, processes: processes() }), { mode: 0o600 });
  await page.getByRole('button', { name: 'Выйти', exact: true }).click();
});
