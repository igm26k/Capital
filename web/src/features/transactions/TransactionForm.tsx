import { useState, type FormEvent } from 'react';
import type { Account } from '../../api/client';
import { minor } from '../../shared/money';
import { timezone } from '../auth/AuthForm';
export function TransactionForm({ accounts, disabled, submit, fail }: { accounts: Account[]; disabled: boolean; submit: (body: object) => void; fail: (message: string) => void }) {
  const [kind, setKind] = useState('expense');
  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget), account = accounts.find(a => a.id === data.get('account'));
    if (!account) return;
    try {
      const amount = minor(String(data.get('amount')), account.currency);
      if (BigInt(amount) <= 0n) throw new Error('Сумма должна быть больше нуля.');
      submit({ id: crypto.randomUUID(), kind, account_id: account.id, amount_minor: amount, occurred_at: new Date().toISOString(), occurred_timezone: timezone(), note: data.get('note'), payee: data.get('payee'), tag_ids: [], allocations: [{ id: crypto.randomUUID(), category_id: null, amount_minor: amount }] });
    } catch (error) { fail((error as Error).message); }
  }
  return <section><h2>Новая операция</h2><form onSubmit={create}><fieldset disabled={disabled || !accounts.length}><label>Тип операции<select value={kind} onChange={e => setKind(e.target.value)}><option value="expense">Расход</option><option value="income">Доход</option></select></label><label>Счет<select name="account">{accounts.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label><label>Сумма<input name="amount" inputMode="decimal" required placeholder="12,34" /></label><label>Получатель<input name="payee" maxLength={200} /></label><label>Примечание<textarea name="note" maxLength={2000} /></label><button>Сохранить операцию</button></fieldset></form></section>;
}
