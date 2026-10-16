import { useEffect, useRef, useState } from 'react';
import { request, RequestError, type Auth, type Account, type Transaction, type List } from '../../api/client';
import { readCSVCommand as readCommand, compareAuxiliaryCommand, AuxiliaryCommandConflict } from '../imports/commandStorage';
import { money } from '../../shared/money';

type SourceEvent = { id: string; workspace_id: string; version: string; state: 'review'|'queued'|'failed'|'dismissed'|'applied'; account_id: string; kind: string; amount_minor: string; currency: string; observed_at: string; merchant: string; package_name: string; card_last_four: string|null; transaction_id: string|null; error_code: string|null };
type Decision = 'create_pending'|'link'|'dismiss'|'confirm_posted'|'cancel_pending';
type Choice = { event: SourceEvent; decision: Decision; transaction: Transaction|null };
type Command = { owner: string; session: string; workspace: string; generation: string; key: string; path: string; body: string; rejected: boolean };
const uncertain = new Set(['action_result_expired','idempotency_conflict','sync_generation_conflict']);
const titles: Record<Decision,string> = { create_pending:'Создать предварительную операцию', link:'Связать с выбранной операцией', dismiss:'Исключить предложение', confirm_posted:'Подтвердить предварительную операцию', cancel_pending:'Отменить предварительную операцию' };
function reason(code: string|null): string {
  return ({ unauthenticated:'Прежняя сессия завершена. Продолжение требует нового явного подтверждения.', version_conflict:'Операция изменилась. Обновите данные и сверьте текущие значения.', account_archived:'Счет в архиве. Проверьте его состояние.', recovery_required:'Данные восстановлены. Проверьте историю перед продолжением.', action_result_expired:'Квитанция истекла. Результат требует сверки; новое действие автоматически не создается.', idempotency_conflict:'Сохраненный запрос требует сверки. Новый ключ не создается.', sync_generation_conflict:'Поколение данных изменилось. Сохраненный запрос требует сверки.' } as Record<string,string>)[code ?? ''] ?? 'Обновите данные и проверьте предложение.';
}
export function SourcesPanel({ auth, accounts, changed, fail }: { auth: Auth; accounts: Account[]; changed: () => void; fail: (e: unknown) => void }) {
  const root=`/workspaces/${auth.workspace.id}/source-events`;
  const storage=`accounting.sources.pending.v1:${auth.profile.id}:${auth.workspace.id}:${auth.session.id}`;
  const active=useRef(true), running=useRef(false);
  const [before,setBefore]=useState<string|null>(null);
  const [items,setItems]=useState<SourceEvent[]>([]), [more,setMore]=useState(false), [busy,setBusy]=useState(false), [error,setError]=useState('');
  const [pending,setPending]=useState<Command|null>(null), [ready,setReady]=useState(false), [choice,setChoice]=useState<Choice|null>(null);
  const [targets,setTargets]=useState<Record<string,Transaction|null>>({});
  const [linking,setLinking]=useState<SourceEvent|null>(null), [candidates,setCandidates]=useState<Transaction[]>([]), [cursor,setCursor]=useState<string|null>(null);
  function show(e: unknown) { if(!active.current)return;setError(e instanceof AuxiliaryCommandConflict ? e.message : e instanceof RequestError ? reason(e.code) : 'Ответ не получен. Сохраненный запрос можно повторить с прежним ключом.');fail(e); }
  function valid(v: Command|null): v is Command { return !!v && v.owner===auth.profile.id && v.session===auth.session.id && v.workspace===auth.workspace.id && typeof v.body==='string' && typeof v.key==='string' && typeof v.generation==='string' && typeof v.rejected==='boolean' && new RegExp(`^${root}/[0-9a-f-]{36}/confirm$`).test(v.path); }
  async function refresh(older=false) {
    try {
      const page=await request<{items:SourceEvent[];has_more:boolean;next_before_id:string|null}>(root+(older&&before?`?limit=100&before_id=${encodeURIComponent(before)}`:'?limit=100'));
      if(!active.current)return;setItems(old=>older?[...old,...page.items.filter(i=>!old.some(o=>o.id===i.id))]:page.items);setMore(page.has_more);setBefore(page.next_before_id);if(!older)setTargets({});
      if(page.items.some(item=>item.state==='applied'))changed();
    }catch(e){show(e);}
  }
  useEffect(()=>{
    active.current=true;
    void (async()=>{const saved=await readCommand<Command>(storage);if(saved&&!valid(saved))throw new Error('Invalid saved source command');if(active.current){setPending(saved);setReady(true);}await refresh();})().catch(show);
    return()=>{active.current=false;};
  },[storage]);
  useEffect(()=>{const queued=items.filter(i=>i.state==='queued').slice(0,20);if(!queued.length)return;let cancelled=false;const timer=setTimeout(()=>void (async()=>{try{const values:SourceEvent[]=[];for(const item of queued)values.push(await request<SourceEvent>(`${root}/${item.id}`));if(cancelled||!active.current)return;setItems(old=>old.map(i=>values.find(v=>v.id===i.id&&BigInt(v.version)>=BigInt(i.version))??i));if(values.some(i=>i.state==='applied'))changed();}catch(e){if(!cancelled)show(e);}})(),2000);return()=>{cancelled=true;clearTimeout(timer);};},[items]);
  async function persist(value:Command|null,expectedKey:string|null) {
    try{await compareAuxiliaryCommand(storage,expectedKey,value);}catch(e){if(e instanceof AuxiliaryCommandConflict&&active.current){const latest=await readCommand<Command>(storage);if(latest===null||valid(latest))setPending(latest);else setReady(false);}throw e;}
    if(active.current)setPending(value);
  }
  async function execute(value:Command) {
    try {
      const result=await request<SourceEvent>(value.path,{method:'POST',body:value.body,headers:{'Content-Type':'application/json','X-CSRF-Token':auth.csrf_token,'Idempotency-Key':value.key,'X-Sync-Generation':value.generation}});
      if(result.workspace_id!==auth.workspace.id||result.id!==value.path.split('/').at(-2))throw new Error('Unexpected source receipt');
      if(!active.current)return;await persist(null,value.key);setTargets({});setItems(old=>old.map(i=>i.id===result.id?result:i));if(result.state==='applied')changed();
    }catch(e){if(e instanceof RequestError&&[400,404,409,422].includes(e.status)&&!uncertain.has(e.code)&&active.current){try{await persist({...value,rejected:true},value.key);}catch(storageError){show(storageError);return;}}show(e);}
  }
  async function submit(selected:Choice) {
    if(running.current||busy||pending||!ready)return;running.current=true;setBusy(true);setError('');setChoice(null);
    const value:Command={owner:auth.profile.id,session:auth.session.id,workspace:auth.workspace.id,generation:auth.workspace.sync_generation_id,key:crypto.randomUUID(),path:`${root}/${selected.event.id}/confirm`,body:JSON.stringify({expected_version:selected.event.version,decision:selected.decision,transaction_id:selected.transaction?.id??null,expected_transaction_version:selected.transaction?.version??null}),rejected:false};
    try{await persist(value,null);if(active.current)await execute(value);}catch(e){show(e);}finally{running.current=false;if(active.current)setBusy(false);}
  }
  async function retry() {if(!pending||pending.rejected||running.current)return;running.current=true;setBusy(true);setError('');try{await execute(pending);}finally{running.current=false;if(active.current)setBusy(false);}}
  async function target(event:SourceEvent) {
    if(!event.transaction_id)return;setBusy(true);
    try{const value=await request<Transaction>(`/workspaces/${auth.workspace.id}/transactions/${event.transaction_id}`);if(active.current)setTargets(old=>({...old,[event.id]:value}));}
    catch(e){if(e instanceof RequestError&&e.status===404){if(active.current)setTargets(old=>({...old,[event.id]:null}));}else show(e);}
    finally{if(active.current)setBusy(false);}
  }
  async function matches(event:SourceEvent,next:string|null=null) {
    setBusy(true);if(!next){setLinking(event);setCandidates([]);}setError('');
    try{
      const page=await request<List<Transaction>>(`/workspaces/${auth.workspace.id}/transactions?account_id=${event.account_id}&limit=100${next?`&cursor=${encodeURIComponent(next)}`:''}`);
      const allowed=page.items.filter(t=>['expense','income','transfer','refund'].includes(t.kind)&&t.entries.some(e=>e.account_id===event.account_id&&e.currency===event.currency&&BigInt(e.amount_minor)===(event.kind==='expense'?-1n:1n)*BigInt(event.amount_minor)));
      if(active.current){setCandidates(old=>next?[...old,...allowed]:allowed);setCursor(page.next_cursor);}
    }catch(e){show(e);}finally{if(active.current)setBusy(false);}
  }
  const actual=(t:Transaction)=>`${({expense:'Расход',income:'Доход',transfer:'Перевод',refund:'Возврат',adjustment:'Корректировка'} as Record<string,string>)[t.kind]??t.kind} · ${new Date(t.occurred_at).toLocaleString()} · ${t.entries.map(e=>`${accounts.find(a=>a.id===e.account_id)?.name??e.account_id}: ${money(e.amount_minor,e.currency)}`).join('; ')}`;
  const enabled=ready&&!busy&&!pending&&navigator.onLine;
  return <section aria-label="Банковские предложения">
    <h2>Банковские предложения</h2><p>Сюда поступают разобранные уведомления, отправленные с вашего Android. Полный текст не передается. Банковские API пока не подключены. Каждое финансовое действие требует подтверждения; одинаковые суммы сами по себе не объединяются.</p>
    <button disabled={busy} onClick={()=>void refresh()}>Обновить банковские предложения</button>
    {error&&<p role="alert">{error}</p>}
    {pending&&<div role="status"><p>Сохранен запрос банковского предложения. Новый запрос не создается до выяснения результата.</p>{pending.rejected?<button disabled={busy} onClick={()=>void persist(null,pending.key).catch(show)}>Закрыть отклоненный запрос предложения</button>:<button disabled={busy} onClick={()=>void retry()}>Повторить сохраненный запрос предложения</button>}</div>}
    {more&&<button disabled={busy||!before} onClick={()=>{setBusy(true);void refresh(true).finally(()=>{if(active.current)setBusy(false);});}}>Загрузить более ранние предложения</button>}
    {!items.length&&<p>Банковских предложений пока нет.</p>}
    {items.filter(event=>event.state!=='dismissed').map(event=>{
      const transaction=targets[event.id], unknown=uncertain.has(event.error_code??'');
      return <article key={event.id} aria-label={`Предложение ${event.merchant||'без продавца'}`}>
        <h3>{event.merchant||'Без продавца'} · {money(event.amount_minor,event.currency)}</h3>
        <p>{accounts.find(a=>a.id===event.account_id)?.name??'Счет недоступен'} · {event.kind==='expense'?'расход':'доход'} · {new Date(event.observed_at).toLocaleString()}{event.card_last_four?` · карта *${event.card_last_four}`:''}</p>
        <p>Источник: {event.package_name}. Время уведомления может отличаться от даты покупки.</p>
        {event.state==='queued'&&<p>Ожидает обработки</p>}
        {event.state==='failed'&&<p>Требуется проверка: {reason(event.error_code)}</p>}
        {(event.state==='review'||event.state==='failed'&&!event.transaction_id&&!unknown)&&<div><button disabled={!enabled} onClick={()=>setChoice({event,decision:'create_pending',transaction:null})}>Создать предварительную операцию</button><button disabled={!enabled} onClick={()=>void matches(event)}>Выбрать существующую операцию</button><button disabled={!enabled} onClick={()=>setChoice({event,decision:'dismiss',transaction:null})}>Исключить предложение</button></div>}
        {event.transaction_id&&<div><button disabled={busy} onClick={()=>void target(event)}>Проверить связанную операцию</button>{transaction===null&&<><p>Связанная операция удалена или недоступна. Новая операция автоматически не создается.</p><button disabled={!enabled} onClick={()=>void matches(event)}>Связать с другой существующей операцией</button></>}{transaction&&<div><p>{transaction.status==='pending'?'Предварительная операция':'Подтвержденная операция'}: {transaction.payee} · {transaction.note}</p><p>Текущая операция: {actual(transaction)}</p>{transaction.status==='pending'&&transaction.kind===event.kind&&transaction.parent_transaction_id===null&&transaction.entries.length===1&&(event.state==='applied'||event.state==='failed'&&!unknown)&&<><button disabled={!enabled} onClick={()=>setChoice({event,decision:'confirm_posted',transaction})}>Подтвердить предварительную операцию</button><button disabled={!enabled} onClick={()=>setChoice({event,decision:'cancel_pending',transaction})}>Отменить предварительную операцию</button></>}</div>}</div>}
      </article>;
    })}
    {linking&&<div role="dialog" aria-label="Выбор операции для связи"><p>Выберите существующую операцию на том же счете, с той же валютой и суммой. Это только связь: финансовая операция не изменится. Если прежняя предварительная запись уже отменена, её история сохранится, а предложение будет привязано к выбранной существующей записи.</p>{candidates.map(t=><button disabled={!enabled} key={t.id} onClick={()=>{setChoice({event:linking,decision:'link',transaction:t});setLinking(null);}}>{new Date(t.occurred_at).toLocaleString()} · {t.payee||'Без получателя'} · {actual(t)}</button>)}{!candidates.length&&<p>Среди загруженных операций подходящих нет.</p>}{cursor&&<button disabled={busy} onClick={()=>void matches(linking,cursor)}>Загрузить следующие операции</button>}<button disabled={busy} onClick={()=>setLinking(null)}>Закрыть выбор операции</button></div>}
    {choice&&<div role="dialog" aria-label="Подтверждение банковского предложения"><h3>{titles[choice.decision]}</h3><p>Исходное уведомление: {money(choice.event.amount_minor,choice.event.currency)} · {choice.event.merchant}</p>{choice.transaction&&<p>Действие применяется к текущей операции: {actual(choice.transaction)}. Ручные исправления суммы и счёта сохранятся.</p>}<p>{choice.decision==='create_pending'?'Сумма попадет в предварительный остаток и отчеты. Проверьте, нет ли уже этой операции.':choice.decision==='confirm_posted'?'Статус изменится без повторного учета суммы. Ваши категории, теги и заметки сохранятся.':choice.decision==='cancel_pending'?'Предварительная операция будет удалена обычной финансовой командой.':choice.decision==='link'?'Будет сохранена связь с выбранной операцией. Ее сумма и пользовательские поля сохранятся.':'Предложение будет исключено; защита от повторного события сохранится.'}</p><button disabled={!enabled} onClick={()=>void submit(choice)}>Подтвердить действие предложения</button><button disabled={busy} onClick={()=>setChoice(null)}>Отмена действия предложения</button></div>}
  </section>;
}
