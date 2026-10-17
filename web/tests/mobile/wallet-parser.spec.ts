import { test, expect } from '@playwright/test';
import { parseWallet, planWallet, walletAccountKey, walletCSV, walletPairs } from '../../src/features/imports/wallet';
const header = 'account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels\n';
const date = '2025-08-12T15:00:00.000Z';
const row = (account: string, amount: string, kind = 'Расход', transfer = 'false', currency = 'EUR', note = '') => `${account};Еда;${currency};${amount};999999;${kind};Наличные;${note};${date};${transfer};;\n`;
test('Wallet positive expenses become expenses, amounts remain exact and metadata is retained', async () => {
  const file = await parseWallet(header + row('Cash', '12.34') + row('Cash', '12.34') + row('Cash', '0.01', 'Доход'));
  expect(file.errors).toEqual([]);
  expect(file.rows.map(r => r.amount)).toEqual(['1234', '1234', '1']);
  const plan = await planWallet(file, [], { [walletAccountKey(file.rows[0])]: 'new' }, [], [], 'owner', 'workspace', 'generation', false);
  expect(plan.transactions).toBe(3);
  expect(plan.createdAccounts).toBe(1);
  expect(plan.createdCategories).toBe(1);
  const commands = plan.commands.filter(c => c.path === 'transactions').map(c => JSON.parse(c.body));
  expect(commands.map(c => c.kind)).toEqual(['expense', 'expense', 'income']);
  expect(commands[0].note).toContain('Категория Wallet: Еда');
  expect(commands[0].id).not.toEqual(commands[1].id);
  expect(commands[0].amount_minor).toBe('1234');
  expect(await planWallet(file, [], { [walletAccountKey(file.rows[0])]: 'new' }, [], [], 'owner', 'workspace', 'generation', false)).toEqual(plan);
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
  const mapping = Object.fromEntries(file.rows.map(r => [walletAccountKey(r), 'new']));
  await expect(planWallet(file, [], mapping, [], [], 'owner', 'workspace', 'generation', false)).rejects.toThrow('переводы');
  const plan = await planWallet(file, [{ debit: 2, credit: 3 }], mapping, [], [], 'owner', 'workspace', 'generation', true);
  expect(plan.skipped).toBe(1); expect(plan.transactions).toBe(1);
  expect(JSON.parse(plan.commands.at(-1)!.body)).toMatchObject({ kind: 'transfer', source_amount_minor: '10000', target_amount_minor: '9100', rate: null, fee: null });
  const paired = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash', '100', 'Доход', 'true'));
  expect(walletPairs(paired.rows)).toEqual([{ debit: 2, credit: 3 }]);
  const mismatch = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash', '99', 'Доход', 'true'));
  expect(walletPairs(mismatch.rows)).toEqual([]);
});
test('Wallet rejects invalid currencies, precision, signs, dates and unknown types', async () => {
  for (const input of [row('Cash', '-1'), row('Cash', '0'), row('Cash', '1.001'), row('Cash', '1e3'), row('Cash', '1', 'Refund'), row('Cash', '1', 'Расход', 'maybe'), row('Cash', '1', 'Расход', 'false', 'XYZ'), row('Cash', '1').replace(date, 'bad-date')]) {
    const file = await parseWallet(header + input);
    expect(file.errors).toHaveLength(1);
    await expect(planWallet(file, [], {}, [], [], 'owner', 'workspace', 'generation', true)).rejects.toThrow();
  }
});
test('Wallet files are not restricted to the generic CSV 1000-row limit', async () => {
  const file = await parseWallet(header + row('Cash', '1.23').repeat(3292));
  expect(file.rows).toHaveLength(3292); expect(file.errors).toEqual([]);
});
