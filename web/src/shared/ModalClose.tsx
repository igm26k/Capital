export function ModalClose({ close, disabled = false, back = false }: { close: () => void; disabled?: boolean; back?: boolean }) {
  return <button type="button" className="modal-close" aria-label="Закрыть окно" disabled={disabled} onClick={close}><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d={back ? "M15 5l-7 7 7 7" : "M6 6l12 12M18 6L6 18"} /></svg></button>;
}
