import { useState } from 'react';
import { request, RequestError, type Auth } from '../../api/client';

export function PrivacyPanel({ auth, deleted, fail }: { auth: Auth; deleted: () => void | Promise<void>; fail: (e: unknown) => void }) {
  const [password, setPassword] = useState(''), [confirmation, setConfirmation] = useState(''), [busy, setBusy] = useState(false), [removing, setRemoving] = useState(false);
  async function download() {
    setBusy(true);
    try {
      const response = await fetch('/api/v1/me/export', { credentials: 'include', cache: 'no-store' });
      if (!response.ok) { const error=await response.json().catch(()=>null);throw new RequestError(response.status,error?.code??'http_error',error?.message??'Не удалось экспортировать данные.'); }
      const blob = await response.blob(), url = URL.createObjectURL(blob), link = document.createElement('a');
      link.href = url; link.download = 'accounting-user-data.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) { fail(e); } finally { setBusy(false); }
  }
  async function remove() {
    if (confirmation !== 'DELETE') return;
    setBusy(true);
    try { await request('/me', { method: 'DELETE', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': auth.csrf_token }, body: JSON.stringify({ password, confirmation }) }); setPassword(''); await deleted(); }
    catch (e) { setPassword(''); fail(e); }
    finally { setBusy(false); }
  }
  return <details><summary>Экспорт и удаление профиля</summary><p>JSON экспорт содержит ваши доступные финансовые пространства, в том числе удаленные записи, категории, теги и настройки. Пароли и токены в экспорт не входят.</p><button disabled={busy} onClick={() => void download()}>Скачать все финансовые данные JSON</button><p>Удаление профиля удаляет личные финансовые пространства и отзывает все устройства. Пространство с другими активными участниками сначала требует решения о владельце. Копии в резервном хранилище удаляются по опубликованной политике хранения.</p><button disabled={busy} onClick={() => setRemoving(true)}>Удалить профиль и личные данные</button>{removing && <form onSubmit={e => { e.preventDefault(); void remove(); }}><label>Пароль для удаления<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required maxLength={128} /></label><label>Введите DELETE для подтверждения<input value={confirmation} onChange={e => setConfirmation(e.target.value)} required /></label><button disabled={busy || confirmation !== 'DELETE'}>Подтвердить удаление профиля</button><button type="button" disabled={busy} onClick={() => { setRemoving(false); setPassword(''); setConfirmation(''); }}>Отмена удаления профиля</button></form>}</details>;
}
