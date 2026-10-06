import type { Category, Tag, Transaction } from '../../api/client';
import { categoryLabel } from '../../shared/categories';
export type BulkComparison = { proposed: { items: { id: string; expected_version: string }[]; category_id: string | null; tag_ids: string[] }; current: (Transaction | null)[] };
export function BulkConflict({ value, categories, tags, disabled, refresh }: { value: BulkComparison; categories: Category[]; tags: Tag[]; disabled: boolean; refresh: () => void }) {
  function category(id: string | null) { const item = categories.find(c => c.id === id); return item ? categoryLabel(item, categories) : id ? 'Категория недоступна' : 'Без категории'; }
  function tagNames(ids: string[]) { return ids.map(id => tags.find(t => t.id === id)?.name ?? 'Тег недоступен').sort().join(', ') || 'Без тегов'; }
  const desired = `${category(value.proposed.category_id)} · ${tagNames(value.proposed.tag_ids)}`;
  return <section className="notice"><h2>Конфликт массовой классификации</h2><p>Пакет отклонен целиком. Ни одна выбранная операция этой командой не изменена. Ниже — текущие назначения, прочитанные после отказа.</p><table><thead><tr><th>Операция</th><th>Выбранные назначения</th><th>На сервере</th></tr></thead><tbody>{value.current.map((transaction, index) => <tr key={value.proposed.items[index].id}><th>{transaction?.payee || transaction?.note || `Операция ${index + 1}`}</th><td>{desired}</td><td>{transaction ? `${transaction.allocations.map(p => category(p.category_id)).join('; ') || 'Без частей'} · ${tagNames(transaction.tag_ids)}` : 'Операция недоступна или удалена'}</td></tr>)}</tbody></table><button disabled={disabled} onClick={refresh}>Обновить историю и снять выбор</button></section>;
}
