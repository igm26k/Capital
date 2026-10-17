import { test, expect } from '@playwright/test';
import { parseWallet, planWallet, walletCSV, walletPairs } from '../../src/features/imports/wallet';
const header = 'account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels\n';
const date = '2025-08-12T15:00:00.000Z';
const row = (account: string, amount: string, kind = 'Расход', transfer = 'false', currency = 'EUR', note = '') => `${account};Еда;${currency};${amount};999999;${kind};Наличные;${note};${date};${transfer};;\n`;
test('Wallet positive expenses become expenses, amounts remain exact and metadata is retained', async () => {
  const file = await parseWallet(header + row('Cash', '12.34') + row('Cash', '12.34') + row('Cash', '0.01', 'Доход'));
  expect(file.errors).toEqual([]);
  expect(file.rows.map(r => r.amount)).toEqual(['1234', '1234', '1']);
  const plan = await planWallet(file, [], [], [], 'owner', 'workspace', 'generation', false);
  expect(plan.transactions).toBe(3);
  expect(plan.createdAccounts).toBe(1);
  expect(plan.createdCategories).toBe(1);
  const commands = plan.commands.filter(c => c.path === 'transactions').map(c => JSON.parse(c.body));
  expect(commands.map(c => c.kind)).toEqual(['expense', 'expense', 'income']);
  expect(commands[0].note).toContain('Категория Wallet: Еда');
  expect(commands[0].id).not.toEqual(commands[1].id);
  expect(commands[0].amount_minor).toBe('1234');
  expect(await planWallet(file, [], [], [], 'owner', 'workspace', 'generation', false)).toEqual(plan);
});
test('Wallet quoting, BOM, multiline notes and non-Latin text', async () => {
  expect(walletCSV('\ufeffa;b\r\n"one;two";"line\n""quote"""\r\n')).toEqual([['a', 'b'], ['one;two', 'line\n"quote"']]);
  const file = await parseWallet(header + row('Кошелек', '1.23', 'Расход', 'false', 'EUR', '"строка; 1\nстрока ""2"""'));
  expect(file.errors).toEqual([]); expect(file.rows[0].note).toBe('строка; 1\nстрока "2"');
  expect(() => walletCSV('a;b\n"broken')).toThrow();
});
test('Wallet only pairs unambiguous transfers, never turns transfers into expenses', async () => {
  const file = await parseWallet(header + row('Bank', '100', 'Расход', 'true', 'USD') + row('Cash', '91', 'Доход', 'true') + row('Other', '2', 'Доход', 'true'));
  expect(walletPairs(file.rows)).toEqual([]);
  await expect(planWallet(file, [], [], [], 'owner', 'workspace', 'generation', false)).rejects.toThrow('переводы');
  const plan = await planWallet(file, [{ debit: 2, credit: 3 }], [], [], 'owner', 'workspace', 'generation', true);
  expect(plan.skipped).toBe(1); expect(plan.transactions).toBe(1);
  expect(JSON.parse(plan.commands.at(-1)!.body)).toMatchObject({ kind: 'transfer', source_amount_minor: '10000', target_amount_minor: '9100', rate: null, fee: null });
  const paired = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash', '100', 'Доход', 'true'));
  expect(walletPairs(paired.rows)).toMatchObject([{ debit: 2, credit: 3 }]);
  const mismatch = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash', '99', 'Доход', 'true'));
  expect(walletPairs(mismatch.rows)).toEqual([]);
});
test('Wallet rejects invalid currencies, precision, signs, dates and unknown types', async () => {
  for (const input of [row('Cash', '-1'), row('Cash', '0'), row('Cash', '1.001'), row('Cash', '1e3'), row('Cash', '1', 'Refund'), row('Cash', '1', 'Расход', 'maybe'), row('Cash', '1', 'Расход', 'false', 'XYZ'), row('Cash', '1').replace(date, 'bad-date'), row('Cash', '1').replace(date, '2025-02-30T00:00:00.000Z')]) {
    const file = await parseWallet(header + input);
    expect(file.errors).toHaveLength(1);
    await expect(planWallet(file, [], [], [], 'owner', 'workspace', 'generation', true)).rejects.toThrow();
  }
});
test('Wallet files are not restricted to the generic CSV 1000-row limit', async () => {
  const file = await parseWallet(header + row('Cash', '1.23').repeat(3292));
  expect(file.rows).toHaveLength(3292); expect(file.errors).toEqual([]);
});
test('Wallet automatically reuses exact name and currency and creates all missing accounts first', async () => {
  const file = await parseWallet(header + row('Cash', '1') + row('Bank', '2', 'Доход', 'false', 'USD') + row('Cash', '3', 'Расход', 'false', 'USD'));
  const existing = { id: '00000000-0000-4000-8000-000000000099', name: 'Cash', currency: 'EUR', type: 'cash' as const, archived_at: null, posted_balance_minor: '0', version: '1', balance_version: '1' };
  const plan = await planWallet(file, [], [existing], [], 'owner', 'workspace', 'generation', false);
  expect(plan.createdAccounts).toBe(2);
  expect(plan.commands.slice(0, 2).map(c => JSON.parse(c.body))).toEqual(expect.arrayContaining([expect.objectContaining({ name: 'Bank', currency: 'USD' }), expect.objectContaining({ name: 'Cash', currency: 'USD' })]));
  const expenses = plan.commands.filter(c => c.path === 'transactions').map(c => JSON.parse(c.body));
  expect(expenses[0].account_id).toBe(existing.id);
  expect(expenses[2].account_id).not.toBe(existing.id);
  await expect(planWallet(file, [], [existing, { ...existing, id: 'duplicate' }], [], 'owner', 'workspace', 'generation', false)).rejects.toThrow('несколько активных счетов');
  await expect(planWallet(file, [], [{ ...existing, archived_at: '2026-01-01' }], [], 'owner', 'workspace', 'generation', false)).rejects.toThrow('архиве');
});
test('Wallet accepts only the fixed wallet_records.csv column format', async () => {
  await expect(parseWallet('date,amount,payee\n2025-01-01,-1,Store\n')).rejects.toThrow('wallet_records.csv');
  await expect(parseWallet(header.replaceAll(';', ',') + row('Cash', '1').replaceAll(';', ','))).rejects.toThrow('wallet_records.csv');
  await expect(parseWallet(header.replace('ref_currency_amount;', '') + row('Cash', '1'))).rejects.toThrow('wallet_records.csv');
});
test('Wallet separates several same-time transfers using exact currency amounts', async () => {
  const file = await parseWallet(header + row('Bank A', '100', 'Расход', 'true') + row('Bank B', '200', 'Расход', 'true') + row('Cash A', '100', 'Доход', 'true') + row('Cash B', '200', 'Доход', 'true'));
  expect(walletPairs(file.rows)).toEqual([{ debit: 2, credit: 4, match: 'amount' }, { debit: 3, credit: 5, match: 'amount' }]);
});
test('Wallet matches unique nearby amounts in both time directions but never outside five minutes', async () => {
  for (const offset of ['2025-08-12T14:55:00.000Z', '2025-08-12T15:05:00.000Z']) {
    const file = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash', '100', 'Доход', 'true').replace(date, offset));
    expect(walletPairs(file.rows)).toEqual([{ debit: 2, credit: 3, match: 'amount' }]);
    expect(walletPairs([...file.rows].reverse())).toEqual(walletPairs(file.rows));
  }
  const file = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash', '100', 'Доход', 'true').replace(date, '2025-08-12T15:05:00.001Z'));
  expect(walletPairs(file.rows)).toEqual([]);
});
test('Wallet requires mutual uniqueness and does not choose the nearest or first duplicate', async () => {
  const file = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash A', '100', 'Доход', 'true').replace(date, '2025-08-12T15:01:00.000Z') + row('Cash B', '100', 'Доход', 'true').replace(date, '2025-08-12T15:02:00.000Z'));
  expect(walletPairs(file.rows)).toEqual([]);
  for (const r of file.rows) r.kind = r.kind === 'expense' ? 'income' : 'expense';
  expect(walletPairs(file.rows)).toEqual([]);
  const duplicate = await parseWallet(header + row('Bank', '100', 'Расход', 'true').repeat(2) + row('Cash', '100', 'Доход', 'true').repeat(2));
  expect(walletPairs(duplicate.rows)).toEqual([]);
});
test('Wallet compares cross-currency references exactly without rounding transaction amounts', async () => {
  const debit = row('Bank', '100', 'Расход', 'true', 'USD').replace(';999999;', ';00091.230000;');
  const credit = row('Cash', '91.23', 'Доход', 'true').replace(';999999;', ';91.23;').replace(date, '2025-08-12T15:02:00.000Z');
  const file = await parseWallet(header + debit + credit);
  expect(walletPairs(file.rows)).toEqual([{ debit: 2, credit: 3, match: 'reference' }]);
  const plan = await planWallet(file, walletPairs(file.rows), [], [], 'owner', 'workspace', 'generation', false);
  expect(JSON.parse(plan.commands.at(-1)!.body)).toMatchObject({ source_amount_minor: '10000', target_amount_minor: '9123' });
  for (const ref of ['91.2300000000000001', '0', '-91.23', 'NaN', '']) {
    const other = row('Cash', '91.23', 'Доход', 'true').replace(';999999;', `;${ref};`).replace(date, '2025-08-12T15:02:00.000Z');
    const mismatch = await parseWallet(header + debit + other);
    expect(mismatch.errors).toEqual([]);
    expect(walletPairs(mismatch.rows)).toEqual([]);
  }
});
test('Wallet never matches different same-currency amounts or movements on one account', async () => {
  const file = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash', '99', 'Доход', 'true').replace(date, '2025-08-12T15:02:00.000Z'));
  expect(walletPairs(file.rows)).toEqual([]);
  const same = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Bank', '100', 'Доход', 'true').replace(date, '2025-08-12T15:02:00.000Z'));
  expect(walletPairs(same.rows)).toEqual([]);
});
