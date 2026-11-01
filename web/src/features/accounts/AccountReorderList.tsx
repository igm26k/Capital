import { useEffect, useId, useRef, useState, type PointerEvent } from 'react';
import { createPortal } from 'react-dom';
import type { Account } from '../../api/client';
import { money } from '../../shared/money';
import { accountColor } from '../../shared/AccountSelect';
import { Icon } from '../../shared/Icon';
type Drag = { id: string; pointer: number; startY: number; y: number; left: number; top: number; width: number; target: number; active: boolean; handle: HTMLButtonElement };
export function AccountReorderList({ accounts, disabled, move, edit }: { accounts: Account[]; disabled: boolean; move: (id: string, index: number) => void; edit: (account: Account) => void }) {
  const list = useRef<HTMLUListElement>(null), session = useRef<Drag | null>(null), frame = useRef(0);
  const [drag, setDrag] = useState<Drag | null>(null), [announcement, announce] = useState('');
  const instruction = useId();
  function targetAt(y: number) {
    const rows = [...(list.current?.children ?? [])] as HTMLElement[];
    let nearest = 0, distance = Infinity;
    rows.forEach((row, index) => { const box = row.getBoundingClientRect(), delta = Math.abs(y - box.top - box.height / 2); if (delta < distance) { nearest = index; distance = delta; } });
    return nearest;
  }
  function stop(cancel = false) {
    const current = session.current;
    session.current = null; cancelAnimationFrame(frame.current); setDrag(null);
    if (!current) return;
    if (current.handle.hasPointerCapture(current.pointer)) current.handle.releasePointerCapture(current.pointer);
    if (!cancel && current.active && current.target !== accounts.findIndex(a => a.id === current.id)) {
      move(current.id, current.target);
      announce(`Счет перемещен на позицию ${current.target + 1}. Сохраните порядок.`);
    }
    requestAnimationFrame(() => { if (current.handle.isConnected) current.handle.focus({ preventScroll: true }); });
  }
  function autoScroll() {
    const current = session.current;
    if (!current?.active) return;
    const top = 120, bottom = window.innerHeight - 120;
    const speed = current.y < top ? -Math.min(18, (top - current.y) / 4) : current.y > bottom ? Math.min(18, (current.y - bottom) / 4) : 0;
    if (speed) {
      window.scrollBy(0, speed);
      current.target = targetAt(current.y);
      setDrag({ ...current });
    }
    frame.current = requestAnimationFrame(autoScroll);
  }
  function start(event: PointerEvent<HTMLButtonElement>, id: string) {
    if (disabled || !event.isPrimary || event.button !== 0) return;
    const row = event.currentTarget.closest('li')!.getBoundingClientRect();
    event.preventDefault(); event.currentTarget.focus({ preventScroll: true });
    event.currentTarget.setPointerCapture(event.pointerId);
    session.current = { id, pointer: event.pointerId, startY: event.clientY, y: event.clientY, left: row.left, top: row.top, width: row.width, target: accounts.findIndex(a => a.id === id), active: false, handle: event.currentTarget };
  }
  function update(event: PointerEvent<HTMLButtonElement>) {
    const current = session.current;
    if (!current || current.pointer !== event.pointerId) return;
    current.y = event.clientY;
    if (!current.active && Math.abs(current.y - current.startY) < 5) return;
    event.preventDefault();
    if (!current.active) { current.active = true; frame.current = requestAnimationFrame(autoScroll); }
    current.target = targetAt(current.y);
    setDrag({ ...current });
  }
  const accountIDs = accounts.map(account => account.id).join('|');
  useEffect(() => { stop(true); }, [disabled, accountIDs]);
  useEffect(() => () => { cancelAnimationFrame(frame.current); session.current = null; }, []);
  const dragged = accounts.find(a => a.id === drag?.id);
  return <>
    <p className="hint" id={instruction}>Перетащите счет за значок ⋮⋮. С клавиатуры используйте стрелки вверх и вниз.</p>
    <span className="sr-only" role="status">{announcement}</span>
    <ul ref={list} className={`account-management-list${drag?.active ? ' is-reordering' : ''}`} aria-label="Порядок счетов">
      {accounts.map((account, index) => <li key={account.id} data-account-id={account.id} className={drag?.active && drag.id === account.id ? 'account-drag-source' : drag?.active && drag.target === index ? (index < accounts.findIndex(a => a.id === drag.id) ? 'account-drop-before' : 'account-drop-after') : undefined}>
        <span className="account-color-dot" aria-hidden="true" style={{ backgroundColor: accountColor(account.id) }} /><div className="account-list-copy"><strong>{account.name}</strong><span>{money(account.posted_balance_minor, account.currency)}{account.archived_at && ' · В архиве'}</span></div>
        <div className="account-list-actions">
          <button className="secondary account-drag-handle" disabled={disabled} aria-label={`Перетащить счет ${account.name}`} aria-describedby={instruction} onPointerDown={event => start(event, account.id)} onPointerMove={update} onPointerUp={event => { if (session.current?.pointer === event.pointerId) stop(); }} onPointerCancel={event => { if (session.current?.pointer === event.pointerId) stop(true); }} onLostPointerCapture={event => { if (session.current?.pointer === event.pointerId) stop(true); }} onKeyDown={event => {
            if (event.key === 'Escape') { stop(true); return; }
            const delta = event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0;
            if (!delta || disabled || index + delta < 0 || index + delta >= accounts.length) return;
            event.preventDefault(); const handle = event.currentTarget;
            move(account.id, index + delta); announce(`Счет перемещен на позицию ${index + delta + 1}. Сохраните порядок.`);
            requestAnimationFrame(() => handle.focus({ preventScroll: true }));
          }}><Icon name="drag" /></button>
          <button className="secondary" disabled={disabled} aria-label={`Изменить счет ${account.name}`} onClick={() => edit(account)}><Icon name="settings" /></button>
        </div>
      </li>)}
    </ul>
    {drag?.active && dragged && createPortal(<div className="account-drag-ghost" aria-hidden="true" style={{ left: drag.left, top: drag.top, width: drag.width, transform: `translateY(${drag.y - drag.startY}px)` }}><Icon name="drag" /><span className="account-color-dot" style={{ backgroundColor: accountColor(dragged.id) }} /><div className="account-list-copy"><strong>{dragged.name}</strong><span>{money(dragged.posted_balance_minor, dragged.currency)}</span></div></div>, document.body)}
  </>;
}
