// Large CSV requests need durable storage before sending; no credentials are stored.
const databaseName = 'accounting-csv-commands-v1';
async function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open(databaseName, 1);
    open.onupgradeneeded = () => { open.result.createObjectStore('commands'); };
    open.onerror = () => reject(new Error('Не удалось открыть локальное хранилище запросов CSV.'));
    open.onblocked = () => reject(new Error('Локальное хранилище CSV занято другой вкладкой.'));
    open.onsuccess = () => resolve(open.result);
  });
}
async function transact<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest): Promise<T | undefined> {
  const db = await database();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction('commands', mode);
      let value: T | undefined;
      const request = action(tx.objectStore('commands'));
      request.onsuccess = () => { value = request.result as T | undefined; };
      tx.oncomplete = () => resolve(value);
      tx.onabort = () => reject(new Error('Не удалось обновить локальное состояние запроса CSV. Проверьте доступное место и повторите действие.'));
      tx.onerror = () => { /* onabort handles the final transaction result. */ };
    });
  } finally { db.close(); }
}
export async function readCSVCommand<T>(key: string): Promise<T | null> { return await transact<T>('readonly', store => store.get(key)) ?? null; }
export async function writeCSVCommand(key: string, value: object | null): Promise<void> { await transact('readwrite', store => value === null ? store.delete(key) : store.put(value, key)); }
export async function clearCSVCommandsForOwner(owner: string): Promise<void> {
  const db = await database();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction('commands', 'readwrite'), request = tx.objectStore('commands').openCursor();
      request.onsuccess = () => { const cursor = request.result; if (!cursor) return; if (String(cursor.key).startsWith(`accounting.csv.pending.v1:${owner}:`)) cursor.delete(); cursor.continue(); };
      tx.oncomplete = () => resolve(); tx.onabort = () => reject(new Error('Не удалось очистить локальное хранилище CSV.'));
    });
  } finally { db.close(); }
}
