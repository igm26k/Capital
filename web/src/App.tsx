import { useEffect, useRef, useState } from 'react';
import { request, listAll, RequestError, type Category, type Tag, type Auth, type Account, type Transaction, type List, type Workspace } from './api/client';
import { PrivacyPanel } from './features/auth/PrivacyPanel';
import { SessionManager } from './features/auth/SessionManager';
import { AuthForm } from './features/auth/AuthForm';
import { AccountManager } from './features/accounts/AccountManager';
import { TransferForm } from './features/transactions/TransferForm';
import { TransactionForm } from './features/transactions/TransactionForm';
import { ClassificationManager } from './features/classification/ClassificationManager';
import { ResourceConflict, type ResourceComparison } from './shared/ResourceConflict';
import { BulkConflict, type BulkComparison } from './features/transactions/BulkConflict';
import { DeleteTransaction, type Deletion } from './features/transactions/DeleteTransaction';
import { AdjustmentForm } from './features/transactions/AdjustmentForm';
import { RefundForm } from './features/transactions/RefundForm';
import { TransactionConflict, type ProposedTransaction, type TransactionComparison } from './features/transactions/TransactionConflict';
import { clearCSVCommandsForOwner } from './features/imports/commandStorage';
import { SourcesPanel } from './features/sources/SourcesPanel';
import { WalletImportPanel } from './features/imports/WalletImportPanel';
import { CSVPanel } from './features/imports/CSVPanel';
import { AnalyticsPanel } from './features/analytics/AnalyticsPanel';
import { TransactionHistory } from './features/transactions/TransactionHistory';
import { HomeOverview } from './features/home/HomeOverview';
import { Icon } from './shared/Icon';
// A single unfinished command survives reload in this tab. No credentials are persisted.
type Command = { owner: string; session: string; workspace: string; generation: string; key: string; path: string; body: string; method?: 'POST' | 'PUT' | 'DELETE' };
const storageKey = 'accounting.pending.v1';
function restore(auth: Auth): Command | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null') as Command | null;
    if (value?.owner === auth.profile.id && value.session === auth.session.id && value.workspace === auth.workspace.id && typeof value.body === 'string' && typeof value.key === 'string' && typeof value.generation === 'string' && (!value.method || ['POST', 'PUT', 'DELETE'].includes(value.method)) && new RegExp(`^/workspaces/${value.workspace}/(?:(?:accounts|transactions|categories|tags)(?:/[0-9a-f-]{36})?|accounts/[0-9a-f-]{36}/adjustments|transactions/classification)$`).test(value.path)) return value;
    sessionStorage.removeItem(storageKey);
  } catch { /* Storage unavailable: retries remain available while this page is open. */ }
  return null;
}
function persist(command: Command | null) { try { command ? sessionStorage.setItem(storageKey, JSON.stringify(command)) : sessionStorage.removeItem(storageKey); } catch { /* In-memory fallback. */ } }
type Section = 'accounts' | 'operations' | 'more';
function currentSection(): Section {
  const value = window.location.hash.slice(1);
  return value === 'operations' || value === 'more' ? value : 'accounts';
}
export function App() {
  const [historyNavigation, setHistoryNavigation] = useState(0);
  const [operationOpen, setOperationOpen] = useState(false);
  const [section, setSection] = useState<Section>(currentSection);
  useEffect(() => {
    const update = () => { setSection(currentSection()); window.scrollTo(0, 0); };
    window.addEventListener('hashchange', update);
    return () => window.removeEventListener('hashchange', update);
  }, []);
  function navigate(next: Section) {
    window.location.hash = next;
    setSection(next);
    window.scrollTo(0, 0);
  }
  const activeSession = useRef<string | null>(null);
 const [analyticsOpen,setAnalyticsOpen]=useState(false);
 const [csvOpen,setCSVOpen]=useState(false);
 const [sourcesOpen,setSourcesOpen]=useState(false);
 const [reportHistoryQuery,setReportHistoryQuery]=useState<string>();
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [auth, setAuth] = useState<Auth | null>(null), [starting, setStarting] = useState(true), [startupError, setStartupError] = useState('');
  const [accounts, setAccounts] = useState<Account[]>([]), [historyRevision, setHistoryRevision] = useState(0);
  const [resourceConflict, setResourceConflict] = useState<ResourceComparison | null>(null), [resourceRevision, setResourceRevision] = useState(0);
  const [bulkConflict, setBulkConflict] = useState<BulkComparison | null>(null);
  const [deleting, setDeleting] = useState<Deletion | null>(null);
  const [refundParent, setRefundParent] = useState<Transaction | null>(null), [refundTransferVersion, setRefundTransferVersion] = useState<string | null>(null);
  const [conflict, setConflict] = useState<TransactionComparison | null>(null);
  const [editingFee, setEditingFee] = useState<Transaction | null>(null), [editingParentVersion, setEditingParentVersion] = useState<string | null>(null);
  const [editing, setEditing] = useState<Transaction | null>(null);
  const [categories, setCategories] = useState<Category[]>([]), [tags, setTags] = useState<Tag[]>([]);
  const [pending, setPending] = useState<Command | null>(null), [busy, setBusy] = useState(false), [loading, setLoading] = useState(false), [message, setMessage] = useState(''), [error, setError] = useState(''), [online, setOnline] = useState(navigator.onLine), [formVersion, setFormVersion] = useState(0);
  function authenticated(value: Auth) { setReportHistoryQuery(undefined); activeSession.current = value.session.id; setAccounts([]); setCategories([]); setTags([]); setEditing(null); setRefundParent(null); setDeleting(null); setBulkConflict(null); setResourceConflict(null); setConflict(null); setAuth(value); setPending(restore(value)); setError(''); setMessage(''); }
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
      setNeedsRefresh(false); setAccounts(a); setHistoryRevision(v => v + 1); setBulkConflict(null); setCategories(c); setTags(tags); setAuth(current => current && current.session.id === value.session.id ? { ...current, workspace } : current);
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
      persist(null); setPending(null); setEditing(null); setRefundParent(null); setDeleting(null); setBulkConflict(null); setResourceConflict(null); setConflict(null); setFormVersion(v => v + 1); setMessage('Сохранено.');
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
          const resource = command.path.match(/\/(accounts|categories|tags)\/[0-9a-f-]{36}$/);
          if (error.code === 'version_conflict' && command.method === 'PUT' && resource) {
            try {
              const current = await request<ResourceComparison['current']>(command.path);
              if (activeSession.current === command.session) setResourceConflict({ kind: resource[1] as ResourceComparison['kind'], proposed: JSON.parse(command.body) as ResourceComparison['proposed'], current });
            } catch { setError('Конфликт: изменение отклонено. Не удалось загрузить запись для сравнения. Введенные поля сохранены.'); }
          }
          if (error.code === 'version_conflict' && command.path.endsWith('/transactions/classification')) {
            try {
              const proposed = JSON.parse(command.body) as BulkComparison['proposed'];
              const current = await Promise.all(proposed.items.map(item => request<Transaction>(`/workspaces/${command.workspace}/transactions/${item.id}`).catch(error => { if (error instanceof RequestError && error.status === 404) return null; throw error; })));
              if (activeSession.current === command.session) setBulkConflict({ proposed, current });
            } catch { setError('Конфликт: весь пакет отклонен. Не удалось загрузить назначения для сравнения. Обновите историю перед новой командой.'); }
          }
          if (error.code === 'version_conflict' && command.method === 'PUT'  && command.path.includes('/transactions/')) {
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
  function create(path: string, body: object, method: 'POST' | 'PUT' | 'DELETE' = 'POST') {
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
      const transfer = value.kind === 'refund' && dependency?.parent_transaction_id ? await request<Transaction>(`/workspaces/${auth.workspace.id}/transactions/${dependency.parent_transaction_id}`) : null;
      if (activeSession.current === auth.session.id) { setDeleting(null); setRefundParent(value.kind === 'refund' ? dependency : null); setRefundTransferVersion(transfer?.version ?? null); setEditing(value); setConflict(null); setEditingFee(value.fee_transaction_id ? dependency : null); setEditingParentVersion(value.parent_transaction_id ? dependency?.version ?? null : null); }
    } catch (error) { showError(error); } finally { setLoading(false); }
  }
  async function reloadResources() {
    if (!auth) return;
    try { await refresh(auth); if (activeSession.current === auth.session.id) { setResourceRevision(v => v + 1); setResourceConflict(null); setError(''); } }
    catch (error) { showError(error); }
  }
  async function deleteTransaction(id: string) {
    if (!auth || busy || pending) return;
    setLoading(true); setError('');
    try {
      const path = `/workspaces/${auth.workspace.id}/transactions/`;
      const transaction = await request<Transaction>(path + id);
      const related: Transaction[] = [];
      if (transaction.fee_transaction_id) related.push(await request<Transaction>(path + transaction.fee_transaction_id));
      if (transaction.parent_transaction_id) {
        const parent = await request<Transaction>(path + transaction.parent_transaction_id); related.push(parent);
        if (transaction.kind === 'refund' && parent.parent_transaction_id) related.push(await request<Transaction>(path + parent.parent_transaction_id));
      }
      if (activeSession.current === auth.session.id) { setEditing(null); setRefundParent(null); setConflict(null); setDeleting({ transaction, related }); }
    } catch (error) { showError(error); } finally { setLoading(false); }
  }
  async function newRefund(id: string) {
    if (!auth || busy || pending) return;
    setLoading(true); setError('');
    try {
      const parent = await request<Transaction>(`/workspaces/${auth.workspace.id}/transactions/${id}`);
      const transfer = parent.parent_transaction_id ? await request<Transaction>(`/workspaces/${auth.workspace.id}/transactions/${parent.parent_transaction_id}`) : null;
      if (activeSession.current === auth.session.id) { setDeleting(null); setEditing(null); setConflict(null); setRefundParent(parent); setRefundTransferVersion(transfer?.version ?? null); }
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
  function showRecords(query?: string) { setReportHistoryQuery(query ? `${query}&limit=25` : 'limit=25'); setHistoryNavigation(v => v + 1); navigate('operations'); }
  return <main className={auth ? "app-shell wallet-shell" : "login-shell"}><header className="app-header">{auth ? <><button className="icon-button" aria-label="Открыть меню" onClick={() => navigate('more')}><Icon name="menu" /></button><div className="header-title"><h1>{section === 'accounts' ? 'Главная' : section === 'operations' ? 'Операции' : 'Еще'}</h1><span>{auth.workspace.name}</span></div><button className="icon-button" aria-label="Обновить" disabled={busy || loading || !online} onClick={() => { setError(''); void refresh(auth).catch(showError); }}><Icon name="refresh" /></button></> : <div><span className="eyebrow">ЛИЧНЫЕ ФИНАНСЫ</span><h1>Capital</h1></div>}</header>
    {!online && <p role="status" className="notice">Нет соединения. Данные на экране могут быть устаревшими; новые операции недоступны.</p>}
    {starting ? <p role="status">Проверяем сессию…</p> : startupError ? <section><p role="alert">{startupError}</p><button onClick={() => void session()}>Повторить проверку сессии</button></section> : !auth ? <><AuthForm onAuth={authenticated} />{error && <p role="alert">{error}</p>}</> : <>
    {message && <p role="status" className="notice success">{message}</p>}{error && <p role="alert" className="notice">{error}</p>}
    {pending && <section className="notice"><h2>Команда ожидает подтверждения</h2><p>Проверьте результат повтором той же команды. Ее данные и ключ сохранены в этой вкладке.</p><button disabled={busy || !online} onClick={() => void execute(pending)}>{busy ? 'Сохраняем…' : 'Повторить ту же команду'}</button></section>}
    {resourceConflict && <ResourceConflict value={resourceConflict} categories={categories} disabled={busy || loading || !!pending || !online} reload={() => void reloadResources()} />}
    <div hidden={section !== "accounts"} className="screen" aria-label="Счета">
    <AccountManager records={() => showRecords()} reports={() => { setAnalyticsOpen(true); navigate("more"); }} history={id => showRecords(`account_id=${encodeURIComponent(id)}`)} key={`accounts-${formVersion}-${resourceRevision}`} accounts={accounts} disabled={busy || loading || !!pending || needsRefresh || !online} loading={loading} submit={create} fail={setError} >
      <HomeOverview workspace={auth.workspace.id} active={section === 'accounts'} revision={historyRevision} accounts={accounts} categories={categories} records={showRecords} openRecord={id => { navigate('operations'); void editTransaction(id); }} />
      <button className="add-record-fab" aria-label="Добавить операцию" disabled={busy || loading || !!pending || needsRefresh || !online} onClick={() => { setOperationOpen(true); navigate('operations'); }}><Icon name="plus" /></button>
    </AccountManager>
    </div>
    <div hidden={section !== "operations"} className="screen" aria-label="Операции">
    <div className="screen-heading"><h2>Операции</h2><p className="hint">Расходы, доходы и переводы</p></div>
    <details className="editor-disclosure" open={operationOpen} onToggle={e => setOperationOpen(e.currentTarget.open)}><summary>Добавить операцию</summary><TransactionForm key={`transaction-${formVersion}`} accounts={accounts.filter(a => !a.archived_at)} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create('transactions', body)} fail={setError} />
    </details>
    <details className="editor-disclosure"><summary>Перевод между счетами</summary>
    <TransferForm key={`transfer-${formVersion}`} accounts={accounts.filter(a => !a.archived_at)} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create('transactions', body)} fail={setError} />
    </details>

    {bulkConflict && <BulkConflict value={bulkConflict} categories={categories} tags={tags} disabled={busy || loading || !!pending || !online} refresh={() => { setError(''); void refresh(auth).catch(showError); }} />}
    {deleting && <DeleteTransaction value={deleting} accounts={accounts} disabled={busy || loading || !!pending || needsRefresh || !online} confirm={() => create(`transactions/${deleting.transaction.id}`, { expected_version: deleting.transaction.version, related_versions: deleting.related.map(t => ({ id: t.id, version: t.version })) }, 'DELETE')} cancel={() => setDeleting(null)} />}
    {conflict && <TransactionConflict value={conflict} accounts={accounts} categories={categories} tags={tags} disabled={busy || loading || !!pending || !online} reload={() => void editTransaction(conflict.current.id)} dismiss={() => setConflict(null)} />}
    {editing && ['expense', 'income'].includes(editing.kind) && <TransactionForm key={`edit-${editing.id}-${editing.version}`} initial={editing} parentVersion={editingParentVersion} accounts={accounts} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create(`transactions/${editing.id}`, body, 'PUT')} fail={setError} cancel={() => setEditing(null)} />}
    {editing?.kind === 'transfer' && <TransferForm key={`edit-transfer-${editing.id}-${editing.version}`} initial={editing} initialFee={editingFee} accounts={accounts} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create(`transactions/${editing.id}`, body, 'PUT')} fail={setError} cancel={() => setEditing(null)} />}
    {refundParent && (!editing || editing.kind === 'refund') && <RefundForm key={`refund-${refundParent.id}-${refundParent.version}-${editing?.id ?? 'new'}`} parent={refundParent} transferVersion={refundTransferVersion} initial={editing ?? undefined} accounts={accounts} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create(editing ? `transactions/${editing.id}` : 'transactions', body, editing ? 'PUT' : 'POST')} fail={setError} cancel={() => { setRefundParent(null); setEditing(null); }} />}
    <details className="editor-disclosure"><summary>Корректировка остатка</summary><AdjustmentForm key={`adjustment-${formVersion}`} accounts={accounts.filter(a => !a.archived_at)} disabled={busy || loading || !!pending || needsRefresh || !online} submit={create} fail={setError} />
    </details>
    {editing?.kind === 'adjustment' && <AdjustmentForm key={`edit-adjustment-${editing.id}-${editing.version}`} accounts={accounts} initial={editing} disabled={busy || loading || !!pending || needsRefresh || !online} submit={create} fail={setError} cancel={() => setEditing(null)} />}
    <TransactionHistory filterRevision={historyNavigation} filterQuery={reportHistoryQuery} key={`${auth.session.id}-${auth.workspace.id}`} workspace={auth.workspace.id} revision={historyRevision} accounts={accounts} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} edit={id => void editTransaction(id)} refund={id => void newRefund(id)} remove={id => void deleteTransaction(id)} classify={body => create('transactions/classification', body)} fail={showError} />
    </div>
    <div hidden={section !== "more"} className="screen" aria-label="Еще">
    <div className="screen-heading"><h2>Еще</h2><p className="hint">Отчеты, справочники и настройки</p></div>
    <details className="editor-disclosure"><summary>Категории и теги</summary>
    <ClassificationManager key={`classification-${formVersion}-${resourceRevision}`} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} submit={create} fail={setError} />
    </details>
    <details open={analyticsOpen} onToggle={e => setAnalyticsOpen(e.currentTarget.open)}><summary>Бюджеты, отчеты и правила</summary>{analyticsOpen && <AnalyticsPanel key={`${auth.session.id}:${auth.workspace.sync_generation_id}`} auth={auth} accounts={accounts} categories={categories} tags={tags} revision={historyRevision} classify={body => create('transactions/classification',body)} fail={showError} drill={query => {setReportHistoryQuery(`${query}&limit=25`);navigate('operations');}} />}</details>
    <details><summary>Импорт из Wallet</summary><WalletImportPanel key={`${auth.profile.id}:${auth.workspace.id}:${auth.session.id}`} auth={auth} accounts={accounts} categories={categories} blocked={busy || loading || !!pending || needsRefresh || !online} changed={() => { void refresh(auth).catch(showError); }} fail={showError} working={setBusy} /></details>
    <details onToggle={e=>setCSVOpen(e.currentTarget.open)}><summary>Экспорт CSV</summary>{csvOpen && <CSVPanel key={`${auth.session.id}:${auth.workspace.sync_generation_id}`} auth={auth} accounts={accounts} changed={()=>{void refresh(auth).catch(showError);}} fail={showError}/>}</details>
    <details onToggle={e=>setSourcesOpen(e.currentTarget.open)}><summary>Банковские предложения</summary>{sourcesOpen&&<SourcesPanel key={`${auth.session.id}:${auth.workspace.sync_generation_id}`} auth={auth} accounts={accounts} changed={()=>{void refresh(auth).catch(showError);}} fail={showError}/>}</details>
    <p className="hint">Ручной учет и CSV. Банковские подключения пока недоступны.</p>
    <section className="profile-card"><h2>Профиль</h2><p>{auth.profile.email}</p><button className="secondary" disabled={busy || !!pending} onClick={() => void logout()}>Выйти</button></section>
    <PrivacyPanel auth={auth} fail={showError} deleted={async()=>{
      let localCleared=true;try{await clearCSVCommandsForOwner(auth.profile.id);}catch{localCleared=false;}
      try {for(const key of Object.keys(sessionStorage)){if([`accounting.planning.pending.v1:${auth.profile.id}:`,`accounting.csv.pending.v1:${auth.profile.id}:`,`accounting.csv.last:${auth.profile.id}:`].some(prefix=>key.startsWith(prefix)))sessionStorage.removeItem(key);}}catch{/* In-memory state is cleared below. */}
      if(activeSession.current!==auth.session.id)return;
      persist(null);setPending(null);activeSession.current=null;setAuth(null);setAccounts([]);setCategories([]);setTags([]);setReportHistoryQuery(undefined);setError(localCleared?'Профиль и личные данные удалены.':'Профиль удалён. Очистите данные этого сайта в настройках браузера: локальное хранилище недоступно.');}}/>
    <SessionManager key={auth.session.id} disabled={busy || loading || !!pending || !online} revoke={revokeSession} fail={showError} />
    </div>
    <nav className="bottom-nav" aria-label="Основная навигация">
      {([['accounts', 'Счета', '◫'], ['operations', 'Операции', '⇄'], ['more', 'Еще', '⋯']] as const).map(([id, label, symbol]) =>
        <a key={id} href={`#${id}`} aria-current={section === id ? 'page' : undefined}><span className="nav-symbol" aria-hidden="true">{symbol}</span><span>{label}</span></a>)}
    </nav>
    </>}
  </main>;
}
