import type { Account, Category, Tag, Transaction } from '../../api/client';
import { categoryLabel } from '../../shared/categories';
import { money } from '../../shared/money';
import { Icon } from '../../shared/Icon';
const kinds: Record<string, string> = { opening: 'Начальный остаток', expense: 'Расход', income: 'Доход', transfer: 'Перевод', refund: 'Возврат', adjustment: 'Корректировка' };
const symbols: Record<string, string> = { EUR: '€', USD: '$', RUB: '₽', GBP: '£', JPY: '¥' };
type Props = { transaction: Transaction; accounts: Account[]; categories: Category[]; tags: Tag[]; disabled: boolean; selectable: boolean; selected: boolean; selectionDisabled: boolean; select: (checked: boolean) => void; edit: () => void; refund: () => void; remove: () => void };
export function OperationCard({ transaction: t, accounts, categories, tags, disabled, selectable, selected, selectionDisabled, select, edit, refund, remove }: Props) {
  const kind = t.kind === 'expense' && t.parent_transaction_id ? 'Комиссия перевода' : kinds[t.kind] ?? t.kind;
  const category = (id: string | null) => id ? categories.find(c => c.id === id) ? categoryLabel(categories.find(c => c.id === id)!, categories) : 'Категория недоступна' : 'Без категории';
  const account = (id: string) => accounts.find(a => a.id === id)?.name ?? 'Счет недоступен';
  const label = t.payee || kind;
  const entries = t.kind === 'transfer' ? [...t.entries].sort((a, b) => BigInt(a.amount_minor) < BigInt(b.amount_minor) ? -1 : 1) : t.entries;
  const names = [...new Set(t.allocations.map(p => category(p.category_id)))].join(' · ');
  return <li className={`operation-card${selected ? ' is-selected' : ''}`} data-testid={`transaction-${t.id}`}>
    <details className="operation-details"><summary>
      <span className="operation-icon"><Icon name={t.kind === 'transfer' ? 'transfer' : t.kind === 'income' || t.kind === 'refund' ? 'income' : t.kind === 'opening' || t.kind === 'adjustment' ? 'bank' : 'expense'} /></span>
      <span className="operation-copy"><strong title={label}>{label}</strong><span title={names}>{[names || kind, entries.map(e => account(e.account_id)).join(' → ')].filter(Boolean).join(' · ')}</span>{t.note && <small className="operation-preview">{t.note}</small>}{t.status === 'pending' && <small className="pending-label">Предварительная</small>}</span>
      <span className="operation-values">{entries.map((e, i) => <strong key={i} className={t.kind === 'transfer' || t.kind === 'opening' || t.kind === 'adjustment' ? 'neutral-value' : BigInt(e.amount_minor) > 0n ? 'income-value' : 'expense-value'}>{money(e.amount_minor, e.currency).replace(e.currency, symbols[e.currency] ?? e.currency)}</strong>)}<time dateTime={t.occurred_at}>{new Date(t.occurred_at).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: '2-digit', hour: '2-digit', minute: '2-digit' })}</time></span>
      <span className="operation-chevron" aria-hidden="true">⌄</span>
    </summary><div className="operation-expanded">
      <div className="operation-detail-heading"><strong>{kind}</strong><time dateTime={t.occurred_at}>{new Date(t.occurred_at).toLocaleString('ru-RU')}</time></div>
      {t.payee && <p>{t.payee}</p>}{t.reason && <p>Причина: {t.reason}</p>}{t.note && <p className="operation-note">{t.note}</p>}
      <ul className="operation-movements">{entries.map((e, i) => <li key={i}><span>{account(e.account_id)}</span><strong>{money(e.amount_minor, e.currency)}</strong></li>)}</ul>
      {t.allocations.map(p => <p className="operation-allocation" key={p.id}>{category(p.category_id)} · {money(p.amount_minor, t.entries[0]?.currency ?? 'EUR')}</p>)}
      {t.tag_ids.length > 0 && <p>Теги: {t.tag_ids.map(id => tags.find(tag => tag.id === id)?.name ?? 'Недоступный тег').join(', ')}</p>}
      {selectable && <label className="check operation-select"><input type="checkbox" aria-label={`Выбрать операцию ${t.payee || t.note || t.id}`} disabled={selectionDisabled} checked={selected} onChange={event => select(event.target.checked)} />Выбрать для классификации</label>}
      <div className="operation-actions">{['expense', 'income', 'transfer', 'refund', 'adjustment'].includes(t.kind) && <button className="secondary" disabled={disabled} onClick={edit} aria-label={`Изменить операцию ${t.payee || t.note || t.id}`}>Изменить</button>}
        {t.kind === 'expense' && t.status === 'posted' && t.allocations.some(p => BigInt(p.remaining_refundable_minor) > 0n) && <button className="secondary" disabled={disabled} aria-label={`Возврат по операции ${t.payee || t.note || t.id}`} onClick={refund}>Возврат</button>}
        {t.kind !== 'opening' && <button className="text-button operation-delete" disabled={disabled} aria-label={`Удалить операцию ${t.payee || t.note || t.id}`} onClick={remove}>Удалить</button>}
      </div>
    </div></details>
  </li>;
}
