import { test, expect } from '@playwright/test';
import { transactionNote, transactionReason, presentTransactions } from '../../src/shared/transactionText';
test('import provenance is hidden while original notes and our category assignments remain', () => {
  const note = 'Покупка продуктов\nWallet · строка 42\nКатегория Wallet: Старая категория\nОплата: Card\nМетки: Дом';
  const transaction = { id: 'same-id', version: '8', kind: 'expense', note, reason: null, entries: [{ account_id: 'same-account', amount_minor: '-1234' }], allocations: [{ category_id: 'our-category', amount_minor: '1234' }] };
  expect(transactionNote(note)).toBe('Покупка продуктов\nОплата: Card\nМетки: Дом');
  expect(presentTransactions({ items: [transaction], next_cursor: 'cursor' })).toEqual({ items: [{ ...transaction, note: 'Покупка продуктов\nОплата: Card\nМетки: Дом' }], next_cursor: 'cursor' });
  expect(transaction.note).toBe(note);
  expect(transactionNote('Моя заметка о Wallet\nКатегория Wallet: авторский текст')).toBe('Моя заметка о Wallet\nКатегория Wallet: авторский текст');
});
test('transfer source rows and correction origins are hidden without losing user descriptions', () => {
  expect(transactionNote('Списание\nWallet · строка 3\nКатегория Wallet: Перевод\nЗачисление Wallet · строка 4 · 2026-01-01T00:00:00Z\nЗачисление\nWallet · строка 4')).toBe('Списание\nЗачисление');
  expect(transactionReason('Начальный остаток Wallet')).toBe('Начальный остаток');
  expect(transactionReason('Корректировка Wallet: отсутствует источник поступления')).toBe('Корректировка остатка');
  expect(transactionReason('Корректировка Wallet: отсутствует направление перевода')).toBe('Корректировка остатка');
  expect(transactionReason('Ручная сверка')).toBe('Ручная сверка');
  expect(transactionReason(null)).toBeNull();
});
test('existing imported operations use our category in history and edit form without provenance', async ({ page }) => {
  const workspace = { id: '00000000-0000-4000-8000-000000000001', name: 'Финансы', sync_generation_id: 'generation' };
  const transaction = { id: '00000000-0000-4000-8000-000000000002', kind: 'expense', version: '1', status: 'posted', occurred_at: '2026-10-01T12:00:00Z', occurred_timezone: 'UTC', payee: 'Магазин', reason: null, note: 'Купил хлеб\nWallet · строка 42\nКатегория Wallet: Исходная категория', tag_ids: [], parent_transaction_id: null, fee_transaction_id: null, entries: [{ account_id: 'cash', amount_minor: '-1234', currency: 'EUR' }], allocations: [{ id: 'part', category_id: 'our-category', amount_minor: '1234', remaining_refundable_minor: '0' }] };
  const sent: Record<string, unknown>[] = [];
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body: unknown = { items: [], next_cursor: null };
    if (route.request().method() === 'PUT') { sent.push(route.request().postDataJSON()); return route.fulfill({ json: {} }); }
    if (path.endsWith('/auth/session')) body = { profile: { id: 'owner', email: 'test@example.test' }, session: { id: 'session' }, workspace, csrf_token: 'csrf', transport: 'cookie' };
    else if (path.endsWith('/workspaces')) body = { items: [workspace], next_cursor: null };
    else if (path.endsWith('/accounts')) body = { items: [{ id: 'cash', name: 'Карта', currency: 'EUR', type: 'bank', posted_balance_minor: '10000', version: '1', balance_version: '1', archived_at: null }], next_cursor: null };
    else if (path.endsWith('/categories')) body = { items: [{ id: 'our-category', name: 'Наша категория', parent_id: null, archived_at: null, version: '1' }], next_cursor: null };
    else if (path.endsWith('/transactions')) body = { items: [transaction], next_cursor: null };
    else if (path.endsWith('/' + transaction.id)) body = transaction;
    return route.fulfill({ json: body });
  });
  await page.goto('/#operations');
  await expect(page.getByText('Купил хлеб', { exact: true })).toBeVisible();
  await expect(page.locator('.history')).toContainText('Наша категория');
  await expect(page.getByLabel('Операции', { exact: true })).not.toContainText('Wallet');
  await expect(page.getByLabel('Операции', { exact: true })).not.toContainText('строка 42');
  await expect(page.getByLabel('Операции', { exact: true })).not.toContainText('Исходная категория');
  await page.getByRole('button', { name: 'Изменить операцию Магазин', exact: true }).click();
  await expect(page.getByRole('textbox', { name: 'Примечание', exact: true })).toHaveValue('Купил хлеб');
  await expect(page.getByRole('combobox', { name: 'Категория', exact: true })).toHaveValue('our-category');
  await page.getByRole('button', { name: 'Сохранить изменения операции' }).click();
  await expect.poll(() => sent.length).toBe(1);
  expect(sent[0]).toMatchObject({ note: 'Купил хлеб', amount_minor: '1234', allocations: [expect.objectContaining({ category_id: 'our-category' })] });
});
