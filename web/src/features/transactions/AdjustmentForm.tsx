import { useState, type FormEvent } from 'react';
import type { Account, Transaction } from '../../api/client';
import { decimal, minor, money } from '../../shared/money';
import { timezone } from '../auth/AuthForm';
type Props = { accounts: Account[]; initial?: Transaction; disabled: boolean; submit: (path: string, body: object, method: 'POST' | 'PUT') => void; fail: (message: string) => void; cancel?: () => void };
export function AdjustmentForm({ accounts, initial, disabled, submit, fail, cancel }: Props) {
  const [accountID, setAccountID] = useState('');
  const account = accounts.find(a => a.id === accountID) ?? accounts[0];
  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    try {
      if (initial) submit(`transactions/${initial.id}`, { kind: 'adjustment', expected_version: initial.version, note: data.get('adjustment_note') }, 'PUT');
      else if (account) {
        const reason = String(data.get('reason')).trim();
        if (!reason) throw new Error('Укажите причину корректировки.');
        submit(`accounts/${account.id}/adjustments`, { id: crypto.randomUUID(), expected_balance_version: account.balance_version, target_balance_minor: minor(String(data.get('target_balance')), account.currency), reason, note: data.get('adjustment_note'), occurred_timezone: timezone() }, 'POST');
      }
    } catch (error) { fail((error as Error).message); }
  }
  return <section><h2>{initial ? 'Изменить примечание корректировки' : 'Корректировка остатка'}</h2><form onSubmit={save}><fieldset disabled={disabled || (!initial && !account)}>
    {initial ? <p>Причина: {initial.reason}. Финансовое движение корректировки сохраняется.</p> : <>
      <label>Счет корректировки<select aria-label="Счет корректировки" value={account?.id ?? ''} onChange={event => setAccountID(event.target.value)}>{accounts.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label>
      {account && <p>Подтвержденный остаток сейчас: {money(account.posted_balance_minor, account.currency)}.</p>}
      <label>Фактический остаток, {account?.currency ?? 'EUR'}<input key={`${account?.id}-${account?.balance_version}`} name="target_balance" inputMode="decimal" required defaultValue={account ? decimal(account.posted_balance_minor, account.currency) : ''} /></label>
      <label>Причина корректировки<input name="reason" required maxLength={500} /></label><p className="hint">Разница будет сохранена отдельным движением с указанной причиной.</p>
    </>}
    <label>Примечание корректировки<textarea aria-label="Примечание корректировки" name="adjustment_note" maxLength={2000} defaultValue={initial?.note} /></label><button>{initial ? 'Сохранить примечание корректировки' : 'Сохранить корректировку'}</button>{cancel && <button type="button" className="secondary" onClick={cancel}>Отменить редактирование</button>}
  </fieldset></form></section>;
}
