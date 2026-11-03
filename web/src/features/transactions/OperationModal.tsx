import { useEffect, useId, useRef, useState } from 'react';
import type { Account, Category, Tag } from '../../api/client';
import { ModalClose } from '../../shared/ModalClose';
import { TransactionForm } from './TransactionForm';
import { TransferForm } from './TransferForm';
import { AdjustmentForm } from './AdjustmentForm';
type Kind = 'expense' | 'income' | 'transfer' | 'adjustment';
type Props = { accounts: Account[]; categories: Category[]; tags: Tag[]; disabled: boolean; saving: boolean; error: string; submit: (path: string, body: object) => void; cancel: () => void };
export function OperationModal({ accounts, categories, tags, disabled, saving, error, submit, cancel }: Props) {
  const [kind, setKind] = useState<Kind>('expense'), [validation, setValidation] = useState('');
  const dialog = useRef<HTMLDialogElement>(null), id = useId();
  const savingRef = useRef(saving); savingRef.current = saving;
  const basicID = `${id}-basic`, transferID = `${id}-transfer`, adjustmentID = `${id}-adjustment`;
  const formID = kind === 'transfer' ? transferID : kind === 'adjustment' ? adjustmentID : basicID;
  useEffect(() => {
    const element = dialog.current!, previous = document.activeElement, overflow = document.body.style.overflow;
    element.showModal(); document.body.style.overflow = 'hidden';
    const viewport = window.visualViewport;
    const resize = () => {
      element.style.setProperty('--operation-viewport-height', `${viewport?.height ?? window.innerHeight}px`);
      element.style.setProperty('--operation-viewport-top', `${viewport?.offsetTop ?? 0}px`);
    };
    resize(); viewport?.addEventListener('resize', resize); viewport?.addEventListener('scroll', resize);
    window.addEventListener('resize', resize);
    const root = history.state?.capitalScreen && history.state.depth === 0;
    if (root && history.state.capitalModal !== id) history.pushState({ ...history.state, capitalModal: id }, '', location.href);
    const leave = () => { if (!savingRef.current) cancel(); };
    window.addEventListener('popstate', leave);
    return () => {
      window.removeEventListener('popstate', leave);
      viewport?.removeEventListener('resize', resize); viewport?.removeEventListener('scroll', resize); window.removeEventListener('resize', resize);
      element.close(); document.body.style.overflow = overflow;
      queueMicrotask(() => { if (!element.open && history.state?.capitalModal === id) history.back(); });
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
    <div className="modal-heading"><h2 id={`${id}-title`}>Новая операция</h2><ModalClose back close={cancel} disabled={saving} /></div>
    <div className="modal-content">
      <div className="operation-kind-tabs" role="group" aria-label="Тип операции">{([['expense', 'Расход'], ['income', 'Доход'], ['transfer', 'Перевод']] as const).map(([value, label]) => <button type="button" key={value} disabled={disabled} aria-pressed={kind === value} onClick={() => { setKind(value); setValidation(''); }}>{label}</button>)}</div>

      {(validation || error) && <p role="alert" className="notice">{validation || error}</p>}
      {!accounts.length && <p className="notice">Сначала добавьте счет в разделе «Счета».</p>}
      <div hidden={kind !== 'expense' && kind !== 'income'}><TransactionForm embedded formID={basicID} creationKind={kind === 'income' ? 'income' : 'expense'} accounts={accounts} categories={categories} tags={tags} disabled={disabled} fail={setValidation} submit={body => { setValidation(''); submit('transactions', body); }} /></div>
      <div hidden={kind !== 'transfer'}><TransferForm embedded formID={transferID} accounts={accounts} categories={categories} tags={tags} disabled={disabled} fail={setValidation} submit={body => { setValidation(''); submit('transactions', body); }} /></div>
      <div hidden={kind !== 'adjustment'}><AdjustmentForm embedded formID={adjustmentID} accounts={accounts} disabled={disabled} fail={setValidation} submit={(path, body) => { setValidation(''); submit(path, body); }} /></div>
      <details className="operation-other-kind"><summary>Другие операции</summary><button type="button" className="secondary" disabled={disabled} aria-pressed={kind === 'adjustment'} onClick={() => { setKind('adjustment'); setValidation(''); }}>Корректировка</button></details>
    </div>
    <div className="modal-actions"><button type="button" className="secondary" disabled={saving} onClick={cancel}>Отмена</button><button type="submit" form={formID} disabled={disabled || unavailable}>{saving ? 'Добавляем…' : 'Сохранить операцию'}</button></div>
  </dialog>;
}
