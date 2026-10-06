import { useState, type FormEvent } from 'react';
import { currencies, minor } from '../../shared/money';
import { timezone } from '../auth/AuthForm';
export function AccountForm({ disabled, submit, fail }: { disabled: boolean; submit: (body: object) => void; fail: (message: string) => void }) {
  const [currency, setCurrency] = useState('EUR');
  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    try { submit({ id: crypto.randomUUID(), name: data.get('name'), type: data.get('type'), currency, opened_at: new Date().toISOString(), occurred_timezone: timezone(), opening_balance_minor: minor(String(data.get('balance')), currency) }); }
    catch (error) { fail((error as Error).message); }
  }
  return <section><h2>Новый счет</h2><form onSubmit={create}><fieldset disabled={disabled}><label>Название счета<input name="name" required maxLength={100} placeholder="Основной счет" /></label><label>Тип счета<select aria-label="Тип счета" name="type"><option value="bank">Банковский счет</option><option value="cash">Наличные</option><option value="card">Карта</option></select></label><label>Валюта счета<select aria-label="Валюта счета" value={currency} onChange={event => setCurrency(event.target.value)}>{currencies.map(code => <option key={code}>{code}</option>)}</select></label><label>Начальный остаток, {currency}<input name="balance" inputMode="decimal" required defaultValue="1000,00" /></label><button>Создать счет</button></fieldset></form></section>;
}
