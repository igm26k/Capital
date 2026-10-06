import { useState, type FormEvent } from 'react';
import type { Category, Tag } from '../../api/client';
import { canParent, categoryLabel } from '../../shared/categories';
type Props = { categories: Category[]; tags: Tag[]; disabled: boolean; submit: (path: string, body: object, method?: 'POST' | 'PUT') => void; fail: (message: string) => void };
export function ClassificationManager({ categories, tags, disabled, submit, fail }: Props) {
  const [category, setCategory] = useState<Category | null>(null), [tag, setTag] = useState<Tag | null>(null);
  function save(event: FormEvent<HTMLFormElement>, kind: 'categories' | 'tags') {
    event.preventDefault();
    const data = new FormData(event.currentTarget), item = kind === 'categories' ? category : tag;
    const name = String(data.get('name')).trim();
    if (!name) { fail('Введите название.'); return; }
    const body = { name, ...(kind === 'categories' ? { parent_id: data.get('parent') || null } : {}), ...(item ? { expected_version: item.version, archived: data.has('archived') } : { id: crypto.randomUUID() }) };
    submit(`${kind}${item ? `/${item.id}` : ''}`, body, item ? 'PUT' : 'POST');
  }
  return <section><h2>Категории и теги</h2><p className="hint">Архивные категории и теги сохраняются в истории, но недоступны для новых операций.</p><div className="forms">
    <div><h3>Категории</h3><ul className="reference-list">{[...categories].sort((a, b) => categoryLabel(a, categories).localeCompare(categoryLabel(b, categories), 'ru')).map(item => <li key={item.id}><span>{categoryLabel(item, categories)}{item.archived_at && ' · В архиве'}</span><button type="button" className="secondary" disabled={disabled} onClick={() => setCategory(item)} aria-label={`Изменить категорию ${item.name}`}>Изменить</button></li>)}</ul>
    <form key={`category-${category?.id ?? 'new'}-${category?.version ?? ''}`} onSubmit={event => save(event, 'categories')}><fieldset disabled={disabled}><h3>{category ? 'Изменить категорию' : 'Новая категория'}</h3><label>Название категории<input name="name" required maxLength={100} defaultValue={category?.name} /></label><label>Родительская категория<select name="parent" defaultValue={category?.parent_id ?? ''}><option value="">Без родителя</option>{categories.filter(item => canParent(item, category?.id, categories) || item.id === category?.parent_id).map(item => <option key={item.id} value={item.id}>{categoryLabel(item, categories)}</option>)}</select></label>{category && <label className="check"><input type="checkbox" name="archived" defaultChecked={!!category.archived_at} />Категория в архиве</label>}<button>{category ? 'Сохранить категорию' : 'Создать категорию'}</button>{category && <button className="secondary" type="button" onClick={() => setCategory(null)}>Отменить изменение категории</button>}</fieldset></form></div>
    <div><h3>Теги</h3><ul className="reference-list">{tags.map(item => <li key={item.id}><span>{item.name}{item.archived_at && ' · В архиве'}</span><button type="button" className="secondary" disabled={disabled} onClick={() => setTag(item)} aria-label={`Изменить тег ${item.name}`}>Изменить</button></li>)}</ul><form key={`tag-${tag?.id ?? 'new'}-${tag?.version ?? ''}`} onSubmit={event => save(event, 'tags')}><fieldset disabled={disabled}><h3>{tag ? 'Изменить тег' : 'Новый тег'}</h3><label>Название тега<input name="name" required maxLength={50} defaultValue={tag?.name} /></label>{tag && <label className="check"><input type="checkbox" name="archived" defaultChecked={!!tag.archived_at} />Тег в архиве</label>}<button>{tag ? 'Сохранить тег' : 'Создать тег'}</button>{tag && <button className="secondary" type="button" onClick={() => setTag(null)}>Отменить изменение тега</button>}</fieldset></form></div>
  </div></section>;
}
