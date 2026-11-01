import { useRef, useState } from 'react';
import { localDateTime } from './time';
export function OccurrenceFields({ label, initial, name = 'occurred_at', disabled = false }: { label: string; initial?: string; name?: string; disabled?: boolean }) {
  const [value, setValue] = useState(() => localDateTime(initial ?? new Date().toISOString()));
  const picker = useRef<HTMLInputElement>(null);
  const [date, time] = value.split('T'), [year, month, day] = date.split('-');
  return <label>{label}<span className="occurrence-picker"><input aria-label={label} readOnly disabled={disabled} value={`${day}.${month}.${year} ${time.slice(0, 8)}`} onClick={() => picker.current?.showPicker?.()} /><input ref={picker} aria-label={`Календарь: ${label}`} type="datetime-local" onClick={e => { try { e.currentTarget.showPicker?.(); } catch { /* Native control remains available. */ } }} name={name} step="0.001" required disabled={disabled} value={value} onChange={e => setValue(e.target.value.length === 16 ? e.target.value + ':00' : e.target.value)} /></span>{disabled && <span className="hint">Дата комиссии задается переводом.</span>}</label>;
}
