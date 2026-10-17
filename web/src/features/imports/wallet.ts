import type { Account, Category } from '../../api/client';
import { minor } from '../../shared/money';

export type WalletRow = { number: number; account: string; category: string; currency: string; amount: string; kind: 'expense' | 'income'; date: string; transfer: boolean; payee: string; note: string; payment: string; labels: string };
export type WalletFile = { rows: WalletRow[]; errors: string[]; digest: string };
export type WalletPair = { debit: number; credit: number };
export type WalletCommand = { path: 'accounts' | 'categories' | 'transactions'; key: string; body: string; label: string };
export type WalletPlan = { owner: string; workspace: string; generation: string; digest: string; commands: WalletCommand[]; skipped: number; rows: number; createdAccounts: number; createdCategories: number; transactions: number; key: string };
export const walletAccountKey = (r: Pick<WalletRow, 'account' | 'currency'>) => JSON.stringify([r.account, r.currency]);

// RFC 4180 quoting, including escaped quotes and newlines inside a field.
export function walletCSV(text: string): string[][] {
  text = text.replace(/^\uFEFF/, '');
  const first = text.slice(0, text.indexOf('\n') < 0 ? text.length : text.indexOf('\n'));
  const delimiter = first.includes(';') ? ';' : first.includes('\t') ? '\t' : ',';
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
  const required = ['account', 'category', 'currency', 'amount', 'type', 'date', 'transfer'];
  if (!header || new Set(header).size !== header.length || required.some(c => !header.includes(c))) throw new Error('Это не CSV выгрузка Wallet: нужны account, category, currency, amount, type, date, transfer.');
  if (!records.length || records.length > 20000) throw new Error('Выберите выгрузку Wallet от 1 до 20000 строк.');
  const rows: WalletRow[] = [], errors: string[] = [];
  records.forEach((fields, index) => {
    const number = index + 2;
    try {
      if (fields.length !== header.length) throw new Error('Число полей не совпадает с заголовком.');
      const r = Object.fromEntries(header.map((c, i) => [c, fields[i]]));
      const kind = ({ 'Расход': 'expense', 'Доход': 'income', 'Expense': 'expense', 'Income': 'income', 'expense': 'expense', 'income': 'income' } as const)[r.type as 'Расход'];
      if (!kind) throw new Error('Неизвестный тип операции.');
      if (!r.account.trim() || r.account.length > 100 || r.category.length > 100 || (r.payee ?? '').length > 200) throw new Error('Проверьте название счета, категории или получателя.');
      if (!/^\d+(?:[.,]\d+)?$/.test(r.amount)) throw new Error('Сумма Wallet должна быть положительным десятичным числом.');
      const amount = minor(r.amount, r.currency);
      if (BigInt(amount) <= 0n) throw new Error('Сумма должна быть больше нуля.');
      if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?(?:Z|[+-]\d\d:\d\d)$/.test(r.date) || !Number.isFinite(Date.parse(r.date))) throw new Error('Ожидается дата Wallet с часовым поясом ISO 8601.');
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
export function walletPairs(rows: WalletRow[]): WalletPair[] {
  const groups = new Map<string, WalletRow[]>();
  for (const r of rows.filter(r => r.transfer)) groups.set(r.date, [...(groups.get(r.date) ?? []), r]);
  const pairs: WalletPair[] = [];
  for (const group of groups.values()) if (group.length === 2) {
    const debit = group.find(r => r.kind === 'expense'), credit = group.find(r => r.kind === 'income');
    if (debit && credit && validWalletPair(debit, credit)) pairs.push({ debit: debit.number, credit: credit.number });
  }
  return pairs;
}
export async function planWallet(file: WalletFile, pairs: WalletPair[], mapping: Record<string, string>, accounts: Account[], categories: Category[], owner: string, workspace: string, generation: string, allowSkipped: boolean): Promise<WalletPlan> {
  if (file.errors.length) throw new Error('Исправьте ошибки CSV перед импортом.');
  const commands: WalletCommand[] = [], mapped = new Map<string, string>(), categoryIDs = new Map<string, string>();
  const root = `wallet:v1:${owner}:${workspace}`;
  const append = (path: WalletCommand['path'], body: { id: string } & Record<string, unknown>, label: string) => commands.push({ path, key: body.id, body: JSON.stringify(body), label });
  const groups = new Map<string, WalletRow[]>();
  for (const row of file.rows) { const key = walletAccountKey(row); groups.set(key, [...(groups.get(key) ?? []), row]); }
  for (const [key, rows] of groups) {
    const first = rows[0], selection = mapping[key];
    if (!selection) throw new Error(`Выберите счет для ${first.account}.`);
    let accountID = selection;
    if (selection === 'new') {
      accountID = await id(`${root}:account:${key}`);
      const opened = rows.reduce((a, r) => r.date < a ? r.date : a, first.date);
      append('accounts', { id: accountID, name: first.account, currency: first.currency, type: /cash|наличн/i.test(first.account) ? 'cash' : /card|карт/i.test(first.account) ? 'card' : 'bank', opened_at: opened, occurred_timezone: 'UTC', opening_balance_minor: '0' }, `Счет: ${first.account}`);
    } else {
      const account = accounts.find(a => a.id === selection && !a.archived_at && a.currency === first.currency);
      if (!account) throw new Error(`Счет для ${first.account} недоступен или имеет другую валюту.`);
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
    const note = `${walletNote(debit)}\nЗачисление Wallet · строка ${credit.number} · ${credit.date}${credit.note ? `\n${credit.note}` : ''}${credit.labels ? `\nМетки зачисления: ${credit.labels}` : ''}`;
    if (note.length > 2000) throw new Error(`Слишком длинное примечание перевода ${debit.number}/${credit.number}.`);
    const transactionID = await id(`${root}:${file.digest}:transfer:${debit.number}:${credit.number}`);
    append('transactions', { id: transactionID, kind: 'transfer', source_account_id: source, target_account_id: target, source_amount_minor: debit.amount, target_amount_minor: credit.amount, occurred_at: debit.date, occurred_timezone: 'UTC', note, payee: debit.payee || credit.payee, tag_ids: [], rate: null, fee: null }, `Перевод: строки ${debit.number} → ${credit.number}`);
    used.add(debit.number); used.add(credit.number); transactions++;
  }
  for (const row of file.rows.filter(r => !r.transfer)) {
    const transactionID = await id(`${root}:${file.digest}:row:${row.number}`);
    append('transactions', { id: transactionID, kind: row.kind, account_id: mapped.get(walletAccountKey(row))!, amount_minor: row.amount, occurred_at: row.date, occurred_timezone: 'UTC', note: walletNote(row), payee: row.payee, tag_ids: [], allocations: [{ id: await id(`${transactionID}:allocation`), category_id: categoryIDs.get(row.category) ?? null, amount_minor: row.amount }] }, `Строка ${row.number}: ${row.kind === 'expense' ? 'расход' : 'доход'}`);
    transactions++;
  }
  const skipped = file.rows.filter(r => r.transfer && !used.has(r.number)).length;
  if (skipped && !allowSkipped) throw new Error('Сопоставьте оставшиеся переводы или явно подтвердите их исключение.');
  return { owner, workspace, generation, digest: file.digest, commands, skipped, rows: file.rows.length, createdAccounts: commands.filter(c => c.path === 'accounts').length, createdCategories: commands.filter(c => c.path === 'categories').length, transactions, key: file.digest };
}
