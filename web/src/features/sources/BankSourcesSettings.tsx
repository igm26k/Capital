import { useEffect, useRef, useState } from 'react';
import { request, RequestError, type Account, type Auth } from '../../api/client';
import { money } from '../../shared/money';
import { readCSVCommand, compareAuxiliaryCommand, AuxiliaryCommandConflict } from '../imports/commandStorage';
import { bankRulesKey, readBankRules, saveBankRules, type BankRules } from './bankMessageStorage';
import { bankMessageIntake, parseBankMessage, sourcePackage, validateBankRule, type BankRule, type ParsedBankMessage, type SourceIntake } from './bankMessages';
import { SourcesPanel } from './SourcesPanel';

type IntakeCommand = { owner: string; workspace: string; session: string; generation: string; key: string; body: string; bank: string; rejected: boolean };
type Receipt = SourceIntake & { workspace_id: string; state: string; version: string };
const uncertain = new Set(['action_result_expired', 'idempotency_conflict', 'sync_generation_conflict']);
const errorText = (error: unknown) => error instanceof AuxiliaryCommandConflict ? error.message : error instanceof RequestError ? ({
  source_event_conflict: 'Это сообщение уже принято с другими параметрами. Проверьте банковские предложения; новая запись не создана.',
  source_event_limit: 'Достигнут лимит банковских предложений.',
  validation_error: 'Проверьте счет, валюту и параметры сообщения.',
  unauthenticated: 'Сессия завершена. Войдите снова; сохраненный запрос останется доступен для сверки.',
  sync_generation_conflict: 'Данные восстановлены или изменились. Сверьте банковские предложения перед продолжением.',
} as Record<string, string>)[error.code] ?? 'Сервер не принял сообщение. Проверьте банковские предложения и сохраненный запрос.' : error instanceof Error ? error.message : 'Не удалось выполнить действие.';
const channelTitle = (channel: string) => channel === 'sms' ? 'SMS' : 'Push';
function validCommand(value: IntakeCommand | null, auth: Auth): value is IntakeCommand {
  if (!value || value.owner !== auth.profile.id || value.workspace !== auth.workspace.id || typeof value.session !== 'string' || typeof value.generation !== 'string' || typeof value.key !== 'string' || typeof value.body !== 'string' || typeof value.bank !== 'string' || typeof value.rejected !== 'boolean') return false;
  try { const body = JSON.parse(value.body) as SourceIntake; return /^web\.(sms|push)\.s[0-9a-f]{64}$/.test(body.package_name) && /^[0-9a-f]{64}$/.test(body.external_id) && /^[1-9][0-9]*$/.test(body.amount_minor) && ['income', 'expense'].includes(body.kind) && typeof body.account_id === 'string' && typeof body.id === 'string'; } catch { return false; }
}

