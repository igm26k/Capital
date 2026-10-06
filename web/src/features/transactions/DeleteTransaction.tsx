import type { Account, Transaction } from '../../api/client';
import { money } from '../../shared/money';
export type Deletion = { transaction: Transaction; related: Transaction[] };
export function DeleteTransaction({ value, accounts, disabled, confirm, cancel }: { value: Deletion; accounts: Account[]; disabled: boolean; confirm: () => void; cancel: () => void }) {
  const { transaction, related } = value;
  const cascade = transaction.kind === 'transfer' ? related.filter(t => t.id === transaction.fee_transaction_id) : [];
  return <section className="notice"><h2>Удаление операции</h2><p>{transaction.payee || transaction.note || new Date(transaction.occurred_at).toLocaleString('ru-RU')}</p><p>Движения будут исключены из остатка. Связанные версии проверит сервер.</p><ul>{[transaction, ...cascade].flatMap(t => t.entries.map((e, i) => <li key={`${t.id}-${i}`}>{accounts.find(a => a.id === e.account_id)?.name ?? 'Счет недоступен'} · {money(e.amount_minor, e.currency)}{t.id !== transaction.id && ' · Комиссия перевода'}</li>))}</ul>{cascade.length > 0 && <p>Комиссия будет удалена вместе с переводом.</p>}<button disabled={disabled} onClick={confirm}>Подтвердить удаление операции</button><button className="secondary" disabled={disabled} onClick={cancel}>Отменить удаление</button></section>;
}
