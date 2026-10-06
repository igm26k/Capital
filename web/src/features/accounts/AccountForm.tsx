import type { FormEvent } from 'react';
import { minor } from '../../shared/money';
import { timezone } from '../auth/AuthForm';
export function AccountForm({ disabled, submit, fail }: { disabled: boolean; submit: (body: object) => void; fail: (message: string) => void }) {
  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    try { submit({ id: crypto.randomUUID(), name: data.get('name'), type: 'bank', currency: 'EUR', opened_at: new Date().toISOString(), occurred_timezone: timezone(), opening_balance_minor: minor(String(data.get('balance'))) }); }
    catch (error) { fail((error as Error).message); }
  }
  return <section><h2>Новый счет</h2><form onSubmit={create}><fieldset disabled={disabled}><label>Название счета<input name="name" required maxLength={100} placeholder="Основной счет" /></label><label>Начальный остаток, EUR<input name="balance" inputMode="decimal" required defaultValue="1000,00" /></label><button>Создать счет</button></fieldset></form></section>;
}
