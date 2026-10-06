import type { Category } from '../api/client';
import { categoryLabel } from './categories';
export type ResourceComparison = { kind: 'accounts' | 'categories' | 'tags'; proposed: {name: string; archived: boolean; type?: string; parent_id?: string | null}; current: {id: string; name: string; archived_at: string | null; type?: string; parent_id?: string | null} };
export function ResourceConflict({ value, categories, disabled, reload }: { value: ResourceComparison; categories: Category[]; disabled: boolean; reload: () => void }) {
  const { proposed, current, kind } = value;
  const rows = [['Название', proposed.name, current.name], ['Архив', proposed.archived ? 'В архиве' : 'Активен', current.archived_at ? 'В архиве' : 'Активен']];
  if (kind === 'accounts') { const types: Record<string, string> = {cash: 'Наличные', bank: 'Банковский счет', card: 'Карта'}; rows.push(['Тип счета', types[proposed.type ?? ''] ?? '', types[current.type ?? ''] ?? '']); }
  if (kind === 'categories') { const parent = (id: string | null | undefined) => { const item = categories.find(c => c.id === id); return item ? categoryLabel(item, categories) : id ? 'Категория недоступна' : 'Без родителя'; }; rows.push(['Родитель', parent(proposed.parent_id), parent(current.parent_id)]); }
  return <section className="notice"><h2>Конфликт справочника</h2><p>Изменение отклонено. Введенные поля остаются в форме; сравните их с текущей записью сервера.</p><table><thead><tr><th>Поле</th><th>Ваша правка</th><th>На сервере</th></tr></thead><tbody>{rows.map(([label, ours, theirs]) => <tr key={label}><th>{label}</th><td>{ours}</td><td>{theirs}</td></tr>)}</tbody></table><button disabled={disabled} onClick={reload}>Обновить справочники и закрыть их редакторы</button></section>;
}
