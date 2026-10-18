import { useEffect, useState } from 'react';
import { request, type Account, type Category, type List, type Transaction } from '../../api/client';
import { money } from '../../shared/money';
import { Icon } from '../../shared/Icon';
type Report = { currencies: { currency: string; income_minor: string; expense_minor: string }[]; categories: { currency: string; category_id: string | null; expense_minor: string }[] };
type Props = { workspace: string; active: boolean; revision: number; accounts: Account[]; categories: Category[]; records: (query?: string) => void; openRecord: (id: string) => void };
const palette = ['#26a9de', '#ffad32', '#ff724c', '#8168cf', '#55b69b', '#db77ac'];
function periodRange(period: string) {
  const now = new Date(), end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1));
  const start = period === 'month' ? new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)) : new Date(end.getTime() - 30 * 86400000);
  return new URLSearchParams({ from: start.toISOString(), to: end.toISOString(), status: 'posted' }).toString();
}
export function HomeOverview({ workspace, active, revision, accounts, categories, records, openRecord }: Props) {
  const [period, setPeriod] = useState('30days'), [currency, setCurrency] = useState('');
  const [data, setData] = useState<{ report: Report; recent: Transaction[]; query: string } | null>(null);
  const [error, setError] = useState(false), [loading, setLoading] = useState(true), [retry, setRetry] = useState(0);
  useEffect(() => {
    if (!active) return;
    const controller = new AbortController(), query = periodRange(period);
    setLoading(true); setError(false); setData(null);
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(30000)]);
    void Promise.all([
      request<Report>(`/workspaces/${workspace}/reports?${query}`, { signal }),
      request<List<Transaction>>(`/workspaces/${workspace}/transactions?limit=5&status=posted`, { signal }),
    ]).then(([report, recent]) => {
      if (!controller.signal.aborted) setData({ report: { currencies: report.currencies ?? [], categories: report.categories ?? [] }, recent: recent.items.filter(t => t.kind !== 'opening'), query });
    }).catch(() => { if (!controller.signal.aborted) setError(true); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [workspace, active, revision, period, retry]);
  const currencies = [...new Set([...accounts.filter(a => !a.archived_at).map(a => a.currency), ...(data?.report.currencies.map(c => c.currency) ?? [])])].sort();
  const selected = currencies.includes(currency) ? currency : currencies[0] ?? 'EUR';
  const totals = data?.report.currencies.find(c => c.currency === selected);
  const expenses = data?.report.categories.filter(c => c.currency === selected && BigInt(c.expense_minor) !== 0n).sort((a, b) => {
    const aa = BigInt(a.expense_minor), bb = BigInt(b.expense_minor); return (aa < 0n ? -aa : aa) > (bb < 0n ? -bb : bb) ? -1 : 1;
  }) ?? [];
  const total = expenses.reduce((sum, row) => sum + (BigInt(row.expense_minor) < 0n ? -BigInt(row.expense_minor) : BigInt(row.expense_minor)), 0n);
  let offset = 0;
  const segments = expenses.map((row, index) => {
    const value = BigInt(row.expense_minor), share = total ? Number((value < 0n ? -value : value) * 100000n / total) / 1000 : 0;
    const segment = { ...row, share, offset, color: palette[index % palette.length] }; offset += share; return segment;
  });
  return <div className="home-overview" aria-busy={loading}>
    <section className="overview-card" aria-label="Структура расходов">
      <div className="overview-heading"><h2>Структура расходов</h2><Icon name="expense" /></div>
      <div className="overview-controls"><label className="sr-only" htmlFor="home-period">Период обзора</label><select id="home-period" value={period} onChange={e => setPeriod(e.target.value)}><option value="30days">Последние 30 дней</option><option value="month">Этот месяц</option></select>
        {currencies.length > 1 && <div className="currency-switch" role="group" aria-label="Валюта обзора">{currencies.map(c => <button key={c} aria-pressed={selected === c} onClick={() => setCurrency(c)}>{c}</button>)}</div>}
      </div>
      {loading ? <p role="status" className="overview-placeholder">Загружаем обзор…</p> : error ? <div className="overview-placeholder" role="alert"><p>Не удалось загрузить обзор.</p><button className="text-button" onClick={() => setRetry(v => v + 1)}>Повторить загрузку обзора</button></div> : <>
        <div className="flow-summary"><div><span>Расходы</span><strong>{money(totals?.expense_minor ?? '0', selected)}</strong></div><div><span>Доходы</span><strong className="income-value">{money(totals?.income_minor ?? '0', selected)}</strong></div></div>
        <div className="expense-chart"><svg viewBox="0 0 160 160" role="img" aria-label={`Расходы по категориям, ${selected}`}><circle cx="80" cy="80" r="61" fill="none" stroke="var(--chart-track)" strokeWidth="20" />{segments.map((s, i) => <circle key={i} cx="80" cy="80" r="61" fill="none" stroke={s.color} strokeWidth="20" pathLength="100" strokeDasharray={`${s.share} ${100 - s.share}`} strokeDashoffset={-s.offset} transform="rotate(-90 80 80)" />)}</svg><div className="chart-center"><span>{total ? 'Все расходы' : 'Нет расходов'}</span><strong>{money(totals?.expense_minor ?? '0', selected)}</strong></div></div>
        {segments.length ? <ul className="chart-legend">{segments.map((s, i) => <li key={i}><i style={{ background: s.color }} /><span>{categories.find(c => c.id === s.category_id)?.name ?? 'Без категории'}</span><strong>{money(s.expense_minor, selected)}</strong></li>)}</ul> : <p className="chart-empty">За выбранный период расходов нет</p>}
        {expenses.some(e => BigInt(e.expense_minor) < 0n) && <p className="hint">Возвраты вычтены из расходов. Доли диаграммы показаны по абсолютным суммам категорий.</p>}
        <button className="overview-footer" onClick={() => records(data?.query)}>Операции за период<Icon name="arrow" /></button>
      </>}
    </section>
    <section className="overview-card recent-card" aria-label="Последние записи"><div className="overview-heading"><h2>Последние записи</h2><Icon name="records" /></div>
      {loading ? <p className="overview-placeholder">Загружаем записи…</p> : error ? <p className="chart-empty">Записи временно недоступны</p> : data?.recent.length ? <ul className="recent-records">{data.recent.map(t => {
        const entry = t.entries.find(e => BigInt(e.amount_minor) < 0n) ?? t.entries[0];
        const target = t.kind === 'transfer' ? t.entries.find(e => BigInt(e.amount_minor) > 0n) : null;
        const category = categories.find(c => c.id === t.allocations[0]?.category_id)?.name;
        const label = t.kind === 'transfer' ? 'Перевод между счетами' : t.kind === 'adjustment' ? 'Корректировка остатка' : category ?? (t.kind === 'income' ? 'Доход' : t.kind === 'refund' ? 'Возврат' : 'Расход');
        return <li key={t.id}><button onClick={() => openRecord(t.id)}><span className={`record-icon ${t.kind}`}><Icon name={t.kind === 'transfer' ? 'transfer' : t.kind === 'income' || t.kind === 'refund' ? 'income' : 'expense'} /></span><span className="record-description"><strong>{t.payee || label}</strong><small>{accounts.find(a => a.id === entry?.account_id)?.name ?? label}</small></span><span className="record-values"><strong className={t.kind === 'transfer' ? 'transfer-value' : entry && BigInt(entry.amount_minor) > 0n ? 'income-value' : 'expense-value'}>{entry ? money(entry.amount_minor, entry.currency) : '—'}</strong>{target && <small>→ {money(target.amount_minor, target.currency)}</small>}<time dateTime={t.occurred_at}>{new Date(t.occurred_at).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })}</time></span></button></li>;
      })}</ul> : <p className="chart-empty">Здесь появятся ваши операции</p>}
      <button className="overview-footer" onClick={() => records()}>Все записи<Icon name="arrow" /></button>
    </section>
  </div>;
}
