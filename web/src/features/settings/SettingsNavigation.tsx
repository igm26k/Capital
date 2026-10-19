import { useEffect, useRef, useState } from 'react';
import { Icon } from '../../shared/Icon';
export const settingsGroups = [
  ['profile', 'Профиль'], ['classification', 'Категории и теги'], ['analytics', 'Бюджеты и отчеты'],
  ['data', 'Импорт и экспорт'], ['sources', 'Банки и уведомления'], ['security', 'Безопасность и данные'],
] as const;
export type SettingsGroup = typeof settingsGroups[number][0];
const storageKey = 'accounting.settings.group.v1';
export function restoreSettingsGroup(): SettingsGroup {
  try { const value = sessionStorage.getItem(storageKey); return settingsGroups.find(([id]) => id === value)?.[0] ?? 'profile'; } catch { return 'profile'; }
}
export function SettingsNavigation({ value, change, active }: { value: SettingsGroup; change: (value: SettingsGroup) => void; active: boolean }) {
  const [open, setOpen] = useState(false), wrapper = useRef<HTMLDivElement>(null), trigger = useRef<HTMLButtonElement>(null);
  useEffect(() => { try { sessionStorage.setItem(storageKey, value); } catch { /* Navigation still works in memory. */ } }, [value]);
  useEffect(() => { if (!active) setOpen(false); }, [active]);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => { if (event.target instanceof Node && !wrapper.current?.contains(event.target)) setOpen(false); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setOpen(false); trigger.current?.focus(); } };
    document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); };
  }, [open]);
  return <div className="settings-navigation" ref={wrapper} onBlur={event => { if (event.relatedTarget instanceof Node && !event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}>
    <div className="screen-heading settings-heading"><div><h2>Настройки</h2><p className="hint">{settingsGroups.find(([id]) => id === value)?.[1]}</p></div><button className="icon-button settings-trigger" ref={trigger} aria-label="Группы настроек" aria-expanded={open} aria-controls="settings-group-menu" onClick={() => setOpen(v => !v)}><Icon name="menu" /></button></div>
    {open && <nav className="settings-group-menu" id="settings-group-menu" aria-label="Группы настроек">{settingsGroups.map(([id, label]) => <button key={id} aria-current={value === id ? 'page' : undefined} onClick={() => { change(id); setOpen(false); trigger.current?.focus(); }}>{label}<span aria-hidden="true">{value === id ? '✓' : '›'}</span></button>)}</nav>}
  </div>;
}
