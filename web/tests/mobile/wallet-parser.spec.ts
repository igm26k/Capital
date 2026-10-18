import { test, expect } from '@playwright/test';
import { parseWallet, planWallet, walletCSV, walletPairs, walletCorrections } from '../../src/features/imports/wallet';
const header = 'account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels\n';
const date = '2025-08-12T15:00:00.000Z';
const row = (account: string, amount: string, kind = 'Расход', transfer = 'false', currency = 'EUR', note = '') => `${account};Еда;${currency};${amount};999999;${kind};Наличные;${note};${date};${transfer};;\n`;
test('Wallet positive expenses become expenses, amounts remain exact and metadata is retained', async () => {
  const file = await parseWallet(header + row('Cash', '12.34') + row('Cash', '12.34') + row('Cash', '0.01', 'Доход'));
  expect(file.errors).toEqual([]);
  expect(file.rows.map(r => r.amount)).toEqual(['1234', '1234', '1']);
  const plan = await planWallet(file, [], [], 'owner', 'workspace', 'generation');
  expect(plan.transactions).toBe(3);
  expect(plan.createdAccounts).toBe(1);
  expect(plan.createdCategories).toBe(1);
  const commands = plan.commands.filter(c => c.path === 'transactions').map(c => JSON.parse(c.body));
  expect(commands.map(c => c.kind)).toEqual(['expense', 'expense', 'income']);
  expect(commands[0].note).toContain('Категория Wallet: Еда');
  expect(commands[0].id).not.toEqual(commands[1].id);
  expect(commands[0].amount_minor).toBe('1234');
  expect(await planWallet(file, [], [], 'owner', 'workspace', 'generation')).toEqual(plan);
});
test('Wallet quoting, BOM, multiline notes and non-Latin text', async () => {
  expect(walletCSV('\ufeffa;b\r\n"one;two";"line\n""quote"""\r\n')).toEqual([['a', 'b'], ['one;two', 'line\n"quote"']]);
  const file = await parseWallet(header + row('Кошелек', '1.23', 'Расход', 'false', 'EUR', '"строка; 1\nстрока ""2"""'));
  expect(file.errors).toEqual([]); expect(file.rows[0].note).toBe('строка; 1\nстрока "2"');
  expect(() => walletCSV('a;b\n"broken')).toThrow();
});
test('Wallet keeps only a single explicit pair and treats larger groups as adjustments', async () => {
  const file = await parseWallet(header + row('Bank', '100', 'Расход', 'true', 'USD') + row('Cash', '91', 'Доход', 'true') + row('Other', '2', 'Доход', 'true'));
  expect(walletPairs(file.rows)).toEqual([]);
  const plan = await planWallet(file, [], [], 'owner', 'workspace', 'generation');
  expect(plan.skipped).toBe(0); expect(plan.transactions).toBe(3); expect(plan.adjustments).toBe(3);
  const paired = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash', '100', 'Доход', 'true'));
  expect(walletPairs(paired.rows)).toMatchObject([{ debit: 2, credit: 3 }]);
  const pairPlan = await planWallet(paired, [], [], 'owner', 'workspace', 'generation');
  expect(pairPlan.transactions).toBe(1); expect(pairPlan.adjustments).toBe(0);
  const mismatch = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash', '99', 'Доход', 'true'));
  expect(walletPairs(mismatch.rows)).toEqual([]);
});
test('Wallet rejects invalid currencies, precision, signs, dates and unknown types', async () => {
  for (const input of [row('Cash', '-1'), row('Cash', '0'), row('Cash', '1.001'), row('Cash', '1e3'), row('Cash', '1', 'Refund'), row('Cash', '1', 'Расход', 'maybe'), row('Cash', '1', 'Расход', 'false', 'XYZ'), row('Cash', '1').replace(date, 'bad-date'), row('Cash', '1').replace(date, '2025-02-30T00:00:00.000Z')]) {
    const file = await parseWallet(header + input);
    expect(file.errors).toHaveLength(1);
    await expect(planWallet(file, [], [], 'owner', 'workspace', 'generation')).rejects.toThrow();
  }
});
test('Wallet files are not restricted to the generic CSV 1000-row limit', async () => {
  const file = await parseWallet(header + row('Cash', '1.23').repeat(3292));
  expect(file.rows).toHaveLength(3292); expect(file.errors).toEqual([]);
});
test('Wallet automatically reuses exact name and currency and creates all missing accounts first', async () => {
  const file = await parseWallet(header + row('Cash', '1') + row('Bank', '2', 'Доход', 'false', 'USD') + row('Cash', '3', 'Расход', 'false', 'USD'));
  const existing = { id: '00000000-0000-4000-8000-000000000099', name: 'Cash', currency: 'EUR', type: 'cash' as const, archived_at: null, posted_balance_minor: '0', version: '1', balance_version: '1' };
  const plan = await planWallet(file, [existing], [], 'owner', 'workspace', 'generation');
  expect(plan.createdAccounts).toBe(2);
  expect(plan.commands.slice(0, 2).map(c => JSON.parse(c.body))).toEqual(expect.arrayContaining([expect.objectContaining({ name: 'Bank', currency: 'USD' }), expect.objectContaining({ name: 'Cash', currency: 'USD' })]));
  const expenses = plan.commands.filter(c => c.path === 'transactions').map(c => JSON.parse(c.body));
  expect(expenses[0].account_id).toBe(existing.id);
  expect(expenses[2].account_id).not.toBe(existing.id);
  await expect(planWallet(file, [existing, { ...existing, id: 'duplicate' }], [], 'owner', 'workspace', 'generation')).rejects.toThrow('несколько активных счетов');
  await expect(planWallet(file, [{ ...existing, archived_at: '2026-01-01' }], [], 'owner', 'workspace', 'generation')).rejects.toThrow('архиве');
});
test('Wallet accepts only the fixed wallet_records.csv column format', async () => {
  await expect(parseWallet('date,amount,payee\n2025-01-01,-1,Store\n')).rejects.toThrow('wallet_records.csv');
  await expect(parseWallet(header.replaceAll(';', ',') + row('Cash', '1').replaceAll(';', ','))).rejects.toThrow('wallet_records.csv');
  await expect(parseWallet(header.replace('ref_currency_amount;', '') + row('Cash', '1'))).rejects.toThrow('wallet_records.csv');
});
test('Wallet splits same-time groups only into mutually unique pairs', async () => {
  const file = await parseWallet(header + row('Bank A', '100', 'Расход', 'true') + row('Bank B', '200', 'Расход', 'true') + row('Cash A', '100', 'Доход', 'true') + row('Cash B', '200', 'Доход', 'true'));
  expect(walletPairs(file.rows)).toEqual([{ debit: 2, credit: 4, match: 'amount' }, { debit: 3, credit: 5, match: 'amount' }]);
  const plan = await planWallet(file, [], [], 'owner', 'workspace', 'generation');
  expect(plan.adjustments).toBe(0);
  expect(plan.transactions).toBe(2);
  expect(plan.legacyAdjustmentIds).toHaveLength(4);
});
test('Wallet does not pair close timestamps even with equal amounts and reference values', async () => {
  for (const offset of ['2025-08-12T14:59:59.999Z', '2025-08-12T15:00:00.001Z', '2025-08-12T15:05:00.000Z']) {
    const file = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash', '100', 'Доход', 'true').replace(date, offset));
    expect(walletPairs(file.rows)).toEqual([]);
    expect(walletCorrections(file.rows)).toHaveLength(2);
    expect(walletPairs([...file.rows].reverse())).toEqual([]);
  }
});
test('Wallet never uses reference amounts to link different timestamps or set ledger amounts', async () => {
  for (const ref of ['91.23', '91.2300000000000001', '0', '-91.23', 'NaN', '']) {
    const debit = row('Bank', '100', 'Расход', 'true', 'USD').replace(';999999;', ';91.23;');
    const credit = row('Cash', '91.23', 'Доход', 'true').replace(';999999;', `;${ref};`).replace(date, '2025-08-12T15:02:00.000Z');
    const file = await parseWallet(header + debit + credit);
    const plan = await planWallet(file, [], [], 'owner', 'workspace', 'generation');
    expect(walletPairs(file.rows)).toEqual([]); expect(plan.adjustments).toBe(2);
    expect(plan.commands.filter(c => c.path === 'transactions').map(c => JSON.parse(c.body).amount_minor)).toEqual(['-10000', '9123']);
  }
});
test('Wallet never matches different same-currency amounts or movements on one account', async () => {
  const file = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Cash', '99', 'Доход', 'true').replace(date, '2025-08-12T15:02:00.000Z'));
  expect(walletPairs(file.rows)).toEqual([]);
  const same = await parseWallet(header + row('Bank', '100', 'Расход', 'true') + row('Bank', '100', 'Доход', 'true').replace(date, '2025-08-12T15:02:00.000Z'));
  expect(walletPairs(same.rows)).toEqual([]);
});
test('Wallet orphan debits and credits become dated signed adjustments', async () => {
  const file = await parseWallet(header + row('Bank', '100', 'Расход', 'true', 'USD') + row('Cash', '20', 'Доход', 'true').replace(date, '2025-08-13T15:00:00.000Z'));
  expect(walletPairs(file.rows)).toEqual([]);
  expect(walletCorrections(file.rows, [])).toHaveLength(2);
  const plan = await planWallet(file, [], [], 'owner', 'workspace', 'generation');
  expect(plan.adjustments).toBe(2); expect(plan.skipped).toBe(0); expect(plan.transactions).toBe(2);
  const movements = plan.commands.filter(c => c.path === 'transactions').map(c => JSON.parse(c.body));
  expect(movements).toEqual([expect.objectContaining({ kind: 'adjustment', amount_minor: '-10000', occurred_at: date }), expect.objectContaining({ kind: 'adjustment', amount_minor: '2000', occurred_at: '2025-08-13T15:00:00.000Z' })]);
  expect(movements.every(m => !('target_account_id' in m) && !('allocations' in m) && m.reason)).toBe(true);
  expect(await planWallet(file, [], [], 'owner', 'workspace', 'generation')).toEqual(plan);
});
test('Wallet treats every nonexplicit movement as a correction with no skipped rows', async () => {
  const file = await parseWallet(header + row('Bank A', '100', 'Расход', 'true') + row('Bank B', '100', 'Расход', 'true') + row('Cash', '100', 'Доход', 'true'));
  expect(walletPairs(file.rows)).toEqual([]);
  expect(walletCorrections(file.rows)).toHaveLength(3);
  const plan = await planWallet(file, [], [], 'owner', 'workspace', 'generation');
  expect(plan.adjustments).toBe(3); expect(plan.skipped).toBe(0);
});
test('Wallet recognizes concurrent RUB transfer and EUR to RUB conversion at rate 88', async () => {
  const content = header + row('Settlement RUB', '60358.28', 'Расход', 'true', 'RUB').replace(';999999;', ';60358.280;') + row('Settlement RUB', '52360', 'Доход', 'true', 'RUB').replace(';999999;', ';55607.476635514024;') + row('Savings RUB', '60358.28', 'Доход', 'true', 'RUB').replace(';999999;', ';60358.28;') + row('FX EUR', '595', 'Расход', 'true', 'EUR').replace(';999999;', ';055607.47663551402400;');
  const file = await parseWallet(content);
  expect(walletPairs(file.rows)).toEqual([{ debit: 2, credit: 4, match: 'amount' }, { debit: 5, credit: 3, match: 'reference' }]);
  expect(walletCorrections(file.rows)).toEqual([]);
  const plan = await planWallet(file, [], [], 'owner', 'workspace', 'generation');
  const movements = plan.commands.filter(c => c.path === 'transactions').map(c => JSON.parse(c.body));
  expect(movements).toEqual([expect.objectContaining({ kind: 'transfer', source_amount_minor: '6035828', target_amount_minor: '6035828' }), expect.objectContaining({ kind: 'transfer', source_amount_minor: '59500', target_amount_minor: '5236000', rate: null })]);
  expect(plan.legacyAdjustmentIds).toHaveLength(4);
});
test('Wallet never rounds reference values or picks between duplicate cross-currency candidates', async () => {
  for (const reference of ['100.0000000000000001', '100', '0', 'NaN', '']) {
    const file = await parseWallet(header + row('FX USD', '1', 'Расход', 'true', 'USD').replace(';999999;', ';100;') + row('Cash EUR', '2', 'Доход', 'true').replace(';999999;', `;${reference};`) + row('Other EUR', '3', 'Доход', 'true').replace(';999999;', `;${reference};`));
    expect(walletPairs(file.rows)).toEqual([]);
    expect(walletCorrections(file.rows)).toHaveLength(3);
  }
});

test('Wallet reuses its deterministic account after a rename without creating another account', async () => {
  const file = await parseWallet(header + row('Original name', '12.34'));
  const first = await planWallet(file, [], [], 'owner', 'workspace', 'generation');
  const account = JSON.parse(first.commands.find(c => c.path === 'accounts')!.body);
  const renamed = { ...account, name: 'Corrected name', archived_at: null, version: '2', balance_version: '2', posted_balance_minor: '0' };
  const next = await planWallet(file, [renamed], [], 'owner', 'workspace', 'generation');
  expect(next.createdAccounts).toBe(0);
  const operation = JSON.parse(next.commands.find(c => c.path === 'transactions')!.body);
  expect(operation.account_id).toBe(account.id);
  expect(next.commands.filter(c => c.path === 'transactions')).toEqual(first.commands.filter(c => c.path === 'transactions'));
});
