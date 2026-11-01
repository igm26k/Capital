import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
type Option = { value: string; label: string; group?: string; icon?: ReactNode; color?: string };
function SelectOptions({ label, value, options, choose, close }: { label: string; value: string; options: Option[]; choose: (value: string) => void; close: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null), list = useRef<HTMLDivElement>(null), content = useRef<HTMLDivElement>(null);
  const [query, setQuery] = useState(''), [thumb, setThumb] = useState({ top: 0, height: 0 });
  const filtered = options.filter(o => o.label.toLocaleLowerCase().includes(query.toLocaleLowerCase()));
  const groups = [...new Set(filtered.map(o => o.group ?? ''))];
  function measure() {
    const node = list.current;
    if (!node) return;
    const range = node.scrollHeight - node.clientHeight;
    const height = Math.min(node.clientHeight, Math.max(24, node.clientHeight * node.clientHeight / node.scrollHeight));
    setThumb({ height: range > 1 ? height : 0, top: range > 0 ? node.scrollTop / range * (node.clientHeight - height) : 0 });
  }
  useEffect(() => {
    const node = dialog.current!, previous = document.activeElement;
    node.showModal();
    return () => { node.close(); if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, []);
  useLayoutEffect(() => {
    if (list.current) list.current.scrollTop = 0;
    measure();
    const observer = new ResizeObserver(measure);
    if (list.current) observer.observe(list.current);
    if (content.current) observer.observe(content.current);
    return () => observer.disconnect();
  }, [query]);
  return <dialog ref={dialog} className="search-options select-dialog" aria-label={`Выбор: ${label}`} onCancel={e => { e.preventDefault(); e.stopPropagation(); close(); }} onKeyDown={e => e.stopPropagation()}>
    <h3>{label}</h3><label>Поиск: {label}<input autoFocus value={query} onChange={e => setQuery(e.target.value)} /></label>
    <p className="select-count" role="status">Найдено: {filtered.length}</p>
    <div className="select-scroll-area"><div ref={list} role="listbox" aria-label={label} onScroll={measure} tabIndex={0}><div ref={content}>{groups.map(group => <div key={group}>{group && <strong style={{ color: filtered.find(o => o.group === group)?.color }}>{group}</strong>}{filtered.filter(o => (o.group ?? '') === group).map(o => <button type="button" role="option" aria-selected={o.value === value} key={o.value} onClick={() => choose(o.value)}>{o.icon}{o.label}</button>)}</div>)}{!filtered.length && <p>Ничего не найдено</p>}</div></div>
    {thumb.height > 0 && <span className="select-scrollbar" aria-hidden="true"><span style={{ top: thumb.top, height: thumb.height }} /></span>}</div>
    <button type="button" className="secondary" onClick={close}>Закрыть</button>
  </dialog>;
}
export function SearchSelect({ label, value, options, onChange, name }: { label: string; value: string; options: Option[]; onChange: (value: string) => void; name?: string }) {
  const [open, setOpen] = useState(false);
  return <div className="search-select">
    <span>{label}</span><input type="hidden" name={name} value={value} />
    <button type="button" aria-label={label} aria-expanded={open} aria-haspopup="dialog" onClick={() => setOpen(true)}>{options.find(o => o.value === value)?.icon}{options.find(o => o.value === value)?.label ?? 'Выберите'} ▾</button>
    {open && <SelectOptions label={label} value={value} options={options} close={() => setOpen(false)} choose={id => { onChange(id); setOpen(false); }} />}
  </div>;
}
