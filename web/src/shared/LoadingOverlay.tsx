import { useEffect, useRef, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import { loadingSnapshot, subscribeLoading } from '../api/client';
export function LoadingOverlay({ active }: { active: boolean }) {
  const network = useSyncExternalStore(subscribeLoading, loadingSnapshot);
  const dialog = useRef<HTMLDialogElement>(null);
  const busy = active || network;
  useEffect(() => {
    const node = dialog.current;
    if (busy && node && !node.open) node.showModal();
    else if (!busy && node?.open) node.close();
  }, [busy]);
  return createPortal(<dialog ref={dialog} className="loading-overlay" aria-label="Загрузка" onCancel={event => event.preventDefault()}>
    <div role="status" aria-live="polite"><span className="loading-spinner" aria-hidden="true" /><span className="sr-only">Загрузка…</span></div>
  </dialog>, document.body);
}
