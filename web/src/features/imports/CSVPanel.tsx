import { useEffect, useRef, useState } from 'react';
import { request, RequestError, type Auth, type Account, type Transaction } from '../../api/client';
import { readCSVCommand, compareAuxiliaryCommand, AuxiliaryCommandConflict } from './commandStorage';
import { money } from '../../shared/money';

type Row = { source_transaction_id: string | null; row_number: number; status: string; errors: { message: string }[]; candidate_transaction_ids: string[]; amount_minor: string | null; occurred_at: string | null; payee: string };
type Preview = { currency: string; account_id: string; id: string; version: string; state: string; expires_at: string; error_code: string | null; rows: Row[]; processing: { row_number: number; state: string; transaction_id: string | null }[] };
type Decision = { row_number: number; action: string; transaction_id: string | null; expected_transaction_version: string | null };
type Command = { owner: string; session: string; workspace: string; generation: string; key: string; path: string; body: string };
function importReason(code: string): string {
  return ({ unauthenticated: 'Сессия завершена. Войдите снова и подтвердите продолжение.', not_found: 'Счет или пространство больше недоступны.', account_archived: 'Счет находится в архиве.', validation_error: 'Проверьте строки и актуальные данные счета.', sync_generation_conflict: 'Состояние данных изменилось после восстановления. Проверьте оставшиеся строки.', amount_out_of_range: 'Сумма выходит за поддерживаемый диапазон.' } as Record<string,string>)[code] ?? 'Обновите данные и проверьте оставшиеся строки.';
}
const storagePrefix = 'accounting.csv.pending.v1';
export function CSVPanel({ auth, accounts, changed, fail }: { auth: Auth; accounts: Account[]; changed: () => void; fail: (e: unknown) => void }) {
  const root = `/workspaces/${auth.workspace.id}`;
  const storage = `${storagePrefix}:${auth.profile.id}:${auth.workspace.id}:${auth.session.id}`;
  const identity = `${auth.profile.id}:${auth.workspace.id}`;
  const [preview, setPreview] = useState<Preview | null>(null), [decisions, setDecisions] = useState<Decision[]>([]), [matches, setMatches] = useState<Record<string, Transaction>>({});
  const [pending, setPending] = useState<Command | null>(null);
  const [storageReady,setStorageReady]=useState(false);
  const sending=useRef(false);
  function validCommand(v: Command | null): v is Command {
    return !!v && v.owner===auth.profile.id && v.session===auth.session.id && v.workspace===auth.workspace.id && typeof v.body==='string' && typeof v.key==='string' && typeof v.generation==='string' && new RegExp(`^${root}/imports(?:/[0-9a-f-]{36}/confirm)?$`).test(v.path);
  }
  useEffect(()=>{
    let cancelled=false;
    void (async()=>{
      let value=await readCSVCommand<Command>(storage);
      if(cancelled)return;
      if(!value){
        let legacy:Command|null=null;
        try{legacy=JSON.parse(sessionStorage.getItem(storage)??'null');}catch{/* No legacy draft. */}
        if(validCommand(legacy)&&!cancelled){
          try{await compareAuxiliaryCommand(storage,null,legacy);value=legacy;sessionStorage.removeItem(storage);}
          catch(e){if(!(e instanceof AuxiliaryCommandConflict))throw e;value=await readCSVCommand<Command>(storage);if(value?.key===legacy.key)sessionStorage.removeItem(storage);}
        }
      }
      if(!cancelled){if(value!==null&&!validCommand(value))throw new Error("Сохранённый запрос CSV требует проверки; новые запросы не отправляются.");setPending(value);setStorageReady(true);}
    })().catch(e=>{if(!cancelled)show(e);});
    return()=>{cancelled=true;};
  },[storage]);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [terminal, setTerminal] = useState(false), [confirm, setConfirm] = useState(false);
  const [accountID, setAccountID] = useState(accounts.find(a => !a.archived_at)?.id ?? '');
  const [exportFrom, setExportFrom] = useState(`${new Date().toISOString().slice(0, 7)}-01`), [exportTo, setExportTo] = useState(new Date(Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth() + 1, 1)).toISOString().slice(0, 10));
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  function show(e: unknown) { if (!active.current) return; setError(e instanceof Error ? e.message : 'Не удалось выполнить импорт.'); if (e instanceof RequestError && e.status === 401) { setPreview(null); setMatches({}); fail(e); } }
  function accept(value: Preview) { setPreview(value); if (value.state === 'ready') setDecisions(value.rows.map(r => ({ row_number: r.row_number, action: r.status === 'valid' ? 'create' : 'skip', transaction_id: null, expected_transaction_version: null }))); try { sessionStorage.setItem(`accounting.csv.last:${identity}`, value.id); } catch { /* In-memory fallback. */ } }
  async function refresh(id: string) { try { const p = await request<Preview>(`${root}/imports/${id}`); if (!active.current) return; accept(p); if (p.state === 'committed') changed(); } catch (e) { show(e); } }
  useEffect(() => { try { const id = sessionStorage.getItem(`accounting.csv.last:${identity}`); if (id && /^[a-f0-9-]{36}$/.test(id)) void refresh(id); } catch { /* In-memory fallback. */ } }, []);
  useEffect(() => { if (preview?.state !== 'queued') return; const timer = setTimeout(() => void refresh(preview.id), 2000); return () => clearTimeout(timer); }, [preview]);
  async function persist(v:Command|null,expectedKey:string|null) {
    try{await compareAuxiliaryCommand(storage,expectedKey,v);}catch(e){
      if(e instanceof AuxiliaryCommandConflict&&active.current){const latest=await readCSVCommand<Command>(storage);if(latest===null||validCommand(latest)){setPending(latest);setTerminal(false);}else setStorageReady(false);}
      throw e;
    }
    if(active.current)setPending(v);
  }
  async function execute(v: Command) {
    if (busy) return; setBusy(true); setError(''); setTerminal(false);
    try {
      const result = await request<Preview>(v.path, { method: 'POST', body: v.body, headers: { 'Content-Type': 'application/json', 'Idempotency-Key': v.key, 'X-Sync-Generation': v.generation, 'X-CSRF-Token': auth.csrf_token } });
      if (!active.current) return; await persist(null,v.key); accept(result); if (result.state === 'committed') changed();
    } catch (e) { if (e instanceof RequestError && [400, 404, 409, 413, 422].includes(e.status) && !['idempotency_conflict', 'action_result_expired', 'sync_generation_conflict'].includes(e.code)) setTerminal(true); show(e); }
    finally { if (active.current) setBusy(false); }
  }
  async function send(path: string, body: object) {
    if(!active.current||pending||busy||!storageReady||sending.current)return;
    sending.current=true;setBusy(true);
    const v={owner:auth.profile.id,session:auth.session.id,workspace:auth.workspace.id,generation:auth.workspace.sync_generation_id,key:crypto.randomUUID(),path,body:JSON.stringify(body)};
    try{await persist(v,null);if(!active.current)return;setBusy(false);await execute(v);}catch(e){show(e);}finally{sending.current=false;if(active.current)setBusy(false);}
  }
  async function choose(row: Row, action: string) {
    if (action.startsWith('link:')) {
      const id = action.slice(5);
      try { const t = await request<Transaction>(`${root}/transactions/${id}`); if (!active.current) return; setMatches(m => ({ ...m, [id]: t })); setDecisions(ds => ds.map(d => d.row_number === row.row_number ? { ...d, action: 'link', transaction_id: id, expected_transaction_version: t.version } : d)); } catch (e) { show(e); }
    } else setDecisions(ds => ds.map(d => d.row_number === row.row_number ? { ...d, action, transaction_id: null, expected_transaction_version: null } : d));
  }
  async function download() {
    setBusy(true); setError('');
    try {
      const q = new URLSearchParams({ from: `${exportFrom}T00:00:00Z`, to: `${exportTo}T00:00:00Z` }); if (accountID) q.set('account_id', accountID);
      const result = await request<{ content_base64: string; filename: string; row_count: number }>(`${root}/exports?${q}`);
      if (!active.current) return;
      const bytes = Uint8Array.from(atob(result.content_base64), c => c.charCodeAt(0)); const url = URL.createObjectURL(new Blob([bytes], { type: 'text/csv;charset=utf-8' })); const link = document.createElement('a'); link.href = url; link.download = 'accounting.csv'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) { show(e); } finally { if (active.current) setBusy(false); }
  }
  return <section aria-label="Экспорт CSV"><h2>Экспорт CSV</h2><p>Для импорта используйте раздел «Импорт из Wallet» и файл в формате wallet_records.csv.</p>{error && <p role="alert">{error}</p>}
    {pending && <aside><p>Сохранён запрос импорта. Повтор использует исходный файл и решения.</p><button disabled={busy || !storageReady} onClick={() => void execute(pending)}>Повторить запрос импорта</button>{terminal && <button disabled={busy} onClick={() => { void persist(null,pending.key).then(()=>setTerminal(false)).catch(show); }}>Закрыть отклонённый запрос импорта</button>}</aside>}
    {preview && <><h3>Результат импорта</h3><p>Состояние: {({ ready: 'Готов к подтверждению', queued: 'Выполняется', committed: 'Завершён', failed: 'Требуется проверка и повтор', expired: 'Предпросмотр истёк' } as Record<string, string>)[preview.state]}</p>{preview.error_code && <p>{preview.state==='ready'?'Появились новые возможные совпадения. Предпросмотр обновлён; проверьте решения и подтвердите их заново.':`Причина остановки: ${importReason(preview.error_code)} Уже примененные строки сохраняются; повтор продолжит оставшиеся.`}</p>}<p>Создано: {preview.processing.filter(p => p.state === 'applied').length}; сопоставлено: {preview.processing.filter(p => p.state === 'linked').length}; пропущено: {preview.processing.filter(p => p.state === 'skipped').length}</p><button disabled={busy} onClick={() => void refresh(preview.id)}>Обновить результат импорта</button>
      <table><thead><tr><th>Строка</th><th>Дата UTC</th><th>Сумма</th><th>Получатель</th><th>Проверка</th><th>Решение</th></tr></thead><tbody>{preview.rows.map(row => { const decision = decisions.find(d => d.row_number === row.row_number); const selected = decision?.transaction_id ? matches[decision.transaction_id] : null; return <tr key={row.row_number}><td>{row.row_number}</td><td>{row.occurred_at ?? '—'}</td><td>{row.amount_minor === null ? '—' : money(row.amount_minor, preview.currency)}</td><td>{row.payee}</td><td>{row.status === 'invalid' ? row.errors.map(e => e.message).join(' ') : row.status === 'ambiguous' ? 'Возможно совпадение — выберите решение' : 'Готово'}</td><td>{preview.state === 'ready' && <select aria-label={`Решение строки ${row.row_number}`} value={decision?.action === 'link' ? `link:${decision.transaction_id}` : decision?.action ?? 'skip'} onChange={e => void choose(row, e.target.value)} disabled={busy || !storageReady || !!pending}><option value="skip">Пропустить</option>{row.status !== 'invalid' && !row.source_transaction_id && <option value="create">Создать отдельную операцию</option>}{row.candidate_transaction_ids.map((id, i) => <option key={id} value={`link:${id}`}>Проверить совпадение {i + 1}</option>)}</select>}{selected && <p>{selected.payee || selected.note} · {selected.occurred_at} · {selected.entries.map(e => money(e.amount_minor, e.currency)).join(', ')}. Категория и примечание этой операции сохранятся.</p>}</td></tr>; })}</tbody></table>
      {preview.state === 'ready' && <button disabled={busy || !storageReady || !!pending} onClick={() => setConfirm(true)}>Проверить подтверждение CSV</button>}{preview.state === 'failed' && <button disabled={busy || !storageReady || !!pending} onClick={() => setConfirm(true)}>Проверить повтор оставшихся строк</button>}
      {confirm && <aside><p>{preview.state === 'failed' ? 'Продолжить только неприменённые строки с текущими правами доступа?' : `Подтвердить решения для ${decisions.length} строк? Финансовые данные будут записаны после подтверждения.`}</p><button disabled={busy || !storageReady || !!pending} onClick={() => { setConfirm(false); send(`${root}/imports/${preview.id}/confirm`, { expected_version: preview.version, decisions: preview.state === 'failed' ? [] : decisions }); }}>Подтвердить CSV импорт</button><button onClick={() => setConfirm(false)}>Отмена подтверждения CSV</button></aside>}</>}
    <p>Время UTC; конец периода не включается. Экспорт содержит движения, категории, теги и исходные идентификаторы.</p><label>Счет экспорта CSV<select value={accountID} onChange={e => setAccountID(e.target.value)}><option value="">Все счета</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label><label>Экспорт от<input type="date" value={exportFrom} onChange={e => setExportFrom(e.target.value)} /></label><label>Экспорт до<input type="date" value={exportTo} onChange={e => setExportTo(e.target.value)} /></label><button disabled={busy} onClick={() => void download()}>Скачать CSV</button>
  </section>;
}
