export const timezone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
export function localDateTime(instant: string): string {
  const d = new Date(instant), two = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}T${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`;
}
export function occurrence(data: FormData, original?: { occurred_at: string; occurred_timezone: string }, field = 'occurred_at') {
  const value = String(data.get(field) ?? '');
  if (!value && original) return { occurred_at: original.occurred_at, occurred_timezone: original.occurred_timezone };
  const date = new Date(value);
  if (!value || !Number.isFinite(date.getTime())) throw new Error('Проверьте дату и время.');
  const normalized = value.length === 16 ? `${value}:00.000` : value.length === 19 ? `${value}.000` : value.padEnd(23, '0');
  if (original && normalized === localDateTime(original.occurred_at)) return { occurred_at: original.occurred_at, occurred_timezone: original.occurred_timezone };
  return { occurred_at: date.toISOString(), occurred_timezone: timezone() };
}
