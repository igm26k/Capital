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
  return <section><h2>Новый счет</h2><form onSubmit={create}><fieldset disabled={disabled}><label>Название счета<input name="name" required maxLength={100} placeholder="Основной счет" /></label><fieldset className="choice-group"><legend>Тип счета</legend><div className="filter-chips">{[['bank', 'Банк'], ['cash', 'Наличные'], ['card', 'Карта']].map(([value, label]) => <label className="choice-chip" key={value}><input type="radio" name="type" value={value} defaultChecked={value === 'bank'} /><span>{label}</span></label>)}</div></fieldset><fieldset className="choice-group"><legend>Валюта счета</legend><div className="filter-chips">{currencies.map(code => <label className="choice-chip" key={code}><input type="radio" name="currency" value={code} checked={currency === code} onChange={() => setCurrency(code)} /><span>{code}</span></label>)}</div></fieldset><label>Начальный остаток, {currency}<input name="balance" inputMode="decimal" required defaultValue="0" /></label><OccurrenceFields label="Дата открытия счета" name="opened_at" /><button className="primary-action">Создать счет</button></fieldset></form></section>;
}
