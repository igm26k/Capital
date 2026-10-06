import { useState, type FormEvent } from 'react';
import type { Account, Category, Tag, Transaction } from '../../api/client';
import { decimal, minor } from '../../shared/money';
import { categoryLabel } from '../../shared/categories';
import { occurrence } from '../../shared/time';
import { OccurrenceFields } from '../../shared/OccurrenceFields';
type Part = { id: string; category: string; amount: string };
type Props = { accounts: Account[]; categories: Category[]; tags: Tag[]; disabled: boolean; submit: (body: object) => void; fail: (message: string) => void; initial?: Transaction; initialFee?: Transaction | null; cancel?: () => void };

export function TransferForm({ accounts, categories, tags, disabled, submit, fail, initial, initialFee, cancel }: Props) {
  const originalSource = initial?.entries.find(e => BigInt(e.amount_minor) < 0n);
  const originalTarget = initial?.entries.find(e => BigInt(e.amount_minor) > 0n);
  const [sourceID, setSourceID] = useState(originalSource?.account_id ?? ''), [targetID, setTargetID] = useState(originalTarget?.account_id ?? '');
  const [withFee, setWithFee] = useState(!!initialFee), [feeAccountID, setFeeAccountID] = useState(initialFee?.entries[0]?.account_id ?? '');
  const [parts, setParts] = useState<Part[]>(initialFee ? initialFee.allocations.map(p => ({ id: p.id, category: p.category_id ?? '', amount: decimal(p.amount_minor, initialFee.entries[0].currency) })) : [{ id: crypto.randomUUID(), category: '', amount: '' }]);
  const sources = accounts.filter(a => (!a.archived_at || a.id === originalSource?.account_id) && (!originalSource || a.currency === originalSource.currency));
  const source = sources.find(a => a.id === sourceID) ?? sources[0];
  const targets = accounts.filter(a => a.id !== source?.id && (!a.archived_at || a.id === originalTarget?.account_id) && (!originalTarget || a.currency === originalTarget.currency));
  const target = targets.find(a => a.id === targetID) ?? targets[0];
  const feeAccounts = accounts.filter(a => (!a.archived_at || a.id === initialFee?.entries[0]?.account_id) && (!initialFee || a.currency === initialFee.entries[0].currency));
  const feeAccount = feeAccounts.find(a => a.id === feeAccountID) ?? feeAccounts.find(a => a.id === source?.id) ?? feeAccounts[0];
  function update(id: string, patch: Partial<Part>) { setParts(items => items.map(p => p.id === id ? { ...p, ...patch } : p)); }
  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!source || !target) return;
    const data = new FormData(event.currentTarget);
    try {
      const debit = minor(String(data.get('source_amount')), source.currency);
      const credit = source.currency === target.currency ? debit : minor(String(data.get('target_amount')), target.currency);
      if (BigInt(debit) <= 0n || BigInt(credit) <= 0n) throw new Error('Суммы перевода должны быть больше нуля.');
      let fee = null;
      if (withFee) {
        if (!feeAccount) throw new Error('Выберите счет комиссии.');
        const amount = minor(String(data.get('fee_amount')), feeAccount.currency);
        if (BigInt(amount) <= 0n) throw new Error('Комиссия должна быть больше нуля.');
        const allocations = parts.map(p => ({ id: p.id, category_id: p.category || null, amount_minor: parts.length === 1 ? amount : minor(p.amount, feeAccount.currency) }));
        if (allocations.some(p => BigInt(p.amount_minor) <= 0n) || allocations.reduce((sum, p) => sum + BigInt(p.amount_minor), 0n) !== BigInt(amount)) throw new Error('Положительные части комиссии должны точно совпадать с ее суммой.');
        fee = { id: initialFee?.id ?? crypto.randomUUID(), account_id: feeAccount.id, amount_minor: amount, allocations, note: String(data.get('fee_note') ?? ''), tag_ids: data.getAll('fee_tag') };
      }
      submit({ ...(initial ? { expected_version: initial.version, expected_fee_version: initialFee?.version ?? null } : { id: crypto.randomUUID() }), kind: 'transfer', source_account_id: source.id, target_account_id: target.id, source_amount_minor: debit, target_amount_minor: credit, rate: null, fee, ...occurrence(data, initial), note: data.get('transfer_note'), payee: data.get('transfer_payee'), tag_ids: data.getAll('transfer_tag') });
    } catch (error) { fail((error as Error).message); }
  }
  return <section><h2>{initial ? 'Изменить перевод' : 'Новый перевод'}</h2><form onSubmit={save}><fieldset disabled={disabled || !target}>
    <label>Со счета<select aria-label="Со счета" value={source?.id ?? ''} onChange={event => setSourceID(event.target.value)}>{sources.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label>
    <label>На счет<select aria-label="На счет" value={target?.id ?? ''} onChange={event => setTargetID(event.target.value)}>{targets.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label>
    <label>Сумма списания, {source?.currency ?? 'EUR'}<input name="source_amount" inputMode="decimal" required defaultValue={originalSource ? decimal((-BigInt(originalSource.amount_minor)).toString(), originalSource.currency) : ''} /></label>
    {target && source?.currency !== target.currency && <label>Сумма зачисления, {target.currency}<input key={target.currency} name="target_amount" inputMode="decimal" required defaultValue={originalTarget ? decimal(originalTarget.amount_minor, originalTarget.currency) : ''} /></label>}
    <p className="hint">{source?.currency === target?.currency ? 'На второй счет поступит та же сумма.' : 'Укажите обе точные суммы. Курс рассчитает сервер.'}</p>
    <label className="check"><input type="checkbox" checked={withFee} onChange={event => setWithFee(event.target.checked)} />Добавить комиссию</label>
    {withFee && <>
      <label>Счет комиссии<select aria-label="Счет комиссии" value={feeAccount?.id ?? ''} onChange={event => setFeeAccountID(event.target.value)}>{feeAccounts.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label>
      <label>Комиссия, {feeAccount?.currency}<input key={feeAccount?.currency} name="fee_amount" inputMode="decimal" required defaultValue={initialFee ? decimal(initialFee.allocations.reduce((sum, p) => sum + BigInt(p.amount_minor), 0n).toString(), initialFee.entries[0].currency) : ''} /></label>
      {parts.map((part, index) => <div key={part.id}><label>{parts.length === 1 ? 'Категория комиссии' : `Категория части комиссии ${index + 1}`}<select aria-label={parts.length === 1 ? 'Категория комиссии' : `Категория части комиссии ${index + 1}`} value={part.category} onChange={event => update(part.id, { category: event.target.value })}><option value="">Без категории</option>{categories.filter(c => !c.archived_at || initialFee?.allocations.find(p => p.id === part.id)?.category_id === c.id).map(c => <option key={c.id} value={c.id}>{categoryLabel(c, categories)}</option>)}</select></label>{parts.length > 1 && <><label>Сумма части комиссии {index + 1}<input value={part.amount} inputMode="decimal" required onChange={event => update(part.id, { amount: event.target.value })} /></label><button type="button" className="secondary" onClick={() => setParts(items => items.filter(p => p.id !== part.id))}>Удалить часть комиссии {index + 1}</button></>}</div>)}
      <button type="button" className="secondary" disabled={parts.length >= 100} onClick={event => { const total = String(new FormData(event.currentTarget.form!).get('fee_amount')); setParts(items => [...items.map(p => items.length === 1 ? { ...p, amount: total } : p), { id: crypto.randomUUID(), category: '', amount: '' }]); }}>Добавить часть комиссии</button>
      <label>Описание комиссии<textarea aria-label="Описание комиссии" name="fee_note" maxLength={2000} defaultValue={initialFee?.note} /></label>
      {tags.some(t => !t.archived_at || initialFee?.tag_ids.includes(t.id)) && <fieldset className="tag-options"><legend>Теги комиссии</legend>{tags.filter(t => !t.archived_at || initialFee?.tag_ids.includes(t.id)).map(t => <label className="check" key={t.id}><input type="checkbox" aria-label={`Тег комиссии ${t.name}`} name="fee_tag" value={t.id} defaultChecked={initialFee?.tag_ids.includes(t.id)} />{t.name}</label>)}</fieldset>}
      <p className="hint">Комиссия будет записана как расход вместе с переводом.</p>
    </>}
    <OccurrenceFields label="Дата перевода" initial={initial?.occurred_at} />
    <label>Получатель перевода<input name="transfer_payee" maxLength={200} defaultValue={initial?.payee} /></label>
    {tags.some(t => !t.archived_at || initial?.tag_ids.includes(t.id)) && <fieldset className="tag-options"><legend>Теги перевода</legend>{tags.filter(t => !t.archived_at || initial?.tag_ids.includes(t.id)).map(t => <label className="check" key={t.id}><input type="checkbox" aria-label={`Тег перевода ${t.name}`} name="transfer_tag" value={t.id} defaultChecked={initial?.tag_ids.includes(t.id)} />{t.name}</label>)}</fieldset>}
    <label>Описание перевода<textarea aria-label="Описание перевода" name="transfer_note" maxLength={2000} defaultValue={initial?.note} /></label><button>{initial ? 'Сохранить изменения перевода' : 'Сохранить перевод'}</button>{cancel && <button type="button" className="secondary" onClick={cancel}>Отменить редактирование</button>}
  </fieldset></form>{!target && <p>Для перевода создайте второй счет.</p>}</section>;
}
