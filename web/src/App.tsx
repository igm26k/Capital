import { useEffect, useRef, useState } from 'react';
import { request, listAll, RequestError, type Category, type Tag, type Auth, type Account, type Transaction, type List, type Workspace } from './api/client';
import { AuthForm } from './features/auth/AuthForm';
import { AccountForm } from './features/accounts/AccountForm';
import { TransferForm } from './features/transactions/TransferForm';
import { TransactionForm } from './features/transactions/TransactionForm';
import { ClassificationManager } from './features/classification/ClassificationManager';
import { categoryLabel } from './shared/categories';
import { money } from './shared/money';
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
  const [accounts, setAccounts] = useState<Account[]>([]), [transactions, setTransactions] = useState<Transaction[]>([]);
  const [categories, setCategories] = useState<Category[]>([]), [tags, setTags] = useState<Tag[]>([]);
  const [pending, setPending] = useState<Command | null>(null), [busy, setBusy] = useState(false), [loading, setLoading] = useState(false), [message, setMessage] = useState(''), [error, setError] = useState(''), [online, setOnline] = useState(navigator.onLine), [formVersion, setFormVersion] = useState(0);
  function authenticated(value: Auth) { activeSession.current = value.session.id; setAccounts([]); setTransactions([]); setCategories([]); setTags([]); setAuth(value); setPending(restore(value)); setError(''); setMessage(''); }
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
      const [a, t, w, c, tags] = await Promise.all([listAll<Account>(`${root}/accounts`), request<List<Transaction>>(`${root}/transactions?limit=100`), request<List<Workspace>>('/workspaces'), listAll<Category>(`${root}/categories?archived=include`), listAll<Tag>(`${root}/tags?archived=include`)]);
      const workspace = w.items.find(item => item.id === value.workspace.id);
      if (!workspace) throw new Error('Пространство недоступно.');
      if (activeSession.current !== value.session.id) return;
      setNeedsRefresh(false); setAccounts(a); setTransactions(t.items); setCategories(c); setTags(tags); setAuth(current => current && current.session.id === value.session.id ? { ...current, workspace } : current);
    } finally { setLoading(false); }
  }
  function showError(value: unknown) {
    if (value instanceof RequestError && value.status === 401) { activeSession.current = null; setAuth(null); setAccounts([]); setTransactions([]); setCategories([]); setTags([]); setError('Сессия завершена. Войдите снова.'); return; }
    setError(value instanceof Error ? value.message : 'Не удалось загрузить данные.');
  }
  useEffect(() => { if (auth) void refresh(auth).catch(showError); }, [auth?.session.id]);
  async function execute(command: Command) {
    if (!auth || busy) return;
    setBusy(true); setError(''); setMessage('');
    try {
      await request(command.path, { method: command.method ?? 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': command.generation, 'Idempotency-Key': command.key }, body: command.body });
      persist(null); setPending(null); setFormVersion(v => v + 1); setMessage('Сохранено.');
      try { await refresh(auth); } catch (error) { setError('Операция сохранена, но обновить список не удалось. Нажмите «Обновить».'); showError(error); }
    } catch (error) {
      if (error instanceof RequestError && ['action_result_expired', 'idempotency_conflict'].includes(error.code)) {
        setError('Исходный результат требует сверки с историей. Новый ключ не создается; неподтвержденная команда сохранена.');
      } else if (error instanceof RequestError && error.status >= 400 && error.status < 500 && ![408, 425, 429].includes(error.status)) {
        // Explicit rejection is final. Never regenerate a key automatically.
        persist(null); setPending(null);
        if (error.code === 'sync_generation_conflict') { setNeedsRefresh(true); setError('Данные восстановлены или изменены на сервере. Обновите список и проверьте данные перед новой операцией.'); }
        else if (error.status === 409) setError(`Конфликт: ${error.message}. Обновите данные перед повторным вводом.`);
        else showError(error);
      } else setError('Ответ не получен. Операция могла сохраниться. Повторите ту же команду кнопкой ниже; изменение не будет применено дважды.');
    } finally { setBusy(false); }
  }
  function create(path: string, body: object, method: 'POST' | 'PUT' = 'POST') {
    if (!auth || pending || busy || needsRefresh) return;
    const command: Command = { owner: auth.profile.id, session: auth.session.id, workspace: auth.workspace.id, generation: auth.workspace.sync_generation_id, key: crypto.randomUUID(), path: `/workspaces/${auth.workspace.id}/${path}`, method, body: JSON.stringify(body) };
    persist(command); setPending(command); void execute(command);
  }
  async function logout() {
    if (!auth || pending) return;
    setBusy(true);
    try { await request('/auth/logout', { method: 'POST', headers: { 'X-CSRF-Token': auth.csrf_token } }); activeSession.current = null; setAuth(null); setAccounts([]); setTransactions([]); setCategories([]); setTags([]); persist(null); }
    catch (error) { showError(error); } finally { setBusy(false); }
  }
  return <main><header><div><span className="eyebrow">ЛИЧНЫЕ ФИНАНСЫ</span><h1>Accounting</h1></div>{auth && <div className="identity">{auth.profile.email}<button className="secondary" disabled={busy || !!pending} onClick={() => void logout()}>Выйти</button></div>}</header>
    {!online && <p role="status" className="notice">Нет соединения. Данные на экране могут быть устаревшими; новые операции недоступны.</p>}
    {starting ? <p role="status">Проверяем сессию…</p> : startupError ? <section><p role="alert">{startupError}</p><button onClick={() => void session()}>Повторить проверку сессии</button></section> : !auth ? <><AuthForm onAuth={authenticated} />{error && <p role="alert">{error}</p>}</> : <>
    <div className="workspace"><h2>{auth.workspace.name}</h2><button className="secondary" disabled={busy || loading || !online} onClick={() => { setError(''); void refresh(auth).catch(showError); }}>{loading ? 'Загрузка…' : 'Обновить'}</button></div>
    {message && <p role="status" className="notice success">{message}</p>}{error && <p role="alert" className="notice">{error}</p>}
    {pending && <section className="notice"><h2>Команда ожидает подтверждения</h2><p>Проверьте результат повтором той же команды. Ее данные и ключ сохранены в этой вкладке.</p><button disabled={busy || !online} onClick={() => void execute(pending)}>{busy ? 'Сохраняем…' : 'Повторить ту же команду'}</button></section>}
    <section><h2>Счета</h2>{accounts.length ? <ul className="accounts">{accounts.map(account => <li key={account.id}><span>{account.name}</span><strong data-testid={`balance-${account.id}`}>{money(account.posted_balance_minor, account.currency)}</strong></li>)}</ul> : <p>{loading ? 'Загружаем счета…' : 'Создайте первый счет с начальным остатком.'}</p>}</section>
    <div className="forms"><AccountForm key={`account-${formVersion}`} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create('accounts', body)} fail={setError} /><TransactionForm key={`transaction-${formVersion}`} accounts={accounts} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create('transactions', body)} fail={setError} /></div>
    <TransferForm key={`transfer-${formVersion}`} accounts={accounts} disabled={busy || loading || !!pending || needsRefresh || !online} submit={body => create('transactions', body)} fail={setError} />
    <ClassificationManager key={`classification-${formVersion}`} categories={categories} tags={tags} disabled={busy || loading || !!pending || needsRefresh || !online} submit={create} fail={setError} />
    <section><h2>Последние операции</h2><p className="hint">Показаны до 100 последних операций. Полная история и синхронизация будут добавлены на следующем этапе.</p>{transactions.length ? <ul className="history">{transactions.map(transaction => <li key={transaction.id}><div><strong>{({ opening: 'Начальный остаток', expense: 'Расход', income: 'Доход', transfer: 'Перевод', refund: 'Возврат', adjustment: 'Корректировка' } as Record<string, string>)[transaction.kind] ?? transaction.kind}</strong>{transaction.payee && <p>{transaction.payee}</p>}{transaction.note && <p>{transaction.note}</p>}{transaction.allocations.map(part => <p className="hint" key={part.id}>{part.category_id ? categories.find(item => item.id === part.category_id) ? categoryLabel(categories.find(item => item.id === part.category_id)!, categories) : 'Категория недоступна' : 'Без категории'} · {money(part.amount_minor, transaction.entries[0]?.currency ?? 'EUR')}</p>)}{transaction.tag_ids.length > 0 && <p className="hint">Теги: {transaction.tag_ids.map(id => tags.find(item => item.id === id)?.name ?? 'Недоступный тег').join(', ')}</p>}</div><div>{transaction.entries.map((entry, index) => <p key={index}>{money(entry.amount_minor, entry.currency)}</p>)}</div></li>)}</ul> : <p>Операций пока нет.</p>}</section>
    </>}
  </main>;
}
