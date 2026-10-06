import type { Account, Category, Tag, Transaction } from '../../api/client';
import { categoryLabel } from '../../shared/categories';
import { money } from '../../shared/money';
type Allocation = { category_id?: string | null; amount_minor: string };
export type ProposedTransaction = { kind: string; note: string; payee: string; occurred_at: string; tag_ids: string[]; account_id?: string; amount_minor?: string; allocations?: Allocation[]; source_account_id?: string; target_account_id?: string; source_amount_minor?: string; target_amount_minor?: string; fee?: { account_id: string; amount_minor: string; allocations: Allocation[]; note: string; tag_ids: string[] } | null };
export type TransactionComparison = { proposed: ProposedTransaction; current: Transaction; fee: Transaction | null };
type Props = { value: TransactionComparison; accounts: Account[]; categories: Category[]; tags: Tag[]; disabled: boolean; reload: () => void; dismiss: () => void };
export function TransactionConflict({ value, accounts, categories, tags, disabled, reload, dismiss }: Props) {
  const { proposed, current, fee } = value;
  const account = (id: string | undefined) => accounts.find(a => a.id === id)?.name ?? 'Счет недоступен';
  const tagNames = (ids: string[]) => ids.map(id => tags.find(t => t.id === id)?.name ?? 'Тег недоступен').sort().join(', ') || 'Без тегов';
  const parts = (items: Allocation[], currency: string) => items.map(p => { const c = categories.find(c => c.id === p.category_id); return `${c ? categoryLabel(c, categories) : p.category_id ? 'Категория недоступна' : p.category_id === undefined ? 'Категория исходной части' : 'Без категории'}: ${money(p.amount_minor, currency)}`; }).sort().join('; ') || 'Без частей';
  const currency = current.entries[0]?.currency ?? 'EUR';
  const movement = proposed.kind === 'transfer' ? [
    `${account(proposed.source_account_id)}: ${money(`-${proposed.source_amount_minor}`, current.entries.find(e => BigInt(e.amount_minor) < 0n)?.currency ?? currency)}`,
    `${account(proposed.target_account_id)}: ${money(proposed.target_amount_minor!, current.entries.find(e => BigInt(e.amount_minor) > 0n)?.currency ?? currency)}`,
  ].sort().join('; ') : `${account(proposed.account_id)}: ${money(`${proposed.kind === 'expense' ? '-' : ''}${proposed.amount_minor}`, currency)}`;
  const rows = [
    ['Примечание', proposed.note || 'Без примечания', current.note || 'Без примечания'],
    ['Получатель', proposed.payee || 'Без получателя', current.payee || 'Без получателя'],
    ['Дата', new Date(proposed.occurred_at).toLocaleString('ru-RU'), new Date(current.occurred_at).toLocaleString('ru-RU')],
    ['Движения', movement, current.entries.map(e => `${account(e.account_id)}: ${money(e.amount_minor, e.currency)}`).sort().join('; ')],
    ['Части', parts(proposed.allocations ?? [], currency), parts(current.allocations, currency)],
    ['Теги', tagNames(proposed.tag_ids), tagNames(current.tag_ids)],
  ];
  if (proposed.kind === 'transfer') {
    const feeCurrency = accounts.find(a => a.id === proposed.fee?.account_id)?.currency ?? fee?.entries[0]?.currency ?? currency;
    rows.push(['Комиссия', proposed.fee ? `${account(proposed.fee.account_id)}: ${money(proposed.fee.amount_minor, feeCurrency)}` : 'Без комиссии', fee ? `${account(fee.entries[0].account_id)}: ${money(fee.allocations.reduce((sum, p) => sum + BigInt(p.amount_minor), 0n).toString(), fee.entries[0].currency)}` : 'Без комиссии']);
    rows.push(['Части комиссии', proposed.fee ? parts(proposed.fee.allocations, feeCurrency) : 'Без частей', fee ? parts(fee.allocations, fee.entries[0].currency) : 'Без частей']);
  }
  return <section className="notice"><h2>Конфликт операции</h2><p>Правка отклонена. Сравните ее с данными сервера. Введенные значения остаются в редакторе.</p><table><thead><tr><th>Поле</th><th>Ваша правка</th><th>На сервере</th></tr></thead><tbody>{rows.map(([label, ours, theirs]) => <tr key={label}><th>{label}</th><td>{ours}</td><td>{theirs}</td></tr>)}</tbody></table><button disabled={disabled} onClick={reload}>Загрузить серверную версию в редактор</button><button className="secondary" disabled={disabled} onClick={dismiss}>Скрыть сравнение</button></section>;
}
