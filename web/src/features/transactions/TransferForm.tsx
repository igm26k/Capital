import { useState, type FormEvent } from 'react';
import type { Account, Category } from '../../api/client';
import { minor } from '../../shared/money';
import { categoryLabel } from '../../shared/categories';
import { timezone } from '../auth/AuthForm';

export function TransferForm({ accounts, categories, disabled, submit, fail }: { accounts: Account[]; categories: Category[]; disabled: boolean; submit: (body: object) => void; fail: (message: string) => void }) {
  const [sourceID, setSourceID] = useState(''), [targetID, setTargetID] = useState('');
  const [withFee, setWithFee] = useState(false), [feeAccountID, setFeeAccountID] = useState('');
  const source = accounts.find(a => a.id === sourceID) ?? accounts[0];
  const targets = accounts.filter(a => a.id !== source?.id);
  const target = targets.find(a => a.id === targetID) ?? targets[0];
  const feeAccount = accounts.find(a => a.id === feeAccountID) ?? source;
  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!source || !target) return;
    const data = new FormData(event.currentTarget);
    try {
      const debit = minor(String(data.get('source_amount')), source.currency);
      const credit = source.currency === target.currency ? debit : minor(String(data.get('target_amount')), target.currency);
      if (BigInt(debit) <= 0n || BigInt(credit) <= 0n) throw new Error('Суммы перевода должны быть больше нуля.');
      let fee = null;
      if (withFee && feeAccount) {
        const amount = minor(String(data.get('fee_amount')), feeAccount.currency);
        if (BigInt(amount) <= 0n) throw new Error('Комиссия должна быть больше нуля.');
        fee = { id: crypto.randomUUID(), account_id: feeAccount.id, amount_minor: amount, allocations: [{ id: crypto.randomUUID(), category_id: data.get('fee_category') || null, amount_minor: amount }], note: '', tag_ids: [] };
      }
      submit({ id: crypto.randomUUID(), kind: 'transfer', source_account_id: source.id, target_account_id: target.id, source_amount_minor: debit, target_amount_minor: credit, rate: null, fee, occurred_at: new Date().toISOString(), occurred_timezone: timezone(), note: data.get('transfer_note'), payee: '', tag_ids: [] });
    } catch (error) { fail((error as Error).message); }
  }
  return <section><h2>Новый перевод</h2><form onSubmit={create}><fieldset disabled={disabled || !target}>
    <label>Со счета<select aria-label="Со счета" value={source?.id ?? ''} onChange={event => setSourceID(event.target.value)}>{accounts.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label>
    <label>На счет<select aria-label="На счет" value={target?.id ?? ''} onChange={event => setTargetID(event.target.value)}>{targets.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label>
    <label>Сумма списания, {source?.currency ?? 'EUR'}<input name="source_amount" inputMode="decimal" required /></label>
    {target && source?.currency !== target.currency && <label>Сумма зачисления, {target.currency}<input key={target.currency} name="target_amount" inputMode="decimal" required /></label>}
    <p className="hint">{source?.currency === target?.currency ? 'На второй счет поступит та же сумма.' : 'Укажите обе точные суммы. Курс рассчитает сервер.'}</p>
    <label className="check"><input type="checkbox" checked={withFee} onChange={event => setWithFee(event.target.checked)} />Добавить комиссию</label>
    {withFee && <>
      <label>Счет комиссии<select aria-label="Счет комиссии" value={feeAccount?.id ?? ''} onChange={event => setFeeAccountID(event.target.value)}>{accounts.map(a => <option key={a.id} value={a.id}>{a.name} · {a.currency}</option>)}</select></label>
      <label>Комиссия, {feeAccount?.currency}<input key={feeAccount?.currency} name="fee_amount" inputMode="decimal" required /></label>
      <label>Категория комиссии<select aria-label="Категория комиссии" name="fee_category"><option value="">Без категории</option>{categories.filter(c => !c.archived_at).map(c => <option key={c.id} value={c.id}>{categoryLabel(c, categories)}</option>)}</select></label>
      <p className="hint">Комиссия будет записана как расход вместе с переводом.</p>
    </>}
    <label>Описание перевода<textarea name="transfer_note" maxLength={2000} /></label><button>Сохранить перевод</button>
  </fieldset></form>{!target && <p>Для перевода создайте второй счет.</p>}</section>;
}
