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
      request.onsuccess = () => { const cursor = request.result; if (!cursor) return; if ([`accounting.csv.pending.v1:${owner}:`,`accounting.sources.pending.v1:${owner}:`,`accounting.bank-rules.v1:${owner}:`,`accounting.bank-intake.pending.v1:${owner}:`,`accounting.wallet.pending.v1:${owner}:`].some(prefix=>String(cursor.key).startsWith(prefix))) cursor.delete(); cursor.continue(); };
      tx.oncomplete = () => resolve(); tx.onabort = () => reject(new Error('Не удалось очистить локальное хранилище CSV.'));
    });
  } finally { db.close(); }
}

export class AuxiliaryCommandConflict extends Error {
  constructor() { super('Запрос изменился в другой вкладке. Обновите данные и повторите проверку.'); }
}
/** Atomically claim/finish one auxiliary request. A stale tab cannot overwrite another unknown request. */
export async function compareAuxiliaryCommand(key: string, expectedKey: string|null, value: object|null): Promise<void> {
  const db=await database();
  try {
    await new Promise<void>((resolve,reject)=>{
      const tx=db.transaction('commands','readwrite'),store=tx.objectStore('commands'),lookup=store.get(key);
      let conflict=false;
      lookup.onsuccess=()=>{
        const current=lookup.result as {key?:unknown}|undefined;
        if(expectedKey===null ? current!==undefined : !current||current.key!==expectedKey){conflict=true;tx.abort();return;}
        try{value===null?store.delete(key):store.put(value,key);}catch{tx.abort();}
      };
      tx.oncomplete=()=>resolve();
      tx.onabort=()=>reject(conflict?new AuxiliaryCommandConflict():new Error('Не удалось сохранить запрос. Проверьте доступное место.'));
    });
  }finally{db.close();}
}

/** Close a completed Wallet journal atomically, including its progress record. */
export async function closeWalletJournal(planKey: string, digest: string, progressKey: string, expectedProgress: string): Promise<void> {
  const db = await database();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction('commands', 'readwrite'), store = tx.objectStore('commands');
      const plan = store.get(planKey), progress = store.get(progressKey);
      let checked = 0, conflict = false;
      const check = () => {
        if (++checked !== 2) return;
        if (plan.result?.key !== digest || progress.result?.key !== expectedProgress || progress.result?.cursor !== plan.result?.commands?.length) { conflict = true; tx.abort(); return; }
        store.delete(planKey); store.delete(progressKey);
      };
      plan.onsuccess = check; progress.onsuccess = check;
      tx.oncomplete = () => resolve();
      tx.onabort = () => reject(conflict ? new AuxiliaryCommandConflict() : new Error('Не удалось закрыть журнал Wallet.'));
    });
  } finally { db.close(); }
}
