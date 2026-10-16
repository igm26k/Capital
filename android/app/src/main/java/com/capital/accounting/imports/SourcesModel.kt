package com.capital.accounting.imports

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capital.accounting.CapitalApplication
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.finance.PlanningPending
import com.capital.accounting.finance.PlanningStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

class SourcesModel(application:Application):AndroidViewModel(application) {
    private val app get()=getApplication<CapitalApplication>()
    private val store by lazy {PlanningStore(app,"sources")}
    private var session:StoredSession?=null
    var items by mutableStateOf<List<SourceEvent>>(emptyList());private set
    var hasMore by mutableStateOf(false);private set
    var before by mutableStateOf<String?>(null);private set
    var linking by mutableStateOf<SourceEvent?>(null);private set
    var candidates by mutableStateOf<List<Transaction>>(emptyList());private set
    var candidateCursor by mutableStateOf<String?>(null);private set
    var pending by mutableStateOf<PlanningPending?>(null);private set
    var busy by mutableStateOf(false);private set
    var message by mutableStateOf("");private set
    var unauthorized by mutableStateOf(false);private set
    var targets by mutableStateOf<Map<String,Transaction?>>(emptyMap());private set
    var delivered by mutableStateOf<Triple<NotificationBinding,String,String>?>(null);private set
    private fun same(s:StoredSession)=session?.let {it.notificationBinding()==s.notificationBinding()&&it.token==s.token}==true
    fun bind(s:StoredSession) {
        if(same(s)){session=s;return};session=s;items=emptyList();hasMore=false;before=null;pending=null;busy=false;message="";targets=emptyMap();delivered=null;unauthorized=false;linking=null;candidates=emptyList();candidateCursor=null
    }
    fun clear(){session=null;items=emptyList();hasMore=false;before=null;pending=null;busy=false;message="";targets=emptyMap();delivered=null;unauthorized=false;linking=null;candidates=emptyList();candidateCursor=null}
    private fun work(block:suspend(StoredSession)->Unit) {
        val saved=session?:return;if(busy)return;busy=true;message=""
        viewModelScope.launch {
            try {block(saved)}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(same(saved)) {unauthorized=e is ApiFailure&&e.status==401;message=if(e is ApiFailure)sourceReason(e.code) else "Ответ не получен. Сохраненный запрос можно повторить с прежним ключом.";runCatching {val value=store.pending(saved);if(same(saved))pending=value}}}
            finally {if(same(saved))busy=false}
        }
    }
    private suspend fun refresh(saved:StoredSession,older:Boolean=false) {
        val suffix=if(older)"?limit=100&before_id="+requireNotNull(before) else "?limit=100"
        val result=decodeResponse<SourceEventList>(ApiClient(saved.origin).request("/workspaces/${saved.workspaceId}/source-events$suffix",session=saved))
        require(result.items.all {it.workspace_id==saved.workspaceId})
        if(same(saved)){items=if(older)(items+result.items).distinctBy {it.id} else result.items;hasMore=result.has_more;before=result.next_before_id;if(!older)targets=emptyMap()}
    }
    fun load()=work {s->val value=store.pending(s);if(same(s))pending=value;refresh(s)}
    fun loadOlder()=work {s->require(hasMore&&before!=null);refresh(s,true)}
    fun poll()=work {s->
        val values=items.filter {it.state=="queued"}.take(20).map {event->decodeResponse<SourceEvent>(ApiClient(s.origin).request("/workspaces/${s.workspaceId}/source-events/${event.id}",session=s)).also {require(it.workspace_id==s.workspaceId&&it.id==event.id)}}
        if(same(s))items=items.map {old->values.find {it.id==old.id&&java.math.BigInteger(it.version)>=java.math.BigInteger(old.version)}?:old}
    }
    private suspend fun send(saved:StoredSession) {
        val command=requireNotNull(store.pending(saved))
        if(same(saved))pending=command
        val result=decodeResponse<SourceEvent>(store.send(saved))
        if(command.path.endsWith("/source-events")) {
            val input=decodeResponse<SourceEventCreate>(command.body)
            runCatching {app.notificationInbox.uploaded(saved.notificationBinding(),input.external_id,result.id)}
            if(same(saved))delivered=Triple(saved.notificationBinding(),input.external_id,result.id)
        }
        if(!same(saved))return
        pending=null
        items=if(items.any {it.id==result.id})items.map {if(it.id==result.id)result else it} else listOf(result)+items
        targets=emptyMap()
        if(same(saved))message="Сохранено. Финансовые задания обрабатываются сервером; обновите данные после обработки."
    }
    fun upload(binding:NotificationBinding,candidate:NotificationCandidate)=work {s->
        require(s.notificationBinding()==binding && store.pending(s)==null)
        val body=ApiClient.json.encodeToString(sourceEnvelope(binding,candidate))
        store.enqueue(s,"/workspaces/${s.workspaceId}/source-events","POST",body)
        send(s)
    }
    fun decide(event:SourceEvent,decision:String,transaction:Transaction?)=work {s->
        require(event.workspace_id==s.workspaceId && store.pending(s)==null)
        val input=SourceEventDecision(event.version,decision,transaction?.id,transaction?.version)
        store.enqueue(s,"/workspaces/${s.workspaceId}/source-events/${event.id}/confirm","POST",ApiClient.json.encodeToString(input));send(s)
    }
    fun retry()=work {send(it)}
    fun dismissRejected()=work {s->store.dismissRejected(s);if(same(s))pending=null}
    fun closeMatches(){linking=null;candidates=emptyList();candidateCursor=null}
    fun matches(event:SourceEvent,more:Boolean=false)=work {s->
        require(event.workspace_id==s.workspaceId)
        val cursor=if(more)candidateCursor else null
        if(!more&&same(s)){linking=event;candidates=emptyList();candidateCursor=null}
        val suffix=cursor?.let {"&cursor="+java.net.URLEncoder.encode(it,"UTF-8")}?:""
        val page=decodeResponse<TransactionList>(ApiClient(s.origin).request("/workspaces/${s.workspaceId}/transactions?account_id=${event.account_id}&limit=100$suffix",session=s))
        require(page.items.all {it.workspace_id==s.workspaceId})
        val allowed=page.items.filter {t->t.deleted_at==null&&t.kind in setOf("expense","income","transfer","refund")&&t.entries.any {e->e.account_id==event.account_id&&e.currency==event.currency&&java.math.BigInteger(e.amount_minor)==java.math.BigInteger(event.amount_minor).multiply(java.math.BigInteger.valueOf(if(event.kind=="expense")-1L else 1L))}}
        if(same(s)){candidates=(if(more)candidates+allowed else allowed).distinctBy {it.id};candidateCursor=page.next_cursor}
    }
    fun target(event:SourceEvent)=work {s->
        require(event.workspace_id==s.workspaceId)
        val id=event.transaction_id?:return@work
        try {val value=decodeResponse<Transaction>(ApiClient(s.origin).request("/workspaces/${s.workspaceId}/transactions/$id",session=s));if(same(s))targets=targets+(event.id to value)}
        catch(e:ApiFailure){if(e.status!=404)throw e;if(same(s))targets=targets+(event.id to null)}
    }
}
fun sourceReason(code:String?):String=when(code){
    "unauthenticated"->"Прежняя сессия завершена. Продолжение требует нового явного подтверждения."
    "version_conflict"->"Операция изменилась. Обновите предложения и проверьте связанную операцию."
    "recovery_required"->"Данные восстановлены. Проверьте историю перед продолжением."
    "account_archived"->"Счет в архиве. Проверьте его состояние."
    "action_result_expired","idempotency_conflict","sync_generation_conflict"->"Исходный результат требует сверки. Новый ключ не создается."
    else->"Обновите данные и проверьте предложение."
}
