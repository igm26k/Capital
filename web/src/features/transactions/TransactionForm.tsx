import { useState, type FormEvent } from 'react';
import type { Account, Category, Tag } from '../../api/client';
import { minor, money } from '../../shared/money';
import { categoryLabel } from '../../shared/categories';
import { timezone } from '../auth/AuthForm';
type Part = { id: string; category: string; amount: string };
export function TransactionForm({ accounts, categories, tags, disabled, submit, fail }: { accounts: Account[]; categories: Category[]; tags: Tag[]; disabled: boolean; submit: (body: object) => void; fail: (message: string) => void }) {
  const [kind, setKind] = useState('expense'), [accountID, setAccountID] = useState(accounts[0]?.id ?? ''), [amount, setAmount] = useState('');
  const [parts, setParts] = useState<Part[]>([{ id: crypto.randomUUID(), category: '', amount: '' }]);
  const account = accounts.find(item => item.id === (accountID || accounts[0]?.id));
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
      submit({ id: crypto.randomUUID(), kind, account_id: account.id, amount_minor: total, occurred_at: new Date().toISOString(), occurred_timezone: timezone(), note: data.get('note'), payee: data.get('payee'), tag_ids: data.getAll('tag'), allocations });
    } catch (error) { fail((error as Error).message); }
  }
  return <section><h2>Новая операция</h2><form onSubmit={create}><fieldset disabled={disabled || !accounts.length}><label>Тип операции<select value={kind} onChange={e => setKind(e.target.value)}><option value="expense">Расход</option><option value="income">Доход</option></select></label><label>Счет<select name="account" value={account?.id ?? ''} onChange={event => setAccountID(event.target.value)}>{accounts.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label><label>Сумма<input name="amount" value={amount} onChange={event => setAmount(event.target.value)} inputMode="decimal" required placeholder="12,34" /></label>
    {parts.map((part, index) => <div className="allocation" key={part.id}><label>{parts.length === 1 ? 'Категория' : `Категория части ${index + 1}`}<select aria-label={parts.length === 1 ? 'Категория' : `Категория части ${index + 1}`} value={part.category} onChange={event => update(part.id, { category: event.target.value })}><option value="">Без категории</option>{categories.filter(item => !item.archived_at).map(item => <option key={item.id} value={item.id}>{categoryLabel(item, categories)}</option>)}</select></label>{parts.length > 1 && <><label>Сумма части {index + 1}<input value={part.amount} onChange={event => update(part.id, { amount: event.target.value })} inputMode="decimal" required /></label><button type="button" className="secondary" onClick={() => setParts(items => items.filter(item => item.id !== part.id))}>Удалить часть {index + 1}</button></>}</div>)}
    <button type="button" className="secondary" disabled={parts.length >= 100} onClick={() => setParts(items => [...items.map(item => items.length === 1 ? { ...item, amount } : item), { id: crypto.randomUUID(), category: '', amount: '' }])}>Добавить часть</button>{remainder && <p role="status">Нераспределено: {remainder}</p>}
    {tags.some(item => !item.archived_at) && <fieldset className="tag-options"><legend>Теги операции</legend>{tags.filter(item => !item.archived_at).map(item => <label className="check" key={item.id}><input type="checkbox" name="tag" value={item.id} />{item.name}</label>)}</fieldset>}
    <label>Получатель<input name="payee" maxLength={200} /></label><label>Примечание<textarea name="note" maxLength={2000} /></label><button>Сохранить операцию</button></fieldset></form></section>;
}
