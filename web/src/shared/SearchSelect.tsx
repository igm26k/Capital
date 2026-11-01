import { useState } from 'react';
type Option = { value: string; label: string; group?: string };
export function SearchSelect({ label, value, options, onChange, name }: { label: string; value: string; options: Option[]; onChange: (value: string) => void; name?: string }) {
  const [open, setOpen] = useState(false), [query, setQuery] = useState('');
  const filtered = options.filter(o => o.label.toLocaleLowerCase().includes(query.toLocaleLowerCase()));
  const groups = [...new Set(filtered.map(o => o.group ?? ''))];
  return <div className="search-select">
    <span>{label}</span><input type="hidden" name={name} value={value} />
    <button type="button" aria-label={label} aria-expanded={open} onClick={() => { setOpen(!open); setQuery(''); }}>{options.find(o => o.value === value)?.label ?? 'Выберите'} ▾</button>
    {open && <div className="search-options"><label>Поиск: {label}<input autoFocus value={query} onChange={e => setQuery(e.target.value)} /></label><div role="listbox" aria-label={label}>{groups.map(group => <div key={group}>{group && <strong>{group}</strong>}{filtered.filter(o => (o.group ?? '') === group).map(o => <button type="button" role="option" aria-selected={o.value === value} key={o.value} onClick={() => { onChange(o.value); setOpen(false); }}>{o.label}</button>)}</div>)}{!filtered.length && <p>Ничего не найдено</p>}</div><button type="button" onClick={() => setOpen(false)}>Закрыть</button></div>}
  </div>;
}
