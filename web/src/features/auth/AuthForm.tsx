import { useState, type FormEvent } from 'react';
import { request, RequestError, type Auth } from '../../api/client';
import { timezone } from '../../shared/time';
export { timezone };
export function AuthForm({ onAuth }: { onAuth: (auth: Auth) => void }) {
  const [register, setRegister] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState(''), [mode,setMode]=useState<'normal'|'registration'|'recovery'|'recovery-confirm'>('normal');
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError('');
    const data = new FormData(event.currentTarget);
    try {
      const body=mode==='recovery'?{email:data.get('email')}:mode==='recovery-confirm'?{email:data.get('email'),code:data.get('code'),password:data.get('password')}:mode==='registration'?{email:data.get('email'),code:data.get('code'),password:data.get('password'),device_name:'Веб-браузер',transport:'cookie'}:{email:data.get('email'),password:data.get('password'),device_name:'Веб-браузер',transport:'cookie',...(register?{timezone:timezone()}:{})};
      const path=mode==='recovery'?'/auth/recovery/request':mode==='recovery-confirm'?'/auth/recovery/confirm':mode==='registration'?'/auth/registration/confirm':register?'/auth/register':'/auth/login';
      const result=await request<Auth|{status:string}>(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
      if('profile' in result){onAuth(result);return;}
      if(result.status==='password_changed'){setMode('normal');setRegister(false);setError('Пароль изменён, прежние сессии отозваны. Войдите с новым паролем.');}
      else{setMode(mode==='recovery'?'recovery-confirm':'registration');setError('Если запрос можно подтвердить, письмо с кодом будет отправлено. Код действует 30 минут. Для регистрации повторите исходный пароль.');}
    } catch (error) { setError(error instanceof RequestError && error.code === 'registration_disabled' ? 'Эта возможность отключена в этом окружении. Обратитесь к владельцу сервера.' : error instanceof RequestError && error.code==='confirmation_invalid'?'Код недействителен, истёк или исходный пароль не совпадает. Если ответ подтверждения был потерян, попробуйте вход.':error instanceof RequestError && error.status === 401 ? 'Проверьте электронную почту и пароль.' : error instanceof Error ? error.message : 'Не удалось войти. Проверьте соединение и повторите.'); }
    finally { setBusy(false); }
  }
  return <section className="auth"><h2>{mode.startsWith('recovery')?'Восстановить пароль':mode==='registration'?'Подтвердить электронную почту':register ? 'Создать профиль' : 'Войти в Accounting'}</h2><p>Счета, расходы и доходы в одном месте.</p><form onSubmit={submit}><label>Электронная почта<input name="email" type="email" autoComplete="username" required maxLength={254} /></label>{mode!=='recovery'&&<label>Пароль<input name="password" type="password" autoComplete={register ? 'new-password' : 'current-password'} required minLength={register||mode!=='normal' ? 12 : 1} maxLength={128} /></label>}{(mode==='registration'||mode==='recovery-confirm')&&<label>Код из письма<input name="code" autoComplete="one-time-code" required minLength={43} maxLength={43}/></label>}{error && <p role="alert">{error}</p>}<button disabled={busy}>{busy ? 'Подождите…' : mode==='registration'?'Подтвердить регистрацию':mode==='recovery'?'Отправить код восстановления':mode==='recovery-confirm'?'Сохранить новый пароль':register ? 'Зарегистрироваться' : 'Войти'}</button></form><button className="secondary" disabled={busy} onClick={() => { setMode('normal');setRegister(!register); setError(''); }}>{register ? 'Уже есть профиль' : 'Создать профиль'}</button><button className="secondary" disabled={busy} onClick={()=>{setMode('recovery');setError('');}}>Забыли пароль?</button>{mode!=='normal'&&<button disabled={busy} onClick={()=>{setMode('normal');setRegister(false);setError('');}}>Вернуться ко входу</button>}{register && <small>Регистрация доступна, если она включена владельцем окружения.</small>}</section>;
}