export function BankSourcesSettings({ auth, accounts, blocked, changed, fail }: { auth: Auth; accounts: Account[]; blocked: boolean; changed: () => void; fail: (error: unknown) => void }) {
  const rulesKey = bankRulesKey(auth.profile.id, auth.workspace.id), pendingKey = `accounting.bank-intake.pending.v1:${auth.profile.id}:${auth.workspace.id}`;
  const root = `/workspaces/${auth.workspace.id}/source-events`;
  const alive = useRef(true), running = useRef(false), feedback = useRef<HTMLParagraphElement>(null);
  const [settings, setSettings] = useState<BankRules | null>(null), [ready, setReady] = useState(false);
  const [pending, setPending] = useState<IntakeCommand | null>(null), [busy, setBusy] = useState(false);
  const [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [editor, setEditor] = useState<BankRule | null>(null), [removing, setRemoving] = useState<BankRule | null>(null);
  const [selected, setSelected] = useState(''), [sender, setSender] = useState(''), [channel, setChannel] = useState<'sms' | 'push'>('sms');
  const [text, setText] = useState(''), [observed, setObserved] = useState('');
  const [preview, setPreview] = useState<ParsedBankMessage | null>(null);
  const [reviewOpen, setReviewOpen] = useState(false), [revision, setRevision] = useState(0), [sourceNames, setSourceNames] = useState<Record<string, string>>({});
  const rules = settings?.rules ?? [], activeAccounts = accounts.filter(a => !a.archived_at);
  const selectedRule = rules.find(r => r.id === selected);
  useEffect(() => { if (error) feedback.current?.scrollIntoView({ block: 'nearest' }); }, [error]);
  function show(e: unknown) { if (alive.current) setError(errorText(e)); }
  async function reload() {
    setReady(false); setError('');
    try {
      const [stored, command] = await Promise.all([readBankRules(rulesKey), readCSVCommand<IntakeCommand>(pendingKey)]);
      if (command && !validCommand(command, auth)) throw new Error('Не удалось прочитать сохраненный запрос сообщения. Исходные данные сохранены.');
      if (alive.current) { setSettings(stored); setPending(command); setReady(true); }
    } catch (e) { show(e); }
  }
  useEffect(() => { alive.current = true; void reload(); return () => { alive.current = false; }; }, [rulesKey, pendingKey]);
  useEffect(() => {
    let cancelled = false;
    void Promise.all(rules.map(async rule => [await sourcePackage(rule.channel, rule.sender), `${rule.bank} · ${channelTitle(rule.channel)}`] as const)).then(values => { if (!cancelled) setSourceNames(Object.fromEntries(values)); }).catch(show);
    setPreview(null);
    return () => { cancelled = true; };
  }, [settings]);
  function selectRule(id: string) {
    setSelected(id); setPreview(null); setNotice(''); setError('');
    const rule = rules.find(r => r.id === id); if (rule) { setSender(rule.sender); setChannel(rule.channel); }
  }
  function newRule() {
    setError(''); setRemoving(null);
    setEditor({ id: crypto.randomUUID(), bank: '', channel: 'sms', sender: '', accountId: activeAccounts[0]?.id ?? '', currency: activeAccounts[0]?.currency ?? 'EUR', kind: 'expense', template: '', decimalSeparator: ',', cardLastFour: '', enabled: true });
  }
  async function updateRules(values: BankRule[]) {
    if (running.current || !ready) return;
    running.current = true; setBusy(true); setError('');
    try {
      const saved = await saveBankRules(rulesKey, settings?.key ?? null, values);
      if (alive.current) { setSettings(saved); setEditor(null); setRemoving(null); setNotice('Правила банков сохранены в этом браузере.'); }
    } catch (e) {
      if (e instanceof AuxiliaryCommandConflict) { try { const latest = await readBankRules(rulesKey); if (alive.current) setSettings(latest); } catch (readError) { show(readError); } }
      show(e);
    } finally { running.current = false; if (alive.current) setBusy(false); }
  }
  function recognize() {
    setError(''); setNotice(''); setPreview(null);
    try {
      if (!selectedRule) throw new Error('Сначала выберите или добавьте правило банка.');
      const account = activeAccounts.find(a => a.id === selectedRule.accountId);
      if (!account || account.currency !== selectedRule.currency) throw new Error('Счет правила недоступен или его валюта изменилась. Измените правило банка.');
      if (!observed || !Number.isFinite(new Date(observed).getTime())) throw new Error('Укажите дату и время исходного сообщения.');
      setPreview(parseBankMessage(selectedRule, { channel, sender, text, observedAt: new Date(observed).toISOString() }));
    } catch (e) { show(e); }
  }
  async function persistCommand(value: IntakeCommand | null, expected: string | null) {
    try { await compareAuxiliaryCommand(pendingKey, expected, value); }
    catch (e) {
      if (e instanceof AuxiliaryCommandConflict && alive.current) {
        const latest = await readCSVCommand<IntakeCommand>(pendingKey);
        if (latest === null || validCommand(latest, auth)) setPending(latest); else setReady(false);
      }
      throw e;
    }
    if (alive.current) setPending(value);
  }
  async function execute(command: IntakeCommand) {
    try {
      const body = JSON.parse(command.body) as SourceIntake;
      const receipt = await request<Receipt>(root, { method: 'POST', body: command.body, headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': auth.csrf_token, 'Idempotency-Key': command.key, 'X-Sync-Generation': command.generation } });
      if (receipt.workspace_id !== auth.workspace.id || receipt.external_id !== body.external_id || receipt.package_name !== body.package_name || receipt.account_id !== body.account_id || receipt.kind !== body.kind || receipt.amount_minor !== body.amount_minor || receipt.currency !== body.currency) throw new Error('Не удалось сверить ответ сервера. Повторите сохраненный запрос.');
      if (!alive.current) return;
      await persistCommand(null, command.key);
      if (!alive.current) return;
      setPreview(null); setText(''); setNotice(receipt.state === 'dismissed' ? 'Это сообщение уже исключено. Повторный ввод не создает новое предложение.' : receipt.state === 'applied' ? 'Это сообщение уже связано с операцией. Повторный ввод не меняет остатки.' : receipt.state === 'queued' ? 'Это сообщение уже принято и ожидает обработки. Проверьте предложение ниже.' : 'Сообщение принято. Проверьте предложение ниже: операция и остаток пока не изменены.'); setReviewOpen(true); setRevision(v => v + 1);
    } catch (e) {
      if (e instanceof RequestError && [400, 404, 409, 422].includes(e.status) && !uncertain.has(e.code) && alive.current) {
        try { await persistCommand({ ...command, rejected: true }, command.key); } catch (storageError) { show(storageError); return; }
      }
      show(e instanceof RequestError || e instanceof AuxiliaryCommandConflict ? e : new Error('Ответ не получен или не удалось сохранить результат. Повторите сохраненный запрос: его ключ останется прежним.'));
      if (e instanceof RequestError && e.status === 401) fail(e);
    }
  }
  async function send() {
    if (running.current || busy || pending || !ready || blocked || !navigator.onLine || !selectedRule || !preview) return;
    running.current = true; setBusy(true); setError(''); setNotice('');
    try {
      const body = await bankMessageIntake(selectedRule, { channel, sender, text, observedAt: new Date(observed).toISOString() });
      const value: IntakeCommand = { owner: auth.profile.id, workspace: auth.workspace.id, session: auth.session.id, generation: auth.workspace.sync_generation_id, key: crypto.randomUUID(), body: JSON.stringify(body), bank: selectedRule.bank, rejected: false };
      await persistCommand(value, null); if (alive.current) await execute(value);
    } catch (e) { show(e); }
    finally { running.current = false; if (alive.current) setBusy(false); }
  }
  const stale = !!pending && (pending.session !== auth.session.id || pending.generation !== auth.workspace.sync_generation_id);
  async function retry() {
    if (!pending || pending.rejected || stale || running.current || blocked || !navigator.onLine) return;
    running.current = true; setBusy(true); setError('');
    try { await execute(pending); } finally { running.current = false; if (alive.current) setBusy(false); }
  }
  async function closePending() {
    if (!pending || busy || !ready) return;
    setBusy(true); setError('');
    try { await persistCommand(null, pending.key); setNotice('Запрос закрыт. Уже принятые банковские предложения сохраняются.'); }
    catch (e) { show(e); } finally { if (alive.current) setBusy(false); }
  }
  return <section className="bank-sources" aria-label="Банки и уведомления">
    <div className="screen-heading"><h2>Банки и уведомления</h2><button className="secondary" disabled={!ready || busy || rules.length >= 50} onClick={newRule}>Добавить банк</button></div>
    <p className="hint">В веб-версии вставьте текст SMS или push вручную. Браузер не читает сообщения и уведомления других приложений. Правила банков сохраняются только в этом браузере для текущего профиля и пространства.</p>
    {error && <p ref={feedback} className="notice" role="alert">{error}</p>}{notice && <p className="notice" role="status">{notice}</p>}
    {!ready && <button className="secondary" disabled={busy} onClick={() => void reload()}>Загрузить настройки банков</button>}
    <div className="bank-rule-list">{rules.map(rule => <article className="bank-rule-card" key={rule.id} aria-label={`Правило ${rule.bank}`}><div><h3>{rule.bank}</h3><p>{channelTitle(rule.channel)} · {rule.sender} · {rule.kind === 'expense' ? 'Расход' : 'Доход'}</p><p>{accounts.find(a => a.id === rule.accountId)?.name ?? 'Счет недоступен'} · {rule.currency}{rule.cardLastFour ? ` · *${rule.cardLastFour}` : ''}{!rule.enabled ? ' · Выключено' : ''}</p></div><div className="bank-rule-actions"><button className="text-button" disabled={busy || !ready} onClick={() => { setEditor({ ...rule }); setError(''); }}>Изменить</button><button className="text-button" disabled={busy || !ready} onClick={() => void updateRules(rules.map(r => r.id === rule.id ? { ...r, enabled: !r.enabled } : r))}>{rule.enabled ? 'Выключить' : 'Включить'}</button><button className="text-button" disabled={busy || !ready} onClick={() => setRemoving(rule)}>Удалить</button></div></article>)}</div>
    {ready && !rules.length && !editor && <p className="empty">Добавьте банк и задайте шаблон его платежных сообщений. Для разных форматов можно создать несколько правил.</p>}
    {removing && <div className="notice" role="group" aria-label="Удаление правила банка"><p>Удалить правило «{removing.bank}»? Принятые предложения и операции сохранятся.</p><button disabled={busy} onClick={() => void updateRules(rules.filter(r => r.id !== removing.id))}>Удалить правило</button><button className="secondary" disabled={busy} onClick={() => setRemoving(null)}>Отмена удаления</button></div>}
    {editor && <form className="bank-rule-editor" aria-label="Правило банка" onSubmit={event => {
      event.preventDefault(); setError('');
      try { validateBankRule(editor); const account = activeAccounts.find(a => a.id === editor.accountId); if (!account || account.currency !== editor.currency) throw new Error('Выберите активный счет.'); void updateRules(rules.some(r => r.id === editor.id) ? rules.map(r => r.id === editor.id ? editor : r) : [...rules, editor]); } catch (e) { show(e); }
    }}>
      <h3>{rules.some(r => r.id === editor.id) ? 'Изменить правило банка' : 'Новый банк'}</h3>
      {!activeAccounts.length && <p className="notice">Сначала добавьте активный счет в разделе «Счета».</p>}
      <label>Название банка<input autoFocus required maxLength={80} value={editor.bank} disabled={busy} onChange={e => setEditor({ ...editor, bank: e.target.value })} placeholder="Например, Revolut" /></label>
      <div className="bank-fields"><label>Тип сообщения<select value={editor.channel} disabled={busy} onChange={e => setEditor({ ...editor, channel: e.target.value as BankRule['channel'] })}><option value="sms">SMS</option><option value="push">Push-уведомление</option></select></label><label>{editor.channel === 'sms' ? 'Отправитель SMS' : 'Приложение-отправитель'}<input required maxLength={100} value={editor.sender} disabled={busy} onChange={e => setEditor({ ...editor, sender: e.target.value })} placeholder={editor.channel === 'sms' ? 'Название или номер отправителя' : 'Например, Revolut'} /></label></div>
      <div className="bank-fields"><label>Счет банка<select required value={editor.accountId} disabled={busy} onChange={e => { const account = activeAccounts.find(a => a.id === e.target.value); setEditor({ ...editor, accountId: e.target.value, currency: account?.currency ?? '' }); }}><option value="">Выберите счет</option>{activeAccounts.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label><label>Тип операции<select value={editor.kind} disabled={busy} onChange={e => setEditor({ ...editor, kind: e.target.value as BankRule['kind'] })}><option value="expense">Расход</option><option value="income">Доход</option></select></label></div>
      <label>Шаблон сообщения<textarea required maxLength={1000} rows={4} value={editor.template} disabled={busy} onChange={e => setEditor({ ...editor, template: e.target.value })} placeholder="Оплата {amount} {currency} в {merchant}, карта *{card}" /></label>
      <p className="hint">Скопируйте постоянный текст сообщения, заменив сумму на {'{amount}'}, валюту на {'{currency}'}, получателя на {'{merchant}'}, четыре цифры карты на {'{card}'}. Нужна только сумма; остальные поля необязательны. Валюта должна совпадать со счетом. Текст и пробелы должны совпадать с сообщением.</p>
      <div className="bank-fields"><label>Десятичный разделитель<select value={editor.decimalSeparator} disabled={busy} onChange={e => setEditor({ ...editor, decimalSeparator: e.target.value as ',' | '.' })}><option value=",">Запятая: 1 234,56</option><option value=".">Точка: 1,234.56</option></select></label><label>Последние 4 цифры карты<input inputMode="numeric" maxLength={4} pattern="[0-9]{4}" value={editor.cardLastFour} disabled={busy} onChange={e => setEditor({ ...editor, cardLastFour: e.target.value })} placeholder="Необязательно" /></label></div>
      <div className="bank-actions"><button type="button" className="secondary" disabled={busy} onClick={() => setEditor(null)}>Отмена</button><button disabled={busy || !ready}>{busy ? 'Сохраняем…' : 'Сохранить правило'}</button></div>
    </form>}
    {ready && rules.length > 0 && <form className="bank-message-form" aria-label="Распознавание банковского сообщения" onSubmit={e => { e.preventDefault(); recognize(); }}>
      <h3>Распознать сообщение</h3>
      <label>Банк и правило<select required value={selected} disabled={busy || !!pending} onChange={e => selectRule(e.target.value)}><option value="">Выберите правило</option>{rules.filter(r => r.enabled).map(r => <option key={r.id} value={r.id}>{r.bank} · {channelTitle(r.channel)} · {r.kind === 'expense' ? 'Расход' : 'Доход'} · {accounts.find(a => a.id === r.accountId)?.name ?? 'Счет недоступен'}{r.cardLastFour ? ` · *${r.cardLastFour}` : ''}</option>)}</select></label>
      <div className="bank-fields"><label>Канал сообщения<select value={channel} disabled={busy || !!pending} onChange={e => { setChannel(e.target.value as BankRule['channel']); setPreview(null); }}><option value="sms">SMS</option><option value="push">Push-уведомление</option></select></label><label>Отправитель сообщения<input required maxLength={100} value={sender} disabled={busy || !!pending} onChange={e => { setSender(e.target.value); setPreview(null); }} /></label></div>
      <label>Дата и время сообщения<input required type="datetime-local" step="1" value={observed} disabled={busy || !!pending} onChange={e => { setObserved(e.target.value); setPreview(null); }} /></label>
      <label>Текст сообщения<textarea required rows={5} maxLength={8192} value={text} disabled={busy || !!pending} onChange={e => { setText(e.target.value); setPreview(null); }} placeholder="Вставьте одно SMS или push-уведомление банка" /></label>
      <p className="hint">Укажите время исходного сообщения с точностью до секунды. Повтор того же текста от того же отправителя с тем же временем вернет прежнее предложение. Сообщения с другим временем считаются отдельными. SMS и push одной покупки свяжите с одной операцией при проверке предложений.</p>
      <button className="secondary" disabled={busy || !!pending || !selectedRule}>Распознать</button>
      {preview && <div className="bank-message-preview" aria-label="Результат распознавания"><h3>{preview.kind === 'expense' ? 'Расход' : 'Доход'} · {money(preview.amountMinor, preview.currency)}</h3><dl><div><dt>Счет</dt><dd>{accounts.find(a => a.id === preview.accountId)?.name}</dd></div><div><dt>Получатель</dt><dd>{preview.merchant || 'Не указан'}</dd></div>{preview.cardLastFour && <div><dt>Карта</dt><dd>*{preview.cardLastFour}</dd></div>}<div><dt>Время сообщения</dt><dd>{new Date(observed).toLocaleString('ru-RU')}</dd></div></dl><p className="hint">На сервер уйдут распознанные поля без полного текста сообщения. Создание предложения не меняет остатки.</p><button type="button" disabled={busy || !!pending || blocked || !navigator.onLine} onClick={() => void send()}>Добавить в предложения</button></div>}
    </form>}
    {pending && <div className="notice" role="status"><h3>Сохраненный запрос сообщения</h3><p>{pending.bank} · {(() => { const body = JSON.parse(pending.body) as SourceIntake; return `${body.kind === 'expense' ? 'Расход' : 'Доход'} · ${money(body.amount_minor, body.currency)}`; })()}</p><p>{stale ? 'Сессия или поколение данных изменились. Сверьте предложения ниже перед закрытием прежнего запроса.' : pending.rejected ? 'Запрос отклонен. Проверьте сообщение и правило перед новой попыткой.' : 'Результат отправки пока не подтвержден. Повтор использует прежний ключ; новый запрос заблокирован.'}</p>{!pending.rejected && !stale && <button disabled={busy || blocked || !navigator.onLine} onClick={() => void retry()}>Повторить отправку сообщения</button>}{(pending.rejected || stale) && <button className="secondary" disabled={busy} onClick={() => void closePending()}>Закрыть сохраненный запрос сообщения</button>}</div>}
    <details open={reviewOpen} onToggle={e => setReviewOpen(e.currentTarget.open)}><summary>Банковские предложения</summary>{reviewOpen && <SourcesPanel auth={auth} accounts={accounts} changed={changed} fail={fail} revision={revision} sourceNames={sourceNames} />}</details>
  </section>;
}
