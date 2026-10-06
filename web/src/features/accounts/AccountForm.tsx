import { useState, type FormEvent } from 'react';
import { currencies, minor } from '../../shared/money';
import { occurrence } from '../../shared/time';
import { OccurrenceFields } from '../../shared/OccurrenceFields';
export function AccountForm({ disabled, submit, fail }: { disabled: boolean; submit: (body: object) => void; fail: (message: string) => void }) {
  const [currency, setCurrency] = useState('EUR');
  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    try { const when = occurrence(data, undefined, 'opened_at'); submit({ id: crypto.randomUUID(), name: data.get('name'), type: data.get('type'), currency, opened_at: when.occurred_at, occurred_timezone: when.occurred_timezone, opening_balance_minor: minor(String(data.get('balance')), currency) }); }
    catch (error) { fail((error as Error).message); }
  }
  return <section><h2>Новый счет</h2><form onSubmit={create}><fieldset disabled={disabled}><label>Название счета<input name="name" required maxLength={100} placeholder="Основной счет" /></label><label>Тип счета<select aria-label="Тип счета" name="type"><option value="bank">Банковский счет</option><option value="cash">Наличные</option><option value="card">Карта</option></select></label><label>Валюта счета<select aria-label="Валюта счета" value={currency} onChange={event => setCurrency(event.target.value)}>{currencies.map(code => <option key={code}>{code}</option>)}</select></label><label>Начальный остаток, {currency}<input name="balance" inputMode="decimal" required defaultValue="1000" /></label><OccurrenceFields label="Дата открытия счета" name="opened_at" /><button>Создать счет</button></fieldset></form></section>;
}
