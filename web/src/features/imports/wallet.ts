import type { Account, Category } from '../../api/client';
import { minor } from '../../shared/money';

export type WalletRow = { number: number; account: string; category: string; currency: string; amount: string; kind: 'expense' | 'income'; date: string; transfer: boolean; payee: string; note: string; payment: string; labels: string };
export type WalletFile = { rows: WalletRow[]; errors: string[]; digest: string };
export type WalletPair = { debit: number; credit: number; match?: 'timestamp' };
export type WalletCommand = { path: 'accounts' | 'categories' | 'transactions'; key: string; body: string; label: string };
export type WalletPlan = { owner: string; workspace: string; generation: string; digest: string; commands: WalletCommand[]; skipped: number; rows: number; createdAccounts: number; createdCategories: number; transactions: number; adjustments?: number; legacyTransferIds?: string[]; key: string };
export const walletAccountKey = (r: Pick<WalletRow, 'account' | 'currency'>) => JSON.stringify([r.account, r.currency]);

// RFC 4180 quoting, including escaped quotes and newlines inside a field.
export function walletCSV(text: string): string[][] {
  text = text.replace(/^\uFEFF/, '');
  const delimiter = ';';
  const rows: string[][] = []; let row: string[] = [], value = '', quoted = false, closed = false;
  const cell = () => { row.push(value); value = ''; closed = false; };
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') { if (text[i + 1] === '"') { value += '"'; i++; } else { quoted = false; closed = true; } }
      else value += c;
    } else if (c === '"' && value === '' && !closed) quoted = true;
    else if (c === delimiter) cell();
    else if (c === '\n' || c === '\r') { if (c === '\r' && text[i + 1] === '\n') i++; cell(); if (row.some(v => v !== '')) rows.push(row); row = []; }
    else { if (closed || c === '"') throw new Error('Некорректные кавычки в CSV Wallet.'); value += c; }
  }
  if (quoted) throw new Error('Незакрытая кавычка в CSV Wallet.');
  if (value !== '' || row.length || closed) { cell(); if (row.some(v => v !== '')) rows.push(row); }
  return rows;
}
async function hash(value: string): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))), b => b.toString(16).padStart(2, '0')).join('');
}
async function id(namespace: string): Promise<string> {
  const s = await hash(namespace);
  return `${s.slice(0, 8)}-${s.slice(8, 12)}-5${s.slice(13, 16)}-a${s.slice(17, 20)}-${s.slice(20, 32)}`;
}
export async function parseWallet(text: string): Promise<WalletFile> {
  if (new TextEncoder().encode(text).length > 10 * 1024 * 1024) throw new Error('Файл Wallet должен быть не больше 10 MiB.');
  const [header, ...records] = walletCSV(text);
  const columns = ['account', 'category', 'currency', 'amount', 'ref_currency_amount', 'type', 'payment_type', 'note', 'date', 'transfer', 'payee', 'labels'];
  if (!header || header.length !== columns.length || columns.some((name, i) => header[i] !== name)) throw new Error('Выберите CSV в формате wallet_records.csv: 12 колонок Wallet с разделителем «;». Другие форматы импорта не поддерживаются.');
  if (!records.length || records.length > 20000) throw new Error('Выберите выгрузку Wallet от 1 до 20000 строк.');
  const rows: WalletRow[] = [], errors: string[] = [];
  records.forEach((fields, index) => {
    const number = index + 2;
    try {
      if (fields.length !== header.length) throw new Error('Число полей не совпадает с заголовком.');
      const r = Object.fromEntries(header.map((c, i) => [c, fields[i]]));
      const kind = ({ 'Расход': 'expense', 'Доход': 'income', 'Expense': 'expense', 'Income': 'income', 'expense': 'expense', 'income': 'income' } as Record<string, WalletRow['kind'] | undefined>)[r.type];
      if (!kind) throw new Error('Неизвестный тип операции.');
      if (!r.account.trim() || r.account.length > 100 || r.category.length > 100 || (r.payee ?? '').length > 200) throw new Error('Проверьте название счета, категории или получателя.');
      if (!/^\d+(?:[.,]\d+)?$/.test(r.amount)) throw new Error('Сумма Wallet должна быть положительным десятичным числом.');
      const amount = minor(r.amount, r.currency);
      if (BigInt(amount) <= 0n) throw new Error('Сумма должна быть больше нуля.');
      if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?(?:Z|[+-]\d\d:\d\d)$/.test(r.date) || !Number.isFinite(Date.parse(r.date))) throw new Error('Ожидается дата Wallet с часовым поясом ISO 8601.');
      const [year, month, day] = r.date.slice(0, 10).split('-').map(Number);
      const calendar = new Date(Date.UTC(year, month - 1, day));
      if (calendar.getUTCFullYear() !== year || calendar.getUTCMonth() !== month - 1 || calendar.getUTCDate() !== day) throw new Error('Несуществующая календарная дата.');
      const date = new Date(r.date).toISOString();
      if (date.slice(0, 10) < '1900-01-01' || Date.parse(date) > Date.now()) throw new Error('Дата вне допустимого диапазона.');
      if (!['true', 'false'].includes(r.transfer.toLowerCase())) throw new Error('Признак перевода должен быть true или false.');
      const row: WalletRow = { number, account: r.account, category: r.category, currency: r.currency, amount, kind, date, transfer: r.transfer.toLowerCase() === 'true', payee: r.payee ?? '', note: r.note ?? '', payment: r.payment_type ?? '', labels: r.labels ?? '' };
      if (walletNote(row).length > 1900) throw new Error('Слишком длинное примечание.');
      rows.push(row);
    } catch (e) { errors.push(`Строка ${number}: ${e instanceof Error ? e.message : 'Некорректные данные.'}`); }
  });
  return { rows, errors, digest: await hash(JSON.stringify([header, records])) };
}
export function walletNote(r: WalletRow): string {
  return [r.note, `Wallet · строка ${r.number}`, r.category && `Категория Wallet: ${r.category}`, r.payment && `Оплата: ${r.payment}`, r.labels && `Метки: ${r.labels}`].filter(Boolean).join('\n');
}
export function validWalletPair(debit: WalletRow, credit: WalletRow): boolean {
  return debit.transfer && credit.transfer && debit.kind === 'expense' && credit.kind === 'income' && walletAccountKey(debit) !== walletAccountKey(credit) && (debit.currency !== credit.currency || debit.amount === credit.amount);
}
// The Wallet format has no shared transfer id. By the owner's import policy,
// only a group of exactly two opposite movements at the exact same instant
// qualifies as a transfer. Amounts/reference values never split larger groups.
export function walletPairs(rows: WalletRow[]): WalletPair[] {
  const groups = new Map<string, WalletRow[]>();
  for (const row of rows.filter(r => r.transfer)) {
    if (!groups.has(row.date)) groups.set(row.date, []);
    groups.get(row.date)!.push(row);
  }
  const pairs: WalletPair[] = [];
  for (const group of groups.values()) if (group.length === 2) {
    const debit = group.find(r => r.kind === 'expense'), credit = group.find(r => r.kind === 'income');
    if (debit && credit && validWalletPair(debit, credit)) pairs.push({ debit: debit.number, credit: credit.number, match: 'timestamp' });
  }
  return pairs;
}
export function walletCorrections(rows: WalletRow[], pairs = walletPairs(rows)): WalletRow[] {
  const used = new Set(pairs.flatMap(p => [p.debit, p.credit]));
  return rows.filter(row => row.transfer && !used.has(row.number));
}
export async function planWallet(file: WalletFile, accounts: Account[], categories: Category[], owner: string, workspace: string, generation: string): Promise<WalletPlan> {
  if (file.errors.length) throw new Error('Исправьте ошибки CSV перед импортом.');
  const pairs = walletPairs(file.rows);
  const commands: WalletCommand[] = [], mapped = new Map<string, string>(), categoryIDs = new Map<string, string>();
  const root = `wallet:v1:${owner}:${workspace}`;
  const append = (path: WalletCommand['path'], body: { id: string } & Record<string, unknown>, label: string) => commands.push({ path, key: body.id, body: JSON.stringify(body), label });
  const groups = new Map<string, WalletRow[]>();
  for (const row of file.rows) { const key = walletAccountKey(row); if (!groups.has(key)) groups.set(key, []); groups.get(key)!.push(row); }
  for (const [key, rows] of groups) {
    const first = rows[0];
    const matches = accounts.filter(a => a.name === first.account && a.currency === first.currency);
    const active = matches.filter(a => !a.archived_at);
    if (active.length > 1) throw new Error(`Найдено несколько активных счетов «${first.account}» в ${first.currency}. Устраните одинаковые названия перед импортом.`);
    if (!active.length && matches.length) throw new Error(`Счет «${first.account}» в ${first.currency} находится в архиве. Восстановите его перед импортом.`);
    let accountID = active[0]?.id;
    if (!accountID) {
      accountID = await id(`${root}:account:${key}`);
      const opened = file.rows.reduce((a, r) => r.date < a ? r.date : a, first.date);
      append('accounts', { id: accountID, name: first.account, currency: first.currency, type: /cash|наличн/i.test(first.account) ? 'cash' : /card|карт/i.test(first.account) ? 'card' : 'bank', opened_at: opened, occurred_timezone: 'UTC', opening_balance_minor: '0' }, `Счет: ${first.account}`);
    }
    if ([...mapped.values()].includes(accountID)) throw new Error('Разные счета Wallet нельзя объединять в один счет при импорте.');
    mapped.set(key, accountID);
  }
  for (const name of [...new Set(file.rows.filter(r => !r.transfer && r.category).map(r => r.category))].sort()) {
    const existing = categories.filter(c => !c.archived_at && c.name === name);
    if (existing.length > 1) throw new Error(`Есть несколько категорий «${name}». Устраните неоднозначность названий перед импортом.`);
    const categoryID = existing[0]?.id ?? await id(`${root}:category:${name}`);
    categoryIDs.set(name, categoryID);
    if (!existing.length) append('categories', { id: categoryID, name, parent_id: null }, `Категория: ${name}`);
  }
  const byNumber = new Map(file.rows.map(r => [r.number, r])), used = new Set<number>();
  let transactions = 0;
  for (const pair of pairs) {
    const debit = byNumber.get(pair.debit), credit = byNumber.get(pair.credit);
    if (!debit || !credit || !validWalletPair(debit, credit) || used.has(pair.debit) || used.has(pair.credit)) throw new Error('Некорректная или повторяющаяся пара перевода.');
    const source = mapped.get(walletAccountKey(debit))!, target = mapped.get(walletAccountKey(credit))!;
    if (source === target) throw new Error('Для перевода нужны два разных счета.');
    const note = `${walletNote(debit)}\nЗачисление Wallet · строка ${credit.number} · ${credit.date}\n${walletNote(credit)}`;
    if (note.length > 2000) throw new Error(`Слишком длинное примечание перевода ${debit.number}/${credit.number}.`);
    const transactionID = await id(`${root}:${file.digest}:transfer:${debit.number}`);
    append('transactions', { id: transactionID, kind: 'transfer', source_account_id: source, target_account_id: target, source_amount_minor: debit.amount, target_amount_minor: credit.amount, occurred_at: debit.date, occurred_timezone: 'UTC', note, payee: debit.payee || credit.payee, tag_ids: [], rate: null, fee: null }, `Перевод: строки ${debit.number} → ${credit.number}`);
    used.add(debit.number); used.add(credit.number); transactions++;
  }
  for (const row of file.rows.filter(r => !r.transfer)) {
    const transactionID = await id(`${root}:${file.digest}:row:${row.number}`);
    append('transactions', { id: transactionID, kind: row.kind, account_id: mapped.get(walletAccountKey(row))!, amount_minor: row.amount, occurred_at: row.date, occurred_timezone: 'UTC', note: walletNote(row), payee: row.payee, tag_ids: [], allocations: [{ id: await id(`${transactionID}:allocation`), category_id: categoryIDs.get(row.category) ?? null, amount_minor: row.amount }] }, `Строка ${row.number}: ${row.kind === 'expense' ? 'расход' : 'доход'}`);
    transactions++;
  }
  const corrections = walletCorrections(file.rows, pairs);
  const legacyTransferIds: string[] = [];
  for (const row of corrections) {
    legacyTransferIds.push(await id(`${root}:${file.digest}:transfer:${row.number}`));
    const transactionID = await id(`${root}:${file.digest}:adjustment:${row.number}`);
    append('transactions', { id: transactionID, kind: 'adjustment', account_id: mapped.get(walletAccountKey(row))!, amount_minor: row.kind === 'expense' ? `-${row.amount}` : row.amount, occurred_at: row.date, occurred_timezone: 'UTC', reason: row.kind === 'expense' ? 'Корректировка Wallet: отсутствует направление перевода' : 'Корректировка Wallet: отсутствует источник поступления', note: walletNote(row), payee: row.payee }, `Корректировка: строка ${row.number}`);
    used.add(row.number); transactions++;
  }
  if (!transactions) throw new Error('В плане нет операций для импорта.');
  return { owner, workspace, generation, digest: file.digest, commands, skipped: 0, rows: file.rows.length, createdAccounts: commands.filter(c => c.path === 'accounts').length, createdCategories: commands.filter(c => c.path === 'categories').length, transactions, adjustments: corrections.length, legacyTransferIds, key: file.digest };
}
