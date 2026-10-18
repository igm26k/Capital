import { useEffect, useId, useRef, useState } from 'react';
import type { Account, Category, Tag } from '../../api/client';
import { TransactionForm } from './TransactionForm';
import { TransferForm } from './TransferForm';
import { AdjustmentForm } from './AdjustmentForm';
type Kind = 'expense' | 'income' | 'transfer' | 'adjustment';
type Props = { accounts: Account[]; categories: Category[]; tags: Tag[]; disabled: boolean; saving: boolean; error: string; submit: (path: string, body: object) => void; cancel: () => void };
export function OperationModal({ accounts, categories, tags, disabled, saving, error, submit, cancel }: Props) {
  const [kind, setKind] = useState<Kind>('expense'), [validation, setValidation] = useState('');
  const dialog = useRef<HTMLDialogElement>(null), id = useId();
  const basicID = `${id}-basic`, transferID = `${id}-transfer`, adjustmentID = `${id}-adjustment`;
  const formID = kind === 'transfer' ? transferID : kind === 'adjustment' ? adjustmentID : basicID;
  useEffect(() => {
    const element = dialog.current!, previous = document.activeElement, overflow = document.body.style.overflow;
    element.showModal(); document.body.style.overflow = 'hidden';
    return () => {
      element.close(); document.body.style.overflow = overflow;
      if (previous instanceof HTMLElement && previous.offsetParent !== null) previous.focus();
      else document.querySelector<HTMLButtonElement>('.operations-add')?.focus();
    };
  }, []);
  useEffect(() => {
    if (error || validation) dialog.current?.querySelector('.modal-content')?.scrollTo({ top: 0 });
  }, [error, validation]);
  const unavailable = kind === 'transfer' ? accounts.length < 2 : !accounts.length;
  return <dialog ref={dialog} className="operation-modal" aria-labelledby={`${id}-title`} onCancel={event => { event.preventDefault(); if (!saving) cancel(); }} onKeyDown={event => {
      if (event.key !== 'Tab') return;
      const controls = [...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [href], [tabindex="0"]')].filter(element => element.getClientRects().length > 0);
      const first = controls[0], last = controls[controls.length - 1];
      if (!first) { event.preventDefault(); return; }
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }}>
    <div className="modal-heading"><h2 id={`${id}-title`}>Новая операция</h2></div>
    <div className="modal-content">
      <label>Тип операции<select autoFocus aria-label="Тип операции" value={kind} disabled={disabled} onChange={event => { setKind(event.target.value as Kind); setValidation(''); }}><option value="expense">Расход</option><option value="income">Доход</option><option value="transfer">Перевод</option><option value="adjustment">Корректировка</option></select></label>
      {(validation || error) && <p role="alert" className="notice">{validation || error}</p>}
      {!accounts.length && <p className="notice">Сначала добавьте счет в разделе «Счета».</p>}
      <div hidden={kind !== 'expense' && kind !== 'income'}><TransactionForm embedded formID={basicID} creationKind={kind === 'income' ? 'income' : 'expense'} accounts={accounts} categories={categories} tags={tags} disabled={disabled} fail={setValidation} submit={body => { setValidation(''); submit('transactions', body); }} /></div>
      <div hidden={kind !== 'transfer'}><TransferForm embedded formID={transferID} accounts={accounts} categories={categories} tags={tags} disabled={disabled} fail={setValidation} submit={body => { setValidation(''); submit('transactions', body); }} /></div>
      <div hidden={kind !== 'adjustment'}><AdjustmentForm embedded formID={adjustmentID} accounts={accounts} disabled={disabled} fail={setValidation} submit={(path, body) => { setValidation(''); submit(path, body); }} /></div>
    </div>
    <div className="modal-actions"><button type="button" className="secondary" disabled={saving} onClick={cancel}>Отмена</button><button type="submit" form={formID} disabled={disabled || unavailable}>{saving ? 'Добавляем…' : 'Добавить'}</button></div>
  </dialog>;
}
