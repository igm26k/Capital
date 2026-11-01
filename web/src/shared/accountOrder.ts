import type { Account } from '../api/client';
export type AccountOrder = { version: string; account_ids: string[] };
export function orderAccounts(accounts: Account[], ids: readonly string[] = []): Account[] {
  const positions = new Map(ids.map((id, index) => [id, index]));
  return accounts.map((account, index) => ({ account, index })).sort((a, b) =>
    (positions.get(a.account.id) ?? ids.length) - (positions.get(b.account.id) ?? ids.length) || a.index - b.index
  ).map(item => item.account);
}
