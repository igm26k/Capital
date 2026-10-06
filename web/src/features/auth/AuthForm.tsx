import { useState, type FormEvent } from 'react';
import { request, RequestError, type Auth } from '../../api/client';
import { timezone } from '../../shared/time';
export { timezone };
export function AuthForm({ onAuth }: { onAuth: (auth: Auth) => void }) {
  const [register, setRegister] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('');
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError('');
    const data = new FormData(event.currentTarget);
    try {
      onAuth(await request<Auth>(register ? '/auth/register' : '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: data.get('email'), password: data.get('password'), device_name: 'Веб-браузер', transport: 'cookie', ...(register ? { timezone: timezone() } : {}) }) }));
    } catch (error) { setError(error instanceof RequestError && error.code === 'registration_disabled' ? 'Регистрация отключена в этом окружении. Используйте существующий профиль.' : error instanceof RequestError && error.status === 401 ? 'Проверьте электронную почту и пароль.' : error instanceof Error ? error.message : 'Не удалось войти. Проверьте соединение и повторите.'); }
    finally { setBusy(false); }
  }
  return <section className="auth"><h2>{register ? 'Создать профиль' : 'Войти в Accounting'}</h2><p>Счета, расходы и доходы в одном месте.</p><form onSubmit={submit}><label>Электронная почта<input name="email" type="email" autoComplete="username" required maxLength={254} /></label><label>Пароль<input name="password" type="password" autoComplete={register ? 'new-password' : 'current-password'} required minLength={register ? 12 : 1} maxLength={128} /></label>{error && <p role="alert">{error}</p>}<button disabled={busy}>{busy ? 'Подождите…' : register ? 'Зарегистрироваться' : 'Войти'}</button></form><button className="secondary" disabled={busy} onClick={() => { setRegister(!register); setError(''); }}>{register ? 'Уже есть профиль' : 'Создать профиль'}</button>{register && <small>Регистрация доступна, если она включена владельцем окружения.</small>}</section>;
}
