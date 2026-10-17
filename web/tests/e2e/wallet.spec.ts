import { test, expect } from '@playwright/test';
import { parseWallet, planWallet, walletPairs } from '../../src/features/imports/wallet';
import type { Account, Auth, List, Transaction } from '../../src/api/client';

test('Wallet plans commit to the real test ledger and exact replay does not duplicate records', async ({ context }) => {
  const headers = { Origin: 'https://localhost:8444' };
  const registration = await context.request.post('/api/v1/auth/register', { headers, data: { email: `wallet-${crypto.randomUUID()}@example.test`, password: 'wallet-synthetic-test-password-2026', device_name: 'Wallet import acceptance', transport: 'cookie', timezone: 'UTC' } });
  expect(registration.status()).toBe(201);
  const auth = await registration.json() as Auth;
  const header = 'account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels\n';
  const file = await parseWallet(header + [
    'Wallet Cash;Groceries;EUR;12.34;999;Expense;Cash;Synthetic expense;2025-08-12T15:00:00.000Z;false;;',
    'Wallet Cash;Groceries;EUR;5;999;Income;Cash;Synthetic income;2025-08-12T15:00:00.000Z;false;;',
    'Wallet Bank;Transfer;USD;100;999;Expense;Card;;2025-08-12T15:00:00.000Z;true;;',
    'Wallet Cash;Transfer;EUR;91;999;Income;Cash;;2025-08-12T15:00:00.000Z;true;;',
  ].join('\n'));
  const plan = await planWallet(file, walletPairs(file.rows), [], [], auth.profile.id, auth.workspace.id, auth.workspace.sync_generation_id, false);
  expect(plan.transactions).toBe(3);
  for (let pass = 0; pass < 2; pass++) {
    for (const command of plan.commands) {
      const response = await context.request.post(`/api/v1/workspaces/${auth.workspace.id}/${command.path}`, { headers: { ...headers, 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': plan.generation, 'Idempotency-Key': command.key, 'Content-Type': 'application/json' }, data: command.body });
      expect(response.status(), `${command.label}: ${await response.text()}`).toBe(201);
      if (pass) expect(response.headers()['idempotency-replayed']).toBe('true');
    }
  }
  const accountResponse = await context.request.get(`/api/v1/workspaces/${auth.workspace.id}/accounts`);
  const accounts = (await accountResponse.json() as List<Account>).items;
  expect(accounts).toHaveLength(2);
  expect(accounts.find(a => a.currency === 'USD')?.posted_balance_minor).toBe('-10000');
  expect(accounts.find(a => a.currency === 'EUR')?.posted_balance_minor).toBe('8366');
  const history = await context.request.get(`/api/v1/workspaces/${auth.workspace.id}/transactions`);
  const transactions = (await history.json() as List<Transaction>).items;
  expect(transactions.filter(t => t.kind === 'transfer')).toHaveLength(1);
  expect(transactions.filter(t => t.kind === 'expense')).toHaveLength(1);
  expect(transactions.filter(t => t.kind === 'income')).toHaveLength(1);
  expect(transactions.find(t => t.kind === 'expense')?.allocations[0].category_id).toBeTruthy();
  expect(transactions.find(t => t.kind === 'expense')?.note).toContain('Synthetic expense');
});
