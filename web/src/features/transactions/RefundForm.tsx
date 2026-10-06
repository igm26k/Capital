import { useState, type FormEvent } from 'react';
import type { Account, Category, Tag, Transaction } from '../../api/client';
import { decimal, minor, money } from '../../shared/money';
import { categoryLabel } from '../../shared/categories';
import { timezone } from '../auth/AuthForm';
type Props = { parent: Transaction; transferVersion: string | null; initial?: Transaction; accounts: Account[]; categories: Category[]; tags: Tag[]; disabled: boolean; submit: (body: object) => void; fail: (message: string) => void; cancel: () => void };
export function RefundForm({ parent, transferVersion, initial, accounts, categories, tags, disabled, submit, fail, cancel }: Props) {
  const currency = parent.entries[0].currency;
  const eligible = accounts.filter(a => a.currency === currency && (!a.archived_at || a.id === initial?.entries[0]?.account_id));
  const [accountID, setAccountID] = useState(initial?.entries[0]?.account_id ?? parent.entries[0].account_id);
  const account = eligible.find(a => a.id === accountID) ?? eligible[0];
  const [parts, setParts] = useState(parent.allocations.map(original => {
    const prior = initial?.allocations.find(p => p.original_allocation_id === original.id);
    return { original, id: prior?.id ?? crypto.randomUUID(), amount: prior ? decimal(prior.amount_minor, currency) : '', maximum: (BigInt(original.remaining_refundable_minor) + BigInt(prior?.amount_minor ?? '0')).toString() };
  }));
  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!account) return;
    const data = new FormData(event.currentTarget);
    try {
      const allocations = parts.map(p => ({ id: p.id, original_allocation_id: p.original.id, amount_minor: p.amount ? minor(p.amount, currency) : '0' }));
      for (let i = 0; i < parts.length; i++) {
        const amount = BigInt(allocations[i].amount_minor);
        if (amount < 0n || amount > BigInt(parts[i].maximum)) throw new Error('Возврат части должен быть неотрицательным и не превышать доступный остаток.');
      }
      const positive = allocations.filter(p => BigInt(p.amount_minor) > 0n);
      const total = positive.reduce((sum, p) => sum + BigInt(p.amount_minor), 0n);
      if (total <= 0n) throw new Error('Укажите положительную сумму хотя бы одной части возврата.');
      if (total > 9000000000000000n) throw new Error('Сумма выходит за допустимый диапазон.');
      submit({ ...(initial ? { expected_version: initial.version } : { id: crypto.randomUUID() }), kind: 'refund', account_id: account.id, amount_minor: total.toString(), parent_transaction_id: parent.id, expected_parent_version: parent.version, expected_transfer_version: transferVersion, allocations: positive, occurred_at: initial?.occurred_at ?? new Date().toISOString(), occurred_timezone: initial?.occurred_timezone ?? timezone(), note: data.get('refund_note'), payee: data.get('refund_payee'), tag_ids: data.getAll('refund_tag') });
    } catch (error) { fail((error as Error).message); }
  }
  return <section><h2>{initial ? 'Изменить возврат' : 'Новый возврат'}</h2><p>Исходный расход: {parent.payee || parent.note || new Date(parent.occurred_at).toLocaleString('ru-RU')}. Категории наследуются от его частей.</p><form onSubmit={save}><fieldset disabled={disabled || !account}>
    <label>Счет возврата<select aria-label="Счет возврата" value={account?.id ?? ''} onChange={event => setAccountID(event.target.value)}>{eligible.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label>
    {parts.map((part, index) => { const category = categories.find(c => c.id === part.original.category_id); return <div className="allocation" key={part.id}><p>{category ? categoryLabel(category, categories) : 'Без категории'} · Доступно: {money(part.maximum, currency)}</p><label>Возврат части {index + 1}<input value={part.amount} inputMode="decimal" placeholder="0" onChange={event => setParts(items => items.map(p => p.id === part.id ? { ...p, amount: event.target.value } : p))} /></label></div>; })}
    <label>Получатель возврата<input name="refund_payee" maxLength={200} defaultValue={initial?.payee} /></label><label>Описание возврата<textarea aria-label="Описание возврата" name="refund_note" maxLength={2000} defaultValue={initial?.note} /></label>
    {tags.some(t => !t.archived_at || initial?.tag_ids.includes(t.id)) && <fieldset className="tag-options"><legend>Теги возврата</legend>{tags.filter(t => !t.archived_at || initial?.tag_ids.includes(t.id)).map(t => <label className="check" key={t.id}><input type="checkbox" name="refund_tag" value={t.id} defaultChecked={initial?.tag_ids.includes(t.id)} />{t.name}</label>)}</fieldset>}
    <button>{initial ? 'Сохранить изменения возврата' : 'Сохранить возврат'}</button><button type="button" className="secondary" onClick={cancel}>Отменить возврат</button>
  </fieldset></form>{!account && <p>Для возврата нужен активный счет в валюте {currency}.</p>}</section>;
}
