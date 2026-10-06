import { useState } from 'react';
import { localDateTime, timezone } from './time';
export function OccurrenceFields({ label, initial, name = 'occurred_at', disabled = false }: { label: string; initial?: string; name?: string; disabled?: boolean }) {
  const [value] = useState(() => localDateTime(initial ?? new Date().toISOString()));
  return <><label>{label}<input aria-label={label} type="datetime-local" name={name} step="0.001" required disabled={disabled} defaultValue={value} /></label><p className="hint">Время в часовом поясе {timezone()}.{disabled && ' Дата комиссии задается переводом.'}</p></>;
}
