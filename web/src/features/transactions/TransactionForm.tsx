import { useState, type FormEvent } from 'react';
import type { Account, Category, Tag, Transaction } from '../../api/client';
import { decimal, minor, money } from '../../shared/money';
import { categoryLabel } from '../../shared/categories';
import { timezone } from '../auth/AuthForm';
type Part = { id: string; category: string; amount: string };
export function TransactionForm({ accounts, categories, tags, disabled, submit, fail, initial, cancel, parentVersion }: { accounts: Account[]; categories: Category[]; tags: Tag[]; disabled: boolean; submit: (body: object) => void; fail: (message: string) => void; initial?: Transaction; cancel?: () => void; parentVersion?: string | null }) {
  const [kind, setKind] = useState(initial?.kind ?? 'expense'), [accountID, setAccountID] = useState(initial?.entries[0]?.account_id ?? accounts[0]?.id ?? ''), [amount, setAmount] = useState(initial ? decimal(initial.allocations.reduce((sum, part) => sum + BigInt(part.amount_minor), 0n).toString(), initial.entries[0].currency) : '');
  const [parts, setParts] = useState<Part[]>(initial ? initial.allocations.map(part => ({ id: part.id, category: part.category_id ?? '', amount: decimal(part.amount_minor, initial.entries[0].currency) })) : [{ id: crypto.randomUUID(), category: '', amount: '' }]);
  const account = accounts.find(item => item.id === (accountID || accounts[0]?.id));
  const eligible = accounts.filter(a => (!a.archived_at || a.id === initial?.entries[0]?.account_id) && (!initial || a.currency === initial.entries[0].currency));
  function update(id: string, patch: Partial<Part>) { setParts(items => items.map(item => item.id === id ? { ...item, ...patch } : item)); }
  let remainder = '';
  try { if (account && amount && parts.length > 1) remainder = money((BigInt(minor(amount, account.currency)) - parts.reduce((sum, part) => sum + BigInt(part.amount ? minor(part.amount, account.currency) : '0'), 0n)).toString(), account.currency); } catch { /* The submit handler explains invalid amounts. */ }
  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    if (!account) return;
    try {
      const total = minor(amount, account.currency);
      if (BigInt(total) <= 0n) throw new Error('Сумма должна быть больше нуля.');
      const allocations = parts.map(part => ({ id: part.id, category_id: part.category || null, amount_minor: parts.length === 1 ? total : minor(part.amount, account.currency) }));
      if (allocations.some(part => BigInt(part.amount_minor) <= 0n)) throw new Error('Сумма каждой части должна быть больше нуля.');
      if (allocations.reduce((sum, part) => sum + BigInt(part.amount_minor), 0n) !== BigInt(total)) throw new Error('Сумма частей должна точно совпадать с суммой операции.');
      submit({ ...(initial ? { expected_version: initial.version, ...(kind === 'expense' ? { expected_parent_version: parentVersion ?? null } : {}) } : { id: crypto.randomUUID() }), kind, account_id: account.id, amount_minor: total, occurred_at: initial?.occurred_at ?? new Date().toISOString(), occurred_timezone: initial?.occurred_timezone ?? timezone(), note: data.get('note'), payee: data.get('payee'), tag_ids: data.getAll('tag'), allocations });
    } catch (error) { fail((error as Error).message); }
  }
  return <section><h2>{initial ? 'Изменить операцию' : 'Новая операция'}</h2><form onSubmit={create}><fieldset disabled={disabled || !accounts.length}><label>Тип операции<select disabled={!!initial} aria-label="Тип операции" value={kind} onChange={e => setKind(e.target.value)}><option value="expense">Расход</option><option value="income">Доход</option></select></label><label>Счет<select aria-label="Счет" name="account" value={account?.id ?? ''} onChange={event => setAccountID(event.target.value)}>{eligible.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label><label>Сумма<input name="amount" value={amount} onChange={event => setAmount(event.target.value)} inputMode="decimal" required placeholder="12,34" /></label>
    {parts.map((part, index) => <div className="allocation" key={part.id}><label>{parts.length === 1 ? 'Категория' : `Категория части ${index + 1}`}<select aria-label={parts.length === 1 ? 'Категория' : `Категория части ${index + 1}`} value={part.category} onChange={event => update(part.id, { category: event.target.value })}><option value="">Без категории</option>{categories.filter(item => !item.archived_at || initial?.allocations.find(original => original.id === part.id)?.category_id === item.id).map(item => <option key={item.id} value={item.id}>{categoryLabel(item, categories)}</option>)}</select></label>{parts.length > 1 && <><label>Сумма части {index + 1}<input value={part.amount} onChange={event => update(part.id, { amount: event.target.value })} inputMode="decimal" required /></label><button type="button" className="secondary" onClick={() => setParts(items => items.filter(item => item.id !== part.id))}>Удалить часть {index + 1}</button></>}</div>)}
    <button type="button" className="secondary" disabled={parts.length >= 100} onClick={() => setParts(items => [...items.map(item => items.length === 1 ? { ...item, amount } : item), { id: crypto.randomUUID(), category: '', amount: '' }])}>Добавить часть</button>{remainder && <p role="status">Нераспределено: {remainder}</p>}
    {tags.some(item => !item.archived_at || initial?.tag_ids.includes(item.id)) && <fieldset className="tag-options"><legend>Теги операции</legend>{tags.filter(item => !item.archived_at || initial?.tag_ids.includes(item.id)).map(item => <label className="check" key={item.id}><input type="checkbox" name="tag" value={item.id} defaultChecked={initial?.tag_ids.includes(item.id)} />{item.name}</label>)}</fieldset>}
    <label>Получатель<input name="payee" defaultValue={initial?.payee} maxLength={200} /></label><label>Примечание<textarea aria-label="Примечание" name="note" defaultValue={initial?.note} maxLength={2000} /></label><button>{initial ? 'Сохранить изменения операции' : 'Сохранить операцию'}</button>{cancel && <button type="button" className="secondary" onClick={cancel}>Отменить редактирование</button>}</fieldset></form></section>;
}
