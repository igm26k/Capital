import { useState, type FormEvent } from 'react';
import type { Account } from '../../api/client';
import { money } from '../../shared/money';
type Props = { accounts: Account[]; disabled: boolean; loading: boolean; submit: (path: string, body: object, method: 'PUT') => void; fail: (message: string) => void };
export function AccountManager({ accounts, disabled, loading, submit, fail }: Props) {
  const [selected, setSelected] = useState<Account | null>(null);
  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected) return;
    const data = new FormData(event.currentTarget), name = String(data.get('name')).trim();
    if (!name) { fail('Введите название счета.'); return; }
    submit(`accounts/${selected.id}`, { expected_version: selected.version, name, type: data.get('type'), archived: data.has('archived') }, 'PUT');
  }
  return <section><h2>Счета</h2>{accounts.length ? <ul className="accounts">{accounts.map(account => <li key={account.id}><span>{account.name}{account.archived_at && ' · В архиве'}</span><strong data-testid={`balance-${account.id}`}>{money(account.posted_balance_minor, account.currency)}</strong><button className="secondary" disabled={disabled} aria-label={`Изменить счет ${account.name}`} onClick={() => setSelected(account)}>Изменить</button></li>)}</ul> : <p>{loading ? 'Загружаем счета…' : 'Создайте первый счет с начальным остатком.'}</p>}
    {selected && <form key={`${selected.id}-${selected.version}`} onSubmit={save}><fieldset disabled={disabled}><h3>Изменить счет</h3><label>Новое название счета<input name="name" required maxLength={100} defaultValue={selected.name} /></label><label>Тип выбранного счета<select aria-label="Тип выбранного счета" name="type" defaultValue={selected.type}><option value="bank">Банковский счет</option><option value="cash">Наличные</option><option value="card">Карта</option></select></label><p>Валюта: {selected.currency}. Остаток: {money(selected.posted_balance_minor, selected.currency)}.</p><label className="check"><input type="checkbox" name="archived" defaultChecked={!!selected.archived_at} />Счет в архиве</label><p className="hint">Архив сохраняет историю и остаток. Для новых операций восстановите счет.</p><button>Сохранить счет</button><button type="button" className="secondary" onClick={() => setSelected(null)}>Отменить изменение счета</button></fieldset></form>}
  </section>;
}
