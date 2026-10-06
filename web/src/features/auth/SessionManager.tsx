import { useEffect, useRef, useState } from 'react';
import { listAll, RequestError, type DeviceSession } from '../../api/client';
type Props = { disabled: boolean; revoke: (id: string, current: boolean) => Promise<void>; fail: (value: unknown) => void };
export function SessionManager({ disabled, revoke, fail }: Props) {
  const alive = useRef(false), sequence = useRef(0);
  const [sessions, setSessions] = useState<DeviceSession[]>([]), [loading, setLoading] = useState(false), [uncertain, setUncertain] = useState<DeviceSession | null>(null), [notice, setNotice] = useState('');
  async function load() {
    const requestID = ++sequence.current;
    setLoading(true);
    try { const items = await listAll<DeviceSession>('/sessions'); if (alive.current && requestID === sequence.current) setSessions(items); }
    catch (error) { if (alive.current && requestID === sequence.current) fail(error); }
    finally { if (alive.current && requestID === sequence.current) setLoading(false); }
  }
  useEffect(() => { alive.current = true; void load(); return () => { alive.current = false; sequence.current++; }; }, []);
  async function remove(session: DeviceSession) {
    if (disabled || loading) return;
    setNotice('');
    try {
      await revoke(session.id, session.is_current);
      if (!alive.current) return;
      setUncertain(null); setNotice('Устройство отключено.'); await load();
    } catch (error) {
      if (!alive.current) return;
      if (error instanceof RequestError && error.status < 500 && ![408, 425, 429].includes(error.status)) { setUncertain(null); fail(error); }
      else { setUncertain(session); setNotice('Ответ не получен. Устройство могло быть отключено. Повторите отзыв той же сессии.'); }
    }
  }
  return <section><h2>Устройства</h2><button className="secondary" disabled={disabled || loading} onClick={() => void load()}>Обновить устройства</button>
    {notice && <p role="status">{notice}</p>}{uncertain && <button disabled={disabled || loading} onClick={() => void remove(uncertain)}>Повторить отзыв устройства</button>}
    {loading && <p role="status">Загружаем устройства…</p>}
    <ul className="reference-list">{sessions.map(session => <li key={session.id} data-testid={`session-${session.id}`}><div><strong>{session.device_name}</strong>{session.is_current && <p>Текущее устройство</p>}<p className="hint">Последняя активность: {new Date(session.last_seen_at).toLocaleString('ru-RU')}</p></div><button className="secondary" disabled={disabled || loading || !!uncertain} aria-label={`Отключить устройство ${session.device_name}${session.is_current ? ' (текущее)' : ''}`} onClick={() => void remove(session)}>{session.is_current ? 'Завершить текущую сессию' : 'Отключить'}</button></li>)}</ul>
  </section>;
}
