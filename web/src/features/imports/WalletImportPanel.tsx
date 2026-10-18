import { useEffect, useMemo, useRef, useState } from 'react';
import { RequestError, type Account, type Auth, type Category } from '../../api/client';
import { money } from '../../shared/money';
import { parseWallet, planWallet, validWalletPair, walletAccountKey, walletPairs, walletCorrections, type WalletFile, type WalletPair, type WalletPlan, type WalletRow } from './wallet';
import { finishWallet, loadWallet, runWallet, saveWallet, walletLock, type WalletProgress } from './walletRunner';
type Props = { auth: Auth; accounts: Account[]; categories: Category[]; blocked: boolean; changed: () => void; fail: (e: unknown) => void; working: (busy: boolean) => void };
export function WalletImportPanel({ auth, accounts, categories, blocked, changed, fail, working }: Props) {
  const [file, setFile] = useState<WalletFile | null>(null), [pairs, setPairs] = useState<WalletPair[]>([]);
  const [skip, setSkip] = useState(false), [error, setError] = useState(''), [ready, setReady] = useState(false), [busy, setBusy] = useState(false);
  const [plan, setPlan] = useState<WalletPlan | null>(null), [progress, setProgress] = useState<WalletProgress | null>(null), [preview, setPreview] = useState<WalletPlan | null>(null);
  const alive = useRef(true), stopped = useRef(false), running = useRef(false), choosing = useRef(0);
  useEffect(() => {
    alive.current = true;
    void loadWallet(auth).then(saved => { if (!alive.current) return; if (saved) { setPlan(saved.plan); setProgress(saved.progress); } setReady(true); }).catch(e => { if (alive.current) setError(String(e instanceof Error ? e.message : e)); });
    return () => { alive.current = false; stopped.current = true; };
  }, []);
  const groups = useMemo(() => {
    const values = new Map<string, WalletRow[]>();
    for (const row of file?.rows ?? []) { const key = walletAccountKey(row); if (!values.has(key)) values.set(key, []); values.get(key)!.push(row); }
    return values;
  }, [file]);
  const used = new Set(pairs.flatMap(p => [p.debit, p.credit]));
  const corrections = file ? walletCorrections(file.rows, pairs) : [];
  const corrective = new Set(corrections.map(r => r.number));
  const unresolved = file?.rows.filter(r => r.transfer && !used.has(r.number) && !corrective.has(r.number)) ?? [];
  async function selectFile(input: File | undefined) {
    if (!input) return;
    const sequence = ++choosing.current;
    setError(''); setPreview(null); setFile(null); setSkip(false);
    try {
      if (input.size > 10 * 1024 * 1024) throw new Error('Файл Wallet должен быть не больше 10 MiB.');
      const parsed = await parseWallet(new TextDecoder('utf-8', { fatal: true }).decode(await input.arrayBuffer()));
      if (!alive.current || sequence !== choosing.current) return;
      setFile(parsed); setPairs(walletPairs(parsed.rows));
    } catch (e) { if (alive.current && sequence === choosing.current) setError(e instanceof Error ? e.message : 'Не удалось прочитать Wallet CSV.'); }
  }
  async function prepare() {
    if (!file) return;
    setError(''); setBusy(true);
    try { const value = await planWallet(file, pairs, accounts, categories, auth.profile.id, auth.workspace.id, auth.workspace.sync_generation_id, skip); if (alive.current) setPreview(value); }
    catch (e) { if (alive.current) setError(e instanceof Error ? e.message : 'Не удалось подготовить импорт.'); }
    finally { if (alive.current) setBusy(false); }
  }
  async function run(value?: WalletPlan) {
    if (running.current || blocked) return;
    running.current = true; stopped.current = false; setBusy(true); working(true); setError('');
    try {
      await walletLock(auth, async () => {
        if (value) { await saveWallet(auth, value); if (!alive.current) return; setPlan(value); setProgress({ key: `${value.digest}:0`, digest: value.digest, cursor: 0 }); setPreview(null); }
        await runWallet(auth, () => stopped.current || !alive.current, next => { if (alive.current) setProgress(next); });
      });
    } catch (e) {
      if (alive.current) {
        setError(e instanceof RequestError ? `Импорт приостановлен (${e.code}). Уже подтвержденные записи сохранены; текущая команда остается для проверки и точного повтора.` : e instanceof Error ? e.message : 'Ответ не получен. Продолжение повторит ту же команду без дублирования.');
        // Reload after a durable plan write/local acknowledgement failure.
        try { const saved = await loadWallet(auth); if (saved && alive.current) { setPlan(saved.plan); setProgress(saved.progress); } } catch { /* Preserve the storage error above. */ }
        if (e instanceof RequestError && e.status === 401) fail(e);
      }
    } finally { running.current = false; working(false); if (alive.current) { setBusy(false); changed(); } }
  }
  async function finish() {
    setBusy(true); setError('');
    try { await walletLock(auth, () => finishWallet(auth)); setPlan(null); setProgress(null); setFile(null); setPreview(null); }
    catch (e) { setError(e instanceof Error ? e.message : 'Не удалось закрыть импорт.'); }
    finally { setBusy(false); }
  }
  const complete = !!plan && progress?.cursor === plan.commands.length;
  return <section aria-label="Импорт Wallet"><h2>Импорт из Wallet</h2><p>Выберите файл в формате wallet_records.csv. Нужные счета и категории будут созданы автоматически; существующие найдутся по имени и валюте.</p>
    {error && <p role="alert" className="notice">{error}</p>}
    {plan ? <div><h3>{complete ? 'Импорт Wallet завершен' : 'Сохраненный импорт Wallet'}</h3><p>Подтверждено шагов: {progress?.cursor ?? 0} из {plan.commands.length}. Операций в плане: {plan.transactions}. Корректировок: {plan.adjustments ?? 0}. Исключено строк: {plan.skipped}.</p><progress aria-label="Прогресс Wallet" max={plan.commands.length} value={progress?.cursor ?? 0} />
      {!complete && <><p>{plan.commands[progress?.cursor ?? 0]?.label}</p><p className="hint">При прерывании уже записанные операции сохраняются. После перезагрузки или повторного входа нажмите «Продолжить импорт Wallet». Сохраненные суммы и ключи не меняются.</p>{busy ? <button onClick={() => { stopped.current = true; }}>Приостановить импорт Wallet</button> : <button disabled={!ready || blocked} onClick={() => void run()}>Продолжить импорт Wallet</button>}</>}
      {complete && <button disabled={busy} onClick={() => void finish()}>Закрыть завершенный импорт Wallet</button>}
    </div> : <fieldset disabled={!ready || busy || blocked}>
      <label>Файл Wallet CSV<input type="file" accept=".csv,text/csv" onChange={e => void selectFile(e.target.files?.[0])} /></label>
      {file && <><div className="balance-summary"><h3>Проверка выгрузки</h3><p>Прочитано: {file.rows.length + file.errors.length} строк. Ошибок: {file.errors.length}. Счетов: {groups.size}. Доходов и расходов: {file.rows.filter(r => !r.transfer).length}. Строк переводов: {file.rows.filter(r => r.transfer).length}.</p></div>
        {!!file.errors.length && <details open><summary>Ошибки файла Wallet ({file.errors.length})</summary><ul>{file.errors.slice(0, 100).map((e, i) => <li key={i}>{e}</li>)}</ul></details>}
        <h3>Счета из файла</h3><p className="hint">Новые счета создаются с нулевым начальным остатком на дату первой записи в выгрузке. Выгрузка операций не содержит исходных балансов: после переноса сверьте остатки с Wallet. У существующего счета дата открытия должна быть не позже первой импортируемой операции.</p>
        <ul className="wallet-rows">{[...groups].map(([key, rows]) => {
          const first = rows[0];
          const existing = accounts.filter(a => a.name === first.account && a.currency === first.currency && !a.archived_at);
          return <li key={key}><strong>{first.account} · {first.currency}</strong><span>{rows.length} строк · {existing.length === 1 ? 'Существующий счет' : existing.length > 1 ? 'Обнаружены одинаковые названия счетов' : accounts.some(a => a.name === first.account && a.currency === first.currency) ? 'Счет в архиве — восстановите перед импортом' : 'Будет создан автоматически'}</span></li>;
        })}</ul>
        <details><summary>Предпросмотр операций Wallet</summary><ul className="wallet-rows">{file.rows.filter(r => !r.transfer).slice(0, 50).map(r => <li key={r.number}><strong>Строка {r.number} · {r.account}</strong><span>{r.date.slice(0, 10)} · {r.kind === 'expense' ? 'Расход' : 'Доход'} · {money(r.amount, r.currency)}</span><span>{r.category}{r.note ? ` · ${r.note}` : ''}</span></li>)}</ul><p className="hint">Первые 50 операций. Все строки проверяются перед подготовкой плана. Категории создаются или сопоставляются по точному имени; метки и способ оплаты сохраняются в примечании.</p></details>
        <h3>Переводы между счетами</h3><p>Подобрано пар: {pairs.length}. Без пары: {unresolved.length} строк. Корректировок: {corrections.length}. Автоподбор учитывает точные суммы и время с разницей до 5 минут. Каждая пара будет одной операцией перевода; суммы списания и зачисления сохранятся в своих валютах.</p>
        {!!pairs.length && <details><summary>Проверить пары переводов ({pairs.length})</summary><ul className="wallet-rows">{pairs.map(pair => { const d = file.rows.find(r => r.number === pair.debit)!, c = file.rows.find(r => r.number === pair.credit)!; return <li key={pair.debit}><span>Строки {d.number} → {c.number}: {d.account} → {c.account}</span><span>{money(d.amount, d.currency)} → {money(c.amount, c.currency)}</span><span>Списание: {d.date} · Зачисление: {c.date}</span><span>{({ timestamp: 'Две строки с одинаковым временем', amount: 'Единственное совпадение суммы и валюты', reference: 'Единственное совпадение справочной суммы Wallet', manual: 'Пара выбрана вручную' } as const)[pair.match ?? 'manual']}</span><button className="secondary" onClick={() => { setPairs(ps => ps.filter(p => p.debit !== pair.debit)); setPreview(null); setSkip(false); }}>Разъединить строки {d.number}/{c.number}</button></li>; })}</ul></details>}
        {!!corrections.length && <details><summary>Корректирующие проводки Wallet ({corrections.length})</summary><p className="hint">В выгрузке нет второй стороны этих движений. Они будут записаны корректировками остатка: зачисление увеличит остаток, списание уменьшит. Исходные даты и примечания сохранятся; в доходы и расходы эти записи не попадут.</p><ul className="wallet-rows">{corrections.map(r => <li key={r.number}><strong>Строка {r.number} · {r.account}</strong><span>{r.date} · {money(r.kind === 'expense' ? `-${r.amount}` : r.amount, r.currency)}</span><span>{r.kind === 'expense' ? 'Не указано направление перевода' : 'Не указан источник поступления'}</span></li>)}</ul></details>}
        {!!unresolved.length && <details open><summary>Сопоставить оставшиеся переводы ({unresolved.length})</summary><p className="hint">Для этих строк однозначной пары по времени и сумме нет. Выберите соответствующее зачисление. Для одной валюты суммы должны совпадать; комиссию мастер не предполагает.</p><ul className="wallet-rows">{unresolved.map(r => <li key={r.number}><strong>Строка {r.number} · {r.account}</strong><span>{r.date} · {r.kind === 'expense' ? 'Списание' : 'Зачисление'} · {money(r.amount, r.currency)}</span>{r.kind === 'expense' && <label>Зачисление для строки {r.number}<select value="" onChange={e => { if (e.target.value) { setPairs(ps => [...ps, { debit: r.number, credit: Number(e.target.value), match: 'manual' }]); setPreview(null); setSkip(false); } }}><option value="">Выберите строку зачисления</option>{unresolved.filter(c => validWalletPair(r, c)).map(c => <option key={c.number} value={c.number}>Строка {c.number} · {c.account} · {c.date.slice(0, 10)} · {money(c.amount, c.currency)}</option>)}</select></label>}</li>)}</ul><label className="check"><input type="checkbox" checked={skip} onChange={e => { setSkip(e.target.checked); setPreview(null); }} />Я проверил строки без пары и исключаю их из этого импорта ({unresolved.length})</label></details>}
        <p className="hint">Одинаковые покупки остаются отдельными записями. Повтор того же плана использует те же идентификаторы. При повторном импорте не меняйте пары переводов. Другая или отредактированная выгрузка требует проверки пересечения периодов.</p>
        <button className="primary-action" disabled={!!file.errors.length || (!!unresolved.length && !skip)} onClick={() => void prepare()}>Подготовить импорт Wallet</button>
        {preview && <aside className="notice"><h3>Подтвердите перенос</h3><p>Новых счетов: {preview.createdAccounts}. Новых категорий: {preview.createdCategories}. Всего операций: {preview.transactions}. Из них корректировок: {preview.adjustments ?? 0}. Исключено строк: {preview.skipped}.</p><p>После подтверждения записи начнут сохраняться. Импорт может занять несколько минут; оставьте вкладку открытой.</p><button onClick={() => void run(preview)}>Подтвердить импорт Wallet</button><button className="secondary" onClick={() => setPreview(null)}>Вернуться к проверке</button></aside>}
      </>}
    </fieldset>}
  </section>;
}
