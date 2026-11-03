import { useEffect } from 'react';
import { Icon, type Name } from '../../shared/Icon';
export const settingsGroups = [
  ['home', 'Настройки'], ['profile', 'Профиль'], ['accounts', 'Счета'], ['classification', 'Категории и теги'], ['analytics', 'Бюджеты, отчёты и правила'],
  ['data', 'Импорт и экспорт'], ['sources', 'Банки и уведомления'], ['security', 'Безопасность и сессии'],
] as const;
export type SettingsGroup = typeof settingsGroups[number][0];
export function restoreSettingsGroup(): SettingsGroup { try { return settingsGroups.find(([id]) => id === sessionStorage.getItem('accounting.settings.group.v1'))?.[0] ?? 'home'; } catch { return 'home'; } }
const groups: { title: string; rows: [SettingsGroup, string, Name][] }[] = [
  { title: 'Управление финансами', rows: [['accounts', 'Счета', 'card'], ['classification', 'Категории и теги', 'category'], ['analytics', 'Бюджеты, отчёты и правила', 'records']] },
  { title: 'Интеграции и данные', rows: [['sources', 'Банки и уведомления', 'bank'], ['data', 'Импорт и экспорт', 'transfer']] },
  { title: 'Приложение', rows: [['profile', 'Профиль и оформление', 'settings'], ['security', 'Безопасность и сессии', 'work']] },
];
export function SettingsNavigation({ value, change, active, email, workspace, timezone }: { value: SettingsGroup; change: (value: SettingsGroup) => void; active: boolean; email: string; workspace: string; timezone?: string }) {
  useEffect(() => { try { sessionStorage.setItem('accounting.settings.group.v1', value); } catch { /* Navigation remains available. */ } }, [value]);
  useEffect(() => { if (!active) change('home'); }, [active]);
  return <div className="settings-navigation">
    <div className="screen-heading settings-heading">{value !== 'home' && <button className="icon-button" aria-label="Назад к настройкам" onClick={() => change('home')}>‹</button>}<h2>{settingsGroups.find(([id]) => id === value)?.[1]}</h2></div>
    {value === 'home' && <><button className="settings-profile" onClick={() => change('profile')}><span className="profile-avatar"><Icon name="user" /></span><span><strong>Профиль</strong><small>{email}</small></span><span>›</span></button><div className="profile-context"><p><Icon name="work" /><span>Пространство<strong>{workspace}</strong></span></p><p><Icon name="clock" /><span>Часовой пояс<strong>{timezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone}</strong></span></p></div>
    {groups.map(group => <div className="settings-menu-group" key={group.title}><h3>{group.title}</h3><nav aria-label={group.title}>{group.rows.map(([id, label, icon]) => <button key={id} onClick={() => change(id)}><Icon name={icon} /><span>{label}</span><span aria-hidden="true">›</span></button>)}</nav></div>)}</>}
  </div>;
}
