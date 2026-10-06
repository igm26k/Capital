export class RequestError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (!path.startsWith('/') || path.startsWith('//')) throw new Error('Invalid API path');
  const response = await fetch(`/api/v1${path}`, { ...init, credentials: 'include', cache: 'no-store' });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new RequestError(response.status, error?.code ?? 'http_error', error?.message ?? `HTTP ${response.status}`);
  }
  return response.status === 204 ? undefined as T : await response.json() as T;
}
export type Auth = { profile: { id: string; email: string }; session: { id: string }; workspace: Workspace; csrf_token: string; transport: 'cookie' };
export type Workspace = { id: string; name: string; sync_generation_id: string };
export type Account = { id: string; name: string; currency: string; posted_balance_minor: string };
export type Category = { id: string; name: string; parent_id: string | null; version: string; archived_at: string | null };
export type Tag = { id: string; name: string; version: string; archived_at: string | null };
export async function listAll<T>(path: string): Promise<T[]> {
  const items: T[] = [], seen = new Set<string>();
  let cursor: string | null = null;
  do {
    const page: List<T> = await request(`${path}${path.includes('?') ? '&' : '?'}limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`);
    items.push(...page.items); cursor = page.next_cursor;
    if (cursor && seen.has(cursor)) throw new Error('Не удалось загрузить полный справочник. Обновите данные.');
    if (cursor) seen.add(cursor);
  } while (cursor);
  return items;
}
export type Transaction = { id: string; kind: string; note: string; payee: string; allocations: { id: string; category_id: string | null; amount_minor: string }[]; tag_ids: string[]; entries: { account_id: string; amount_minor: string; currency: string }[] };
export type List<T> = { items: T[]; next_cursor: string | null };
