import { useEffect, useRef, useState, type FormEvent } from 'react';
import { request, RequestError, type Account, type Category, type List, type Tag, type Transaction } from '../../api/client';
import { categoryLabel } from '../../shared/categories';
import { money } from '../../shared/money';
const kinds: Record<string, string> = { opening: 'Начальный остаток', expense: 'Расход', income: 'Доход', transfer: 'Перевод', refund: 'Возврат', adjustment: 'Корректировка' };
type Props = { workspace: string; revision: number; accounts: Account[]; categories: Category[]; tags: Tag[]; disabled: boolean; edit: (id: string) => void; refund: (id: string) => void; remove: (id: string) => void; fail: (error: unknown) => void };
export function TransactionHistory({ workspace, revision, accounts, categories, tags, disabled, edit, refund, remove, fail }: Props) {
  const [items, setItems] = useState<Transaction[]>([]), [cursor, setCursor] = useState<string | null>(null), [query, setQuery] = useState('limit=25'), [loading, setLoading] = useState(false), [error, setError] = useState(''), [retry, setRetry] = useState(0);
  const sequence = useRef(0);
  const root = `/workspaces/${workspace}/transactions`;
  useEffect(() => {
    const requestID = ++sequence.current, controller = new AbortController();
    setItems([]); setCursor(null); setLoading(true); setError('');
    void request<List<Transaction>>(`${root}?${query}`, { signal: controller.signal }).then(page => {
      if (requestID === sequence.current) { setItems(page.items); setCursor(page.next_cursor); }
    }).catch(error => {
      if (controller.signal.aborted || requestID !== sequence.current) return;
      if (error instanceof RequestError && error.status === 401) fail(error);
      else setError(error instanceof Error ? error.message : 'Не удалось загрузить историю.');
    }).finally(() => { if (!controller.signal.aborted && requestID === sequence.current) setLoading(false); });
    return () => { controller.abort(); sequence.current++; };
  }, [root, query, revision, retry]);
  async function more() {
    if (!cursor || loading || disabled) return;
    const requestID = sequence.current;
    setLoading(true); setError('');
    try {
      const page = await request<List<Transaction>>(`${root}?${query}&cursor=${encodeURIComponent(cursor)}`);
      if (requestID !== sequence.current) return;
      setItems(current => { const ids = new Set(current.map(t => t.id)); return [...current, ...page.items.filter(t => !ids.has(t.id))]; }); setCursor(page.next_cursor);
    } catch (error) {
      if (requestID !== sequence.current) return;
      if (error instanceof RequestError && error.status === 401) fail(error);
      else setError('Не удалось загрузить следующую страницу. Повторите загрузку или обновите историю.');
    } finally { if (requestID === sequence.current) setLoading(false); }
  }
  function filter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget), params = new URLSearchParams();
    for (const key of ['account_id', 'category_id', 'tag_id', 'kind', 'status', 'q', 'limit']) { const value = String(data.get(key) ?? '').trim(); if (value) params.set(key, value); }
    try {
      const from = String(data.get('from') ?? ''), to = String(data.get('to') ?? '');
      if (from) params.set('from', new Date(`${from}T00:00:00`).toISOString());
      if (to) { const end = new Date(`${to}T00:00:00`); end.setDate(end.getDate() + 1); params.set('to', end.toISOString()); }
      if (params.has('from') && params.has('to') && params.get('from')! >= params.get('to')!) throw new Error('Начало периода должно быть не позже его конца.');
      setQuery(params.toString()); setRetry(v => v + 1);
    } catch (error) { setError(error instanceof Error ? error.message : 'Проверьте период.'); }
  }
  return <section><h2>История операций</h2><form onSubmit={filter}><fieldset disabled={disabled || loading}>
    <div className="forms"><label>Счет истории<select aria-label="Счет истории" name="account_id"><option value="">Все счета</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}{a.archived_at && ' · В архиве'}</option>)}</select></label>
    <label>Категория истории<select aria-label="Категория истории" name="category_id"><option value="">Все категории</option>{categories.map(c => <option key={c.id} value={c.id}>{categoryLabel(c, categories)}</option>)}</select></label>
    <label>Тег истории<select aria-label="Тег истории" name="tag_id"><option value="">Все теги</option>{tags.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label>
    <label>Тип в истории<select aria-label="Тип в истории" name="kind"><option value="">Все типы</option>{Object.entries(kinds).map(([value, name]) => <option key={value} value={value}>{name}</option>)}</select></label>
    <label>Статус в истории<select aria-label="Статус в истории" name="status"><option value="">Все статусы</option><option value="posted">Подтвержденные</option><option value="pending">Предварительные</option></select></label>
    <label>С даты<input type="date" name="from" /></label><label>По дату включительно<input type="date" name="to" /></label>
    <label>Поиск в истории<input name="q" maxLength={200} placeholder="Получатель или примечание" /></label><label>Операций на странице<select aria-label="Операций на странице" name="limit" defaultValue="25">{[10, 25, 50, 100].map(n => <option key={n}>{n}</option>)}</select></label></div>
    <button>Применить фильтры</button><button className="secondary" type="button" onClick={event => { event.currentTarget.form?.reset(); setQuery('limit=25'); setRetry(v => v + 1); }}>Сбросить фильтры</button>
  </fieldset></form><p className="hint">Категория фильтруется без потомков. Даты относятся к часовому поясу этого браузера.</p>
    {error && <p role="alert">{error}</p>}<button className="secondary" disabled={disabled || loading} onClick={() => setRetry(v => v + 1)}>Обновить историю</button>
    {loading && <p role="status">Загружаем историю…</p>}
    {items.length ? <ul className="history">{items.map(transaction => <li key={transaction.id} data-testid={`transaction-${transaction.id}`}><div><strong>{transaction.kind === 'expense' && transaction.parent_transaction_id ? 'Комиссия перевода' : kinds[transaction.kind] ?? transaction.kind}</strong>{['expense', 'income', 'transfer', 'refund', 'adjustment'].includes(transaction.kind) && <button className="secondary" disabled={disabled || loading} onClick={() => edit(transaction.id)} aria-label={`Изменить операцию ${transaction.payee || transaction.note || transaction.id}`}>Изменить</button>}{transaction.kind !== 'opening' && <button className="secondary" disabled={disabled || loading} aria-label={`Удалить операцию ${transaction.payee || transaction.note || transaction.id}`} onClick={() => remove(transaction.id)}>Удалить</button>}{transaction.kind === 'expense' && transaction.status === 'posted' && transaction.allocations.some(p => BigInt(p.remaining_refundable_minor) > 0n) && <button className="secondary" disabled={disabled || loading} aria-label={`Возврат по операции ${transaction.payee || transaction.note || transaction.id}`} onClick={() => refund(transaction.id)}>Возврат</button>}<p className="hint">{new Date(transaction.occurred_at).toLocaleString('ru-RU')}{transaction.status === 'pending' && ' · Предварительная'}</p>{transaction.payee && <p>{transaction.payee}</p>}{transaction.reason && <p>Причина: {transaction.reason}</p>}{transaction.note && <p>{transaction.note}</p>}{transaction.allocations.map(part => <p className="hint" key={part.id}>{part.category_id ? categories.find(c => c.id === part.category_id) ? categoryLabel(categories.find(c => c.id === part.category_id)!, categories) : 'Категория недоступна' : 'Без категории'} · {money(part.amount_minor, transaction.entries[0]?.currency ?? 'EUR')}</p>)}{transaction.tag_ids.length > 0 && <p className="hint">Теги: {transaction.tag_ids.map(id => tags.find(t => t.id === id)?.name ?? 'Недоступный тег').join(', ')}</p>}</div><div>{transaction.entries.map((entry, index) => <p key={index}>{accounts.find(a => a.id === entry.account_id)?.name ?? 'Счет недоступен'} · {money(entry.amount_minor, entry.currency)}</p>)}</div></li>)}</ul> : !loading && <p>Операций по выбранным условиям нет.</p>}
    {cursor && <button disabled={disabled || loading} onClick={() => void more()}>Загрузить еще операции</button>}<p className="hint">Показано операций: {items.length}{!cursor && !loading && ' · Все страницы загружены'}</p>
  </section>;
}
