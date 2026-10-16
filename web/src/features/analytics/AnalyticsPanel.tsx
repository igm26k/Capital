import { useEffect, useRef, useState, type FormEvent } from 'react';
import { request, RequestError, type Account, type Auth, type Category, type Tag } from '../../api/client';
import { money, minor, decimal, currencies } from '../../shared/money';
import { categoryLabel } from '../../shared/categories';

type Budget = { id: string; version: string; name: string; category_id: string; currency: string; month: string; timezone: string; limit_minor: string };
type BudgetView = { budget: Budget; spent_minor: string; posted_spent_minor: string; pending_spent_minor: string; remaining_minor: string; overspent_minor: string; warning: string };
type Rule = { id: string; version: string; name: string; category_id: string; priority: number; enabled: boolean; transaction_kind: string; payee_contains: string; note_contains: string };
type Totals = { currency: string; income_minor: string; expense_minor: string; pending_income_minor: string; pending_expense_minor: string };
type Report = { currencies: Totals[]; categories: { currency: string; category_id: string | null; expense_minor: string }[]; balances: { currency: string; opening_minor: string; closing_minor: string; projected_closing_minor: string }[]; daily_balances: { date: string; currency: string; closing_minor: string; projected_closing_minor: string }[] };
type Batch = { items: { id: string; expected_version: string }[]; category_id: string; tag_ids: string[] };
type Command = { owner: string; session: string; workspace: string; generation: string; key: string; path: string; method: string; body: string };
const storagePrefix = 'accounting.planning.pending.v1';
const month = new Date().toISOString().slice(0, 7);
function restore(auth: Auth): Command | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(`${storagePrefix}:${auth.profile.id}:${auth.workspace.id}:${auth.session.id}`) ?? 'null') as Command | null;
    if (value?.owner === auth.profile.id && value.session === auth.session.id && value.workspace === auth.workspace.id && typeof value.body === 'string' && typeof value.key === 'string' && typeof value.generation === 'string' && ['POST', 'PUT', 'DELETE'].includes(value.method) && new RegExp(`^/workspaces/${auth.workspace.id}/(?:budgets|rules)(?:/[0-9a-f-]{36})?$`).test(value.path)) return value;
  } catch { /* In-memory retries remain available. */ }
  return null;
}
export function AnalyticsPanel({ auth, accounts, categories, tags, revision, classify, fail, drill }: { auth: Auth; accounts: Account[]; categories: Category[]; tags: Tag[]; revision: number; classify: (body: object) => void; fail: (e: unknown) => void; drill: (query: string) => void }) {
  const root = `/workspaces/${auth.workspace.id}`;
  const storage = `${storagePrefix}:${auth.profile.id}:${auth.workspace.id}:${auth.session.id}`;
  const [budgets, setBudgets] = useState<BudgetView[]>([]), [rules, setRules] = useState<Rule[]>([]), [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [pending, setPending] = useState<Command | null>(() => restore(auth)), [terminal, setTerminal] = useState(false);
  const [editingBudget, setEditingBudget] = useState<Budget | null>(null), [editingRule, setEditingRule] = useState<Rule | null>(null), [formRevision, setFormRevision] = useState(0), [currency, setCurrency] = useState('EUR');
  const [batches, setBatches] = useState<Batch[] | null>(null), [confirm, setConfirm] = useState<Batch | null>(null);
  const query = useRef(`from=${month}-01T00:00:00Z&to=${new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 1)).toISOString()}`);
  const requestID = useRef(0);
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; requestID.current++; }; }, []);
  function show(e: unknown) { if (!active.current) return; setError(e instanceof Error ? e.message : 'Не удалось загрузить аналитику.'); if (e instanceof RequestError && e.status === 401) fail(e); }
  async function load() {
    const id = ++requestID.current;
    try {
      const b = await request<{ items: BudgetView[] }>(`${root}/budgets`);
      const r = await request<{ items: Rule[] }>(`${root}/rules`);
      const a = await request<Report>(`${root}/reports?${query.current}`);
      if (id !== requestID.current || !active.current) return;
      setBudgets(b.items); setRules(r.items); setReport(a); setBatches(null); setConfirm(null);
    } catch (e) { if (id === requestID.current) show(e); }
  }
  useEffect(() => { void load(); }, [revision]);
  function persist(value: Command | null) { setPending(value); try { if (value) sessionStorage.setItem(storage, JSON.stringify(value)); else sessionStorage.removeItem(storage); } catch { /* In-memory fallback. */ } }
  async function execute(command: Command) {
    if (busy) return; setBusy(true); setError(''); setTerminal(false);
    try {
      await request(command.path, { method: command.method, headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': command.generation, 'Idempotency-Key': command.key }, body: command.body });
      if (!active.current) return;
      persist(null); setEditingBudget(null); setEditingRule(null); setFormRevision(v => v + 1); await load();
    } catch (e) {
      if (e instanceof RequestError && [400, 404, 409, 422].includes(e.status) && !['idempotency_conflict', 'action_result_expired', 'sync_generation_conflict'].includes(e.code)) setTerminal(true);
      show(e);
    } finally { if (active.current) setBusy(false); }
  }
  function save(resource: string, body: object, method = 'POST') {
    if (pending || busy) return;
    const command = { owner: auth.profile.id, session: auth.session.id, workspace: auth.workspace.id, generation: auth.workspace.sync_generation_id, key: crypto.randomUUID(), path: `${root}/${resource}`, method, body: JSON.stringify(body) };
    persist(command); void execute(command);
  }
  function budgetSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    try {
      const body = { name: String(data.get('name')), category_id: String(data.get('category')), currency, month: String(data.get('month')), timezone: String(data.get('timezone')), limit_minor: minor(String(data.get('limit')), currency) };
      save(editingBudget ? `budgets/${editingBudget.id}` : 'budgets', editingBudget ? { ...body, expected_version: editingBudget.version } : { ...body, id: crypto.randomUUID() }, editingBudget ? 'PUT' : 'POST');
    } catch (e) { show(e); }
  }
  function ruleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    const body = { name: String(data.get('name')), category_id: String(data.get('category')), priority: Number(data.get('priority')), enabled: data.has('enabled'), transaction_kind: String(data.get('kind')), payee_contains: String(data.get('payee')), note_contains: String(data.get('note')) };
    save(editingRule ? `rules/${editingRule.id}` : 'rules', editingRule ? { ...body, expected_version: editingRule.version } : { ...body, id: crypto.randomUUID() }, editingRule ? 'PUT' : 'POST');
  }
  function filters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget), params = new URLSearchParams();
    try {
      params.set('from', new Date(`${data.get('from')}T00:00:00Z`).toISOString());
      params.set('to', new Date(`${data.get('to')}T00:00:00Z`).toISOString());
      for (const key of ['account_id', 'category_id', 'tag_id', 'status']) { const value = String(data.get(key)); if (value) params.set(key, value); }
      query.current = params.toString(); setError(''); void load();
    } catch (e) { show(e); }
  }
  const label = (id: string | null) => id ? categories.find(c => c.id === id) ? categoryLabel(categories.find(c => c.id === id)!, categories) : 'Категория недоступна' : 'Без категории';
  const options = categories.filter(c => !c.archived_at).map(c => <option key={c.id} value={c.id}>{categoryLabel(c, categories)}</option>);
  return <section aria-label="Бюджеты и отчеты">
    <h2>Бюджеты и отчеты</h2>
    {error && <p role="alert">{error}</p>}
    {pending && <aside><p>Сохранён запрос настроек. Его повтор использует исходные данные.</p><button disabled={busy} onClick={() => void execute(pending)}>Повторить запрос настроек</button>{terminal && <><p>Запрос отклонён. Обновите данные и сравните их с вашим изменением.</p><p>{(() => { try {const v=JSON.parse(pending.body);return `${v.name??'Удаление настройки'} ${v.month??''} ${v.limit_minor&&v.currency?money(v.limit_minor,v.currency):''}`;}catch{return 'Сохранённое изменение';} })()}</p><button disabled={busy} onClick={() => { persist(null); setTerminal(false); void load(); }}>Закрыть отклонённое изменение настроек</button></>}</aside>}
    <button disabled={busy} onClick={() => void load()}>Обновить аналитику</button>
    <h3>Отчет за период</h3>
    <p>Предварительные операции включены. Даты и динамика — UTC; конец периода не включается. Остатки учитывают выбранный счет и все его движения; фильтры категории и тега относятся к доходам и расходам.</p>
    <form onSubmit={filters}><fieldset disabled={busy}><label>Начало отчета<input name="from" type="date" required defaultValue={`${month}-01`} /></label><label>Конец отчета<input name="to" type="date" required defaultValue={new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 1)).toISOString().slice(0, 10)} /></label>
      <label>Счет отчета<select name="account_id"><option value="">Все счета</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
      <label>Категория отчета<select name="category_id"><option value="">Все категории</option>{categories.map(c => <option key={c.id} value={c.id}>{label(c.id)}</option>)}</select></label>
      <label>Тег отчета<select name="tag_id"><option value="">Все теги</option>{tags.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label>
      <label>Статус отчета<select name="status"><option value="all">Все операции</option><option value="posted">Подтвержденные</option><option value="pending">Предварительные</option></select></label><button>Построить отчет</button>
    </fieldset></form>
    {report && <><div className="forms">{report.currencies.map(t => <article key={t.currency}><h4>{t.currency}</h4><p>Доходы: {money(t.income_minor, t.currency)}</p><p>Расходы за вычетом возвратов: {money(t.expense_minor, t.currency)}</p><p>Предварительные: доход {money(t.pending_income_minor, t.currency)}, расход {money(t.pending_expense_minor, t.currency)}</p></article>)}</div>{!report.currencies.length && <p>Доходов и расходов за период нет.</p>}
      <button onClick={() => {const params=new URLSearchParams(query.current);if(params.get('status')==='all')params.delete('status');drill(params.toString());}}>Показать исходные операции отчета</button>
      <h4>Расходы по категориям</h4><p>Доли показаны по абсолютной величине. Возвраты могут дать отрицательные чистые расходы.</p><ul>{report.categories.map(c => <li key={`${c.currency}-${c.category_id}`}>{label(c.category_id)}: {money(c.expense_minor, c.currency)} <progress aria-label={`Доля расходов ${label(c.category_id)} ${c.currency}`} max={100} value={(() => {const values=report.categories.filter(v=>v.currency===c.currency).map(v=>BigInt(v.expense_minor));const total=values.reduce((sum,v)=>sum+(v<0n?-v:v),0n);const amount=BigInt(c.expense_minor);return total?Number((amount<0n?-amount:amount)*100n/total):0;})()} /></li>)}</ul>
      <h4>Остатки</h4>{report.balances.map(b => <p key={b.currency}>{b.currency}: на начало {money(b.opening_minor, b.currency)}, подтвержденный {money(b.closing_minor, b.currency)}, с предварительными {money(b.projected_closing_minor, b.currency)}</p>)}
      <details><summary>Динамика остатков</summary><table><thead><tr><th>Дата UTC</th><th>Подтвержденный</th><th>С предварительными</th></tr></thead><tbody>{report.daily_balances.map(b => <tr key={`${b.date}-${b.currency}`}><td>{b.date}</td><td>{money(b.closing_minor, b.currency)}</td><td>{money(b.projected_closing_minor, b.currency)}</td></tr>)}</tbody></table></details></>}
    <h3>Месячные бюджеты</h3><p>Лимит включает дочерние категории и предварительные расходы. Предупреждения: 80% и 100%.</p>
    {!budgets.length && <p>Бюджетов пока нет.</p>}<ul>{budgets.map(v => <li key={v.budget.id}><strong>{v.budget.name}</strong> · {v.budget.month} · {v.budget.timezone}<p>{label(v.budget.category_id)} · потрачено {money(v.spent_minor, v.budget.currency)} из {money(v.budget.limit_minor, v.budget.currency)}; осталось {money(v.remaining_minor, v.budget.currency)}</p><p>Предварительные: {money(v.pending_spent_minor, v.budget.currency)} · {v.warning === 'exceeded' ? 'Лимит достигнут или превышен' : v.warning === 'approaching' ? 'Близко к лимиту' : 'В пределах лимита'}</p><button disabled={busy || !!pending} onClick={() => { setEditingBudget(v.budget); setCurrency(v.budget.currency); }}>Изменить бюджет {v.budget.name}</button><button disabled={busy || !!pending} onClick={() => { if (window.confirm(`Удалить бюджет «${v.budget.name}»?`)) save(`budgets/${v.budget.id}`, { expected_version: v.budget.version }, 'DELETE'); }}>Удалить бюджет {v.budget.name}</button></li>)}</ul>
    <form key={`budget-${editingBudget?.id}-${editingBudget?.version}-${formRevision}`} onSubmit={budgetSubmit}><fieldset disabled={busy || !!pending}><legend>{editingBudget ? 'Изменение бюджета' : 'Новый бюджет'}</legend><label>Название бюджета<input name="name" required maxLength={100} defaultValue={editingBudget?.name} /></label><label>Категория бюджета<select name="category" required defaultValue={editingBudget?.category_id}>{options}</select></label><label>Валюта бюджета<select value={currency} onChange={e => setCurrency(e.target.value)}>{currencies.map(c => <option key={c}>{c}</option>)}</select></label><label>Месяц бюджета<input name="month" type="month" required defaultValue={editingBudget?.month ?? month} /></label><label>Часовой пояс бюджета<input name="timezone" required defaultValue={editingBudget?.timezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone} /></label><label>Лимит бюджета<input key={currency} name="limit" required inputMode="decimal" defaultValue={editingBudget && editingBudget.currency === currency ? decimal(editingBudget.limit_minor, currency) : ''} /></label><button>Сохранить бюджет</button>{editingBudget && <button type="button" onClick={() => setEditingBudget(null)}>Отменить изменение бюджета</button>}</fieldset></form>
    <h3>Правила категоризации</h3><p>Первое совпадение по приоритету. Уже выбранные категории сохраняются. Применение к существующим операциям требует подтверждения.</p>
    <ul>{rules.sort((a, b) => a.priority - b.priority || a.id.localeCompare(b.id)).map(r => <li key={r.id}>{r.name} · {r.priority} · {r.enabled ? 'Включено' : 'Выключено'} → {label(r.category_id)}<button disabled={busy || !!pending} onClick={() => setEditingRule(r)}>Изменить правило {r.name}</button><button disabled={busy || !!pending} onClick={() => { if (window.confirm(`Удалить правило «${r.name}»?`)) save(`rules/${r.id}`, { expected_version: r.version }, 'DELETE'); }}>Удалить правило {r.name}</button></li>)}</ul>
    <form key={`rule-${editingRule?.id}-${editingRule?.version}-${formRevision}`} onSubmit={ruleSubmit}><fieldset disabled={busy || !!pending}><legend>{editingRule ? 'Изменение правила' : 'Новое правило'}</legend><label>Название правила<input name="name" required maxLength={100} defaultValue={editingRule?.name} /></label><label>Категория правила<select name="category" required defaultValue={editingRule?.category_id}>{options}</select></label><label>Приоритет правила<input name="priority" type="number" min={0} max={1000000} required defaultValue={editingRule?.priority ?? 100} /></label><label>Тип правила<select name="kind" defaultValue={editingRule?.transaction_kind ?? 'expense'}><option value="expense">Расход</option><option value="income">Доход</option></select></label><label>Получатель содержит<input name="payee" maxLength={200} defaultValue={editingRule?.payee_contains} /></label><label>Примечание содержит<input name="note" maxLength={200} defaultValue={editingRule?.note_contains} /></label><label><input type="checkbox" name="enabled" defaultChecked={editingRule?.enabled ?? true} />Правило включено</label><button>Сохранить правило</button>{editingRule && <button type="button" onClick={() => setEditingRule(null)}>Отменить изменение правила</button>}</fieldset></form>
    <button disabled={busy || !!pending} onClick={() => { const dates = new URLSearchParams(query.current); void request<{ batches: Batch[]; has_more: boolean }>(`${root}/rules/preview?from=${encodeURIComponent(dates.get('from')!)}&to=${encodeURIComponent(dates.get('to')!)}`).then(v => { setBatches(v.batches); setError(v.has_more ? 'Показаны первые 100 совпадений. После применения обновите предпросмотр.' : ''); }).catch(show); }}>Предпросмотр правил за период</button>
    {batches && !batches.length && <p>Совпадений для неклассифицированных операций нет.</p>}{batches?.map((b, i) => <p key={i}>Операций: {b.items.length} → {label(b.category_id)}<button onClick={() => setConfirm(b)}>Проверить применение пакета {i + 1}</button></p>)}
    {confirm && <aside><p>Заменить категорию у {confirm.items.length} операций на «{label(confirm.category_id)}»? Теги сохранятся. При изменении версии весь пакет будет отклонен.</p><button onClick={() => { classify(confirm); setConfirm(null); setBatches(null); }}>Подтвердить применение правил</button><button onClick={() => setConfirm(null)}>Отмена применения правил</button></aside>}
  </section>;
}
