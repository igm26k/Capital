import { useEffect, useRef, useState } from 'react';
import { request, listAll, RequestError, type Category, type Tag, type Auth, type Account, type Transaction, type List, type Workspace } from './api/client';
import { SessionManager } from './features/auth/SessionManager';
import { AuthForm } from './features/auth/AuthForm';
import { AccountManager } from './features/accounts/AccountManager';
import { AccountForm } from './features/accounts/AccountForm';
import { TransferForm } from './features/transactions/TransferForm';
import { TransactionForm } from './features/transactions/TransactionForm';
import { ClassificationManager } from './features/classification/ClassificationManager';
import { TransactionConflict, type ProposedTransaction, type TransactionComparison } from './features/transactions/TransactionConflict';
import { TransactionHistory } from './features/transactions/TransactionHistory';
// A single unfinished command survives reload in this tab. No credentials are persisted.
type Command = { owner: string; session: string; workspace: string; generation: string; key: string; path: string; body: string; method?: 'POST' | 'PUT' };
const storageKey = 'accounting.pending.v1';
function restore(auth: Auth): Command | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null') as Command | null;
    if (value?.owner === auth.profile.id && value.session === auth.session.id && value.workspace === auth.workspace.id && typeof value.body === 'string' && typeof value.key === 'string' && typeof value.generation === 'string' && (!value.method || ['POST', 'PUT'].includes(value.method)) && new RegExp(`^/workspaces/${value.workspace}/(?:accounts|transactions|categories|tags)(?:/[0-9a-f-]{36})?$`).test(value.path)) return value;
    sessionStorage.removeItem(storageKey);
  } catch { /* Storage unavailable: retries remain available while this page is open. */ }
  return null;
}
function persist(command: Command | null) { try { command ? sessionStorage.setItem(storageKey, JSON.stringify(command)) : sessionStorage.removeItem(storageKey); } catch { /* In-memory fallback. */ } }
export function App() {
  const activeSession = useRef<string | null>(null);
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [auth, setAuth] = useState<Auth | null>(null), [starting, setStarting] = useState(true), [startupError, setStartupError] = useState('');
  const [accounts, setAccounts] = useState<Account[]>([]), [historyRevision, setHistoryRevision] = useState(0);
  const [conflict, setConflict] = useState<TransactionComparison | null>(null);
  const [editingFee, setEditingFee] = useState<Transaction | null>(null), [editingParentVersion, setEditingParentVersion] = useState<string | null>(null);
  const [editing, setEditing] = useState<Transaction | null>(null);
  const [categories, setCategories] = useState<Category[]>([]), [tags, setTags] = useState<Tag[]>([]);
  const [pending, setPending] = useState<Command | null>(null), [busy, setBusy] = useState(false), [loading, setLoading] = useState(false), [message, setMessage] = useState(''), [error, setError] = useState(''), [online, setOnline] = useState(navigator.onLine), [formVersion, setFormVersion] = useState(0);
  function authenticated(value: Auth) { activeSession.current = value.session.id; setAccounts([]); setCategories([]); setTags([]); setEditing(null); setConflict(null); setAuth(value); setPending(restore(value)); setError(''); setMessage(''); }
  async function session() {
    setStarting(true); setStartupError('');
    try { authenticated(await request<Auth>('/auth/session')); }
    catch (error) { if (!(error instanceof RequestError && error.status === 401)) setStartupError('Не удалось проверить сессию. Проверьте соединение.'); }
    finally { setStarting(false); }
  }
  useEffect(() => { void session(); const update = () => setOnline(navigator.onLine); window.addEventListener('online', update); window.addEventListener('offline', update); return () => { window.removeEventListener('online', update); window.removeEventListener('offline', update); }; }, []);
  async function refresh(value: Auth) {
    setLoading(true);
    try {
      const root = `/workspaces/${value.workspace.id}`;
      const [a, w, c, tags] = await Promise.all([listAll<Account>(`${root}/accounts?archived=include`), request<List<Workspace>>('/workspaces'), listAll<Category>(`${root}/categories?archived=include`), listAll<Tag>(`${root}/tags?archived=include`)]);
      const workspace = w.items.find(item => item.id === value.workspace.id);
      if (!workspace) throw new Error('Пространство недоступно.');
      if (activeSession.current !== value.session.id) return;
      setNeedsRefresh(false); setAccounts(a); setHistoryRevision(v => v + 1); setCategories(c); setTags(tags); setAuth(current => current && current.session.id === value.session.id ? { ...current, workspace } : current);
    } finally { setLoading(false); }
  }
  function showError(value: unknown) {
    if (value instanceof RequestError && value.status === 401) { activeSession.current = null; setAuth(null); setAccounts([]); setCategories([]); setTags([]); setError('Сессия завершена. Войдите снова.'); return; }
    setError(value instanceof Error ? value.message : 'Не удалось загрузить данные.');
  }
  useEffect(() => { if (auth) void refresh(auth).catch(showError); }, [auth?.session.id]);
  async function execute(command: Command) {
    if (!auth || busy) return;
    setBusy(true); setError(''); setMessage('');
    try {
      await request(command.path, { method: command.method ?? 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': command.generation, 'Idempotency-Key': command.key }, body: command.body });
      persist(null); setPending(null); setEditing(null); setConflict(null); setFormVersion(v => v + 1); setMessage('Сохранено.');
      try { await refresh(auth); } catch (error) { setError('Операция сохранена, но обновить список не удалось. Нажмите «Обновить».'); showError(error); }
    } catch (error) {
      if (error instanceof RequestError && ['action_result_expired', 'idempotency_conflict'].includes(error.code)) {
        setError('Исходный результат требует сверки с историей. Новый ключ не создается; неподтвержденная команда сохранена.');
      } else if (error instanceof RequestError && error.status >= 400 && error.status < 500 && ![408, 425, 429].includes(error.status)) {
        // Explicit rejection is final. Never regenerate a key automatically.
        persist(null); setPending(null);
        if (error.code === 'sync_generation_conflict') { setNeedsRefresh(true); setError('Данные восстановлены или изменены на сервере. Обновите список и проверьте данные перед новой операцией.'); }
        else if (error.status === 409) {
          setError(`Конфликт: ${error.message}. Обновите данные перед повторным вводом.`);
          if (error.code === 'version_conflict' && command.method === 'PUT' && command.path.includes('/transactions/')) {
            try {
              const current = await request<Transaction>(command.path);
              const fee = current.fee_transaction_id ? await request<Transaction>(`/workspaces/${command.workspace}/transactions/${current.fee_transaction_id}`) : null;
              if (activeSession.current === command.session) setConflict({ proposed: JSON.parse(command.body) as ProposedTransaction, current, fee });
            } catch { setError('Конфликт: правка отклонена. Не удалось загрузить данные для сравнения; введенная форма сохранена.'); }
          }
        }
        else showError(error);
      } else setError('Ответ не получен. Операция могла сохраниться. Повторите ту же команду кнопкой ниже; изменение не будет применено дважды.');
    } finally { setBusy(false); }
  }
  function create(path: string, body: object, method: 'POST' | 'PUT' = 'POST') {
    if (!auth || pending || busy || needsRefresh) return;
    const command: Command = { owner: auth.profile.id, session: auth.session.id, workspace: auth.workspace.id, generation: auth.workspace.sync_generation_id, key: crypto.randomUUID(), path: `/workspaces/${auth.workspace.id}/${path}`, method, body: JSON.stringify(body) };
    persist(command); setPending(command); void execute(command);
  }
  async function editTransaction(id: string) {
    if (!auth || busy || pending) return;
    setLoading(true); setError('');
    try {
      const value = await request<Transaction>(`/workspaces/${auth.workspace.id}/transactions/${id}`);
      const related = value.fee_transaction_id || value.parent_transaction_id;
      const dependency = related ? await request<Transaction>(`/workspaces/${auth.workspace.id}/transactions/${related}`) : null;
      if (activeSession.current === auth.session.id) { setEditing(value); setConflict(null); setEditingFee(value.fee_transaction_id ? dependency : null); setEditingParentVersion(value.parent_transaction_id ? dependency?.version ?? null : null); }
    } catch (error) { showError(error); } finally { setLoading(false); }
  }
  async function revokeSession(id: string, current: boolean) {
    if (!auth || busy || pending) throw new Error('Дождитесь завершения команды.');
    setBusy(true);
    try {
      await request(`/sessions/${id}`, { method: 'DELETE', headers: { 'X-CSRF-Token': auth.csrf_token } });
      if (current) { activeSession.current = null; setAuth(null); setAccounts([]); setCategories([]); setTags([]); setEditing(null); persist(null); }
    } finally { setBusy(false); }
  }
  async function logout() {
    if (!auth || pending) return;
    setBusy(true);
    try { await request('/auth/logout', { method: 'POST', headers: { 'X-CSRF-Token': auth.csrf_token } }); activeSession.current = null; setAuth(null); setAccounts([]); setCategories([]); setTags([]); persist(null); }
    catch (error) { showError(error); } finally { setBusy(false); }
  }
  return <main><header><div><span className="eyebrow">ЛИЧНЫЕ ФИНАНСЫ</span><h1>Accounting</h1></div>{auth && <div className="identity">{auth.profile.email}<button className="secondary" disabled={busy || !!pending} onClick={() => void logout()}>Выйти</button></div>}</header>
    {!online && <p role="status" className="notice">Нет соединения. Данные на экране могут быть устаревшими; новые операции недоступны.</p>}
    {starting ? <p role="status">Проверяем сессию…</p> : startupError ? <section><p role="alert">{startupError}</p><button onClick={() => void session()}>Повторить проверку сессии</button></section> : !auth ? <><AuthForm onAuth={authenticated} />{error && <p role="alert">{error}</p>}</> : <>
    <div className="workspace"><h2>{auth.workspace.name}</h2><button className="secondary" disabled={busy || loading || !online} onClick={() => { setError(''); void refresh(auth).catch(showError); }}>{loading ? 'Загрузка…' : 'Обновить'}</button></div>
    {message && <p role="status" className="notice success">{message}</p>}{error && <p role="alert" className="notice">{error}</p>}
    {pending && <section className="notice"><h2>Команда ожидает подтверждения</h2><p>Проверьте результат повтором той же команды. Ее данные и ключ сохранены в этой вкладке.</p><button disabled={busy || !online} onClick={() => void execute(pending)}>{busy ? 'Сохраняем…' : 'Повторить ту же команду'}</button></section>}
    <AccountManager key={`accounts-${formVersion}`} accounts={accounts} disabled={busy || loading || !!pending || needsRefresh || !online} loading={loading} submit={create} fail={setError} />
    <div className="forms"><AccountForm key={`account-${formVersion}`} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create('accounts', body)} fail={setError} /><TransactionForm key={`transaction-${formVersion}`} accounts={accounts.filter(a => !a.archived_at)} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create('transactions', body)} fail={setError} /></div>
    <TransferForm key={`transfer-${formVersion}`} accounts={accounts.filter(a => !a.archived_at)} categories={categories} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create('transactions', body)} fail={setError} />
    {conflict && <TransactionConflict value={conflict} accounts={accounts} categories={categories} tags={tags} disabled={busy || loading || !!pending || !online} reload={() => void editTransaction(conflict.current.id)} dismiss={() => setConflict(null)} />}
    {editing && ['expense', 'income'].includes(editing.kind) && <TransactionForm key={`edit-${editing.id}-${editing.version}`} initial={editing} parentVersion={editingParentVersion} accounts={accounts} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create(`transactions/${editing.id}`, body, 'PUT')} fail={setError} cancel={() => setEditing(null)} />}
    {editing?.kind === 'transfer' && <TransferForm key={`edit-transfer-${editing.id}-${editing.version}`} initial={editing} initialFee={editingFee} accounts={accounts} categories={categories} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create(`transactions/${editing.id}`, body, 'PUT')} fail={setError} cancel={() => setEditing(null)} />}
    <ClassificationManager key={`classification-${formVersion}`} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} submit={create} fail={setError} />
    <TransactionHistory key={`${auth.session.id}-${auth.workspace.id}`} workspace={auth.workspace.id} revision={historyRevision} accounts={accounts} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} edit={id => void editTransaction(id)} fail={showError} />
    <SessionManager key={auth.session.id} disabled={busy || loading || !!pending || !online} revoke={revokeSession} fail={showError} />
    </>}
  </main>;
}
