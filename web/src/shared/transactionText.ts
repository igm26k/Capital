// Source provenance stays in immutable import commands for safe replay. In the
// product, show the user's note and the category assigned by our own catalogue.
export function transactionNote(note: string): string {
  const lines = note.split(/\r?\n/);
  const imported = lines.some(line => /^Wallet · строка \d+$/.test(line) || /^Зачисление Wallet · строка \d+ · /.test(line));
  if (!imported) return note;
  return lines.filter(line => !/^Wallet · строка \d+$/.test(line) && !/^Зачисление Wallet · строка \d+ · /.test(line) && !/^Категория Wallet: /.test(line)).join('\n').trim();
}
export function transactionReason(reason: string | null): string | null {
  if (reason === 'Начальный остаток Wallet') return 'Начальный остаток';
  if (reason === 'Корректировка Wallet: отсутствует направление перевода' || reason === 'Корректировка Wallet: отсутствует источник поступления') return 'Корректировка остатка';
  return reason;
}
// Only the transaction read model is projected. Write bodies, saved journals,
// idempotency receipts, IDs and category assignments are left intact.
export function presentTransactions(value: unknown): unknown {
  if (!value || typeof value !== 'object') return value;
  if ('items' in value && Array.isArray(value.items)) return { ...value, items: value.items.map(presentTransactions) };
  if ('kind' in value && 'note' in value && typeof value.note === 'string' && 'entries' in value && Array.isArray(value.entries)) {
    const reason = 'reason' in value && (typeof value.reason === 'string' || value.reason === null) ? transactionReason(value.reason) : null;
    return { ...value, note: transactionNote(value.note), ...('reason' in value ? { reason } : {}) };
  }
  return value;
}
