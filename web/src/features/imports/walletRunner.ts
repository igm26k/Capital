import { request, listAll, type Auth } from '../../api/client';
import { closeWalletJournal, compareAuxiliaryCommand, readCSVCommand } from './commandStorage';
import type { WalletPlan } from './wallet';
export type WalletProgress = { key: string; digest: string; cursor: number };
const storageKeys = (owner: string, workspace: string) => {
  const root = `accounting.wallet.pending.v1:${owner}:${workspace}`;
  return { plan: `${root}:plan`, progress: `${root}:progress`, lock: root };
};
export async function loadWallet(auth: Auth): Promise<{ plan: WalletPlan; progress: WalletProgress } | null> {
  const keys = storageKeys(auth.profile.id, auth.workspace.id);
  const plan = await readCSVCommand<WalletPlan>(keys.plan);
  if (!plan) return null;
  if (plan.owner !== auth.profile.id || plan.workspace !== auth.workspace.id || !Array.isArray(plan.commands) || plan.key !== plan.digest) throw new Error('Сохраненный импорт Wallet требует проверки.');
  let progress = await readCSVCommand<WalletProgress>(keys.progress);
  if (!progress) {
    progress = { key: `${plan.digest}:0`, digest: plan.digest, cursor: 0 };
    await compareAuxiliaryCommand(keys.progress, null, progress);
  }
  if (progress.digest !== plan.digest || !Number.isInteger(progress.cursor) || progress.cursor < 0 || progress.cursor > plan.commands.length) throw new Error('Прогресс Wallet не соответствует сохраненному плану.');
  return { plan, progress };
}
export async function walletLock<T>(auth: Auth, action: () => Promise<T>): Promise<T> {
  if (!navigator.locks) throw new Error('Для надежного импорта Wallet нужен браузер с поддержкой Web Locks через HTTPS.');
  return navigator.locks.request(storageKeys(auth.profile.id, auth.workspace.id).lock, { ifAvailable: true }, lock => {
    if (!lock) throw new Error('Импорт Wallet выполняется в другой вкладке.');
    return action();
  });
}
// Stop when an older policy recorded these source rows with a different kind.
// Reclassification requires reconciliation, not an additional balance movement.
export async function checkLegacyWalletRecords(auth: Auth, plan: WalletPlan): Promise<void> {
  const checks = [{ ids: plan.legacyTransferIds ?? [], kind: 'transfer', label: 'переводами' }, { ids: plan.legacyAdjustmentIds ?? [], kind: 'adjustment', label: 'корректировками' }];
  for (const check of checks) {
    if (!check.ids.length) continue;
    if (check.ids.some(id => !/^[0-9a-f-]{36}$/.test(id))) throw new Error('Некорректный идентификатор проверки Wallet.');
    // Missing records are normal. List pages return 200 even when empty,
    // avoiding individual 404 probes while checking every existing record.
    const ids = new Set(check.ids);
    const records = await listAll<{ id: string; kind: string }>(`/workspaces/${auth.workspace.id}/transactions?kind=${check.kind}`);
    if (records.some(record => record.kind === check.kind && ids.has(record.id))) throw new Error(`Часть этих строк уже записана ${check.label} по старому алгоритму. Сначала нужна сверка ранее импортированных операций: новые записи не отправлены, чтобы не изменить остаток дважды.`);
  }
}
export async function saveWallet(auth: Auth, plan: WalletPlan): Promise<void> {
  const keys = storageKeys(auth.profile.id, auth.workspace.id);
  const existing = await loadWallet(auth);
  if (existing) throw new Error('Сначала завершите сохраненный импорт Wallet.');
  const orphan = await readCSVCommand<WalletProgress>(keys.progress);
  if (orphan) await compareAuxiliaryCommand(keys.progress, orphan.key, null);
  // No network request is allowed until the complete immutable plan is durable.
  await compareAuxiliaryCommand(keys.plan, null, plan);
  await loadWallet(auth);
}
export async function finishWallet(auth: Auth): Promise<void> {
  const saved = await loadWallet(auth);
  if (!saved || saved.progress.cursor !== saved.plan.commands.length) throw new Error('Импорт ещё не завершен.');
  const keys = storageKeys(auth.profile.id, auth.workspace.id);
  await closeWalletJournal(keys.plan, saved.plan.key, keys.progress, saved.progress.key);
}
export async function runWallet(auth: Auth, stop: () => boolean, updated: (value: WalletProgress) => void): Promise<void> {
  const saved = await loadWallet(auth);
  if (!saved) throw new Error('Нет сохраненного плана Wallet.');
  const { plan } = saved;
  let { progress } = saved;
  if (plan.generation !== auth.workspace.sync_generation_id) throw new Error('Данные пространства восстановлены или изменены. Продолжение старого импорта остановлено; требуется сверка уже записанных операций.');
  await checkLegacyWalletRecords(auth, plan);
  const keys = storageKeys(auth.profile.id, auth.workspace.id);
  while (progress.cursor < plan.commands.length && !stop()) {
    if (!navigator.onLine) throw new Error('Нет соединения. После подключения продолжите сохраненный импорт.');
    const command = plan.commands[progress.cursor];
    if (!['accounts', 'categories', 'transactions'].includes(command.path) || !/^[0-9a-f-]{36}$/.test(command.key)) throw new Error('Некорректная сохраненная команда Wallet.');
    await request(`/workspaces/${plan.workspace}/${command.path}`, { method: 'POST', body: command.body, signal: AbortSignal.timeout(30000), headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': auth.csrf_token, 'X-Sync-Generation': plan.generation, 'Idempotency-Key': command.key } });
    const next = { key: `${plan.digest}:${progress.cursor + 1}`, digest: plan.digest, cursor: progress.cursor + 1 };
    // If local acknowledgement fails, retain the previous cursor and replay
    // the exact same idempotency key/body on the next explicit continuation.
    await compareAuxiliaryCommand(keys.progress, progress.key, next);
    progress = next; updated(progress);
  }
}
