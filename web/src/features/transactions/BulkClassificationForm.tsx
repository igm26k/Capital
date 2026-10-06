import type { FormEvent } from 'react';
import type { Category, Tag, Transaction } from '../../api/client';
import { categoryLabel } from '../../shared/categories';
export function BulkClassificationForm({ selected, categories, tags, disabled, submit, clear }: { selected: Transaction[]; categories: Category[]; tags: Tag[]; disabled: boolean; submit: (body: object) => void; clear: () => void }) {
  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected.length || selected.length > 100) return;
    const data = new FormData(event.currentTarget);
    submit({ items: selected.map(t => ({ id: t.id, expected_version: t.version })), category_id: data.get('bulk_category') || null, tag_ids: data.getAll('bulk_tag') });
  }
  return <div className="notice"><h3>Массовая классификация</h3><p>Выбрано операций: {selected.length}. Категория и теги ниже заменят прежние назначения у всех выбранных операций. Суммы останутся прежними.</p><ul>{selected.map(t => <li key={t.id}>{t.payee || t.note || new Date(t.occurred_at).toLocaleString('ru-RU')}</li>)}</ul><form onSubmit={save}><fieldset disabled={disabled}><label>Категория выбранных операций<select aria-label="Категория выбранных операций" name="bulk_category"><option value="">Без категории</option>{categories.filter(c => !c.archived_at).map(c => <option key={c.id} value={c.id}>{categoryLabel(c, categories)}</option>)}</select></label>
    <fieldset className="tag-options"><legend>Теги выбранных операций</legend>{tags.filter(t => !t.archived_at).map(t => <label className="check" key={t.id}><input type="checkbox" aria-label={`Массовый тег ${t.name}`} name="bulk_tag" value={t.id} />{t.name}</label>)}</fieldset>
    <button>Применить ко всем выбранным</button><button type="button" className="secondary" onClick={clear}>Снять выбор операций</button>
  </fieldset></form></div>;
}
