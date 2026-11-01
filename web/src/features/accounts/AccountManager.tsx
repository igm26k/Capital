import { useState, type FormEvent, type ReactNode, type CSSProperties } from 'react';
import type { Account } from '../../api/client';
import { orderAccounts } from '../../shared/accountOrder';
import { money } from '../../shared/money';
import { AccountForm } from './AccountForm';
import { Icon } from '../../shared/Icon';
type Props = { accounts: Account[]; disabled: boolean; loading: boolean; submit: (path: string, body: object, method?: 'POST' | 'PUT') => void; fail: (message: string) => void; history: (id: string) => void; records: () => void; manage?: boolean; orderVersion?: string; addOperation?: () => void; children?: ReactNode };
const currencySymbols: Record<string, string> = { EUR: '€', USD: '$', RUB: '₽', GBP: '£', JPY: '¥' };
export function AccountManager({ accounts, disabled, loading, submit, fail, history, records, children, manage = false, orderVersion = '0', addOperation }: Props) {
  const [selected, setSelected] = useState<Account | null>(null);
  const [creating, setCreating] = useState(false);
  const settings = manage;
  const [draftOrder, setDraftOrder] = useState<{ ids: string[]; version: string } | null>(null);
  const ordered = draftOrder ? orderAccounts(accounts, draftOrder.ids) : accounts;
  const [filter, setFilter] = useState('active');
  const visible = ordered.filter(a => filter === 'all' || (filter === 'archived' ? !!a.archived_at : !a.archived_at));
  const totals = new Map<string, bigint>();
  for (const account of visible) totals.set(account.currency, (totals.get(account.currency) ?? 0n) + BigInt(account.posted_balance_minor));
  function move(id: string, delta: number) {
    const index = visible.findIndex(a => a.id === id), neighbor = visible[index + delta];
    if (!neighbor) return;
    const ids = ordered.map(a => a.id), from = ids.indexOf(id), to = ids.indexOf(neighbor.id);
    [ids[from], ids[to]] = [ids[to], ids[from]];
    setDraftOrder({ ids, version: draftOrder?.version ?? orderVersion });
  }
  const changed = !!draftOrder && ordered.some((a, i) => a.id !== accounts[i]?.id);
  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected) return;
    const data = new FormData(event.currentTarget), name = String(data.get('name')).trim();
    if (!name) { fail('Введите название счета.'); return; }
    submit(`accounts/${selected.id}`, { expected_version: selected.version, name, type: data.get('type'), archived: data.has('archived') }, 'PUT');
  }
  return <div className="account-screen">
    <div hidden={creating || !!selected}>
      <div className="accounts-toolbar"><h2>{manage ? "Управление счетами" : "Список счетов"} <span>{visible.length}</span></h2></div>
      {settings && <div className="filter-chips" role="group" aria-label="Фильтр счетов">{[['active', 'Активные счета'], ['archived', 'Архивные счета'], ['all', 'Все счета']].map(([value, label]) => <button key={value} className="chip" aria-pressed={filter === value} onClick={() => setFilter(value)}>{label}</button>)}</div>}
      {loading && <p role="status">Загружаем счета…</p>}
      {!loading && !visible.length && <section className="empty-state"><h3>{accounts.length ? 'В выбранном фильтре счетов нет' : 'Создайте первый счет'}</h3><p>{accounts.length ? 'Выберите другой фильтр, чтобы увидеть остальные счета.' : 'Добавьте карту, банковский счет или наличные в разделе Настройки → Счета.'}</p></section>}
      {manage && <><p className="hint">Стрелки меняют порядок счетов на главном экране и во всех списках выбора.</p><button disabled={disabled} onClick={() => setCreating(true)}><Icon name="plus" /> Добавить счет</button>{changed && <div className="account-order-actions"><button disabled={disabled} onClick={() => submit('accounts/order', { expected_version: draftOrder!.version, account_ids: ordered.map(a => a.id) }, 'PUT')}>Сохранить порядок</button><button className="secondary" disabled={disabled} onClick={() => setDraftOrder(null)}>Отменить порядок</button></div>}
      <ul className="account-management-list" aria-label="Порядок счетов">{visible.map((account, index) => <li key={account.id} data-account-id={account.id}>
        <div className="account-list-copy"><strong>{account.name}</strong><span>{money(account.posted_balance_minor, account.currency)}{account.archived_at && ' · В архиве'}</span></div>
        <div className="account-list-actions"><button className="secondary" disabled={disabled || index === 0} aria-label={`Поднять счет ${account.name}`} onClick={() => move(account.id, -1)}>↑</button><button className="secondary" disabled={disabled || index === visible.length - 1} aria-label={`Опустить счет ${account.name}`} onClick={() => move(account.id, 1)}>↓</button><button className="secondary" disabled={disabled} aria-label={`Изменить счет ${account.name}`} onClick={() => setSelected(account)}><Icon name="settings" /></button></div>
      </li>)}</ul></>}
      {!manage && <ul className="account-tiles">{visible.map(account => {
        const colors = ['#167b68', '#2463af', '#8545a4', '#b64a32', '#466d30', '#3c578d'];
        const color = colors[[...account.id].reduce((sum, char) => sum + char.charCodeAt(0), 0) % colors.length];
        return <li className="account-tile" key={account.id} style={{ '--tile-color': color } as CSSProperties}>
          <button className="tile-main" disabled={disabled} aria-label={`Операции счета ${account.name}`} onClick={() => history(account.id)}>
            <span className="tile-name" title={account.name}>{account.name}</span>
            <strong className="tile-balance" data-testid={`${manage ? "manage-" : ""}balance-${account.id}`}>{money(account.posted_balance_minor, account.currency).replace(account.currency, currencySymbols[account.currency] ?? account.currency)}</strong>
          </button>
          {settings && <button className="tile-edit" disabled={disabled} aria-label={`Изменить счет ${account.name}`} onClick={() => setSelected(account)}>Изменить</button>}
        </li>;
      })}<li className="add-account-tile"><button disabled={disabled} onClick={() => manage ? setCreating(true) : addOperation?.()}><Icon name="plus" /><span>{manage ? "Добавить счет" : "Добавить операцию"}</span></button></li></ul>}
      {!!visible.length && <div className="currency-summary" aria-label={manage ? "Итоги счетов в настройках" : "Итоги по валютам"}><span>Всего</span>{[...totals].sort(([a], [b]) => a.localeCompare(b)).map(([currency, total]) => <strong key={currency}>{money(total.toString(), currency)}</strong>)}</div>}
      {!manage && <div className="records-shortcut"><button className="text-button" onClick={records}><Icon name="records" />Записи<Icon name="arrow" /></button></div>}
      {children}
    </div>
    {creating && <div className="account-editor"><button className="text-button" disabled={disabled} onClick={() => setCreating(false)}>← Вернуться к счетам</button><AccountForm disabled={disabled} submit={body => submit('accounts', body)} fail={fail} /></div>}
    {selected && <div className="account-editor"><button className="text-button" disabled={disabled} onClick={() => setSelected(null)}>← Вернуться к счетам</button><section><form key={`${selected.id}-${selected.version}`} onSubmit={save}><fieldset disabled={disabled}><h2>Изменить счет</h2><label>Новое название счета<input name="name" required maxLength={100} defaultValue={selected.name} /></label><label>Тип выбранного счета<select aria-label="Тип выбранного счета" name="type" defaultValue={selected.type}><option value="bank">Банковский счет</option><option value="cash">Наличные</option><option value="card">Карта</option></select></label><p>Валюта: {selected.currency}. Остаток: {money(selected.posted_balance_minor, selected.currency)}.</p><label className="check"><input type="checkbox" name="archived" defaultChecked={!!selected.archived_at} />Счет в архиве</label><p className="hint">Архив сохраняет историю и остаток. Для новых операций восстановите счет.</p><button className="primary-action">Сохранить счет</button></fieldset></form></section></div>}
  </div>;
}
