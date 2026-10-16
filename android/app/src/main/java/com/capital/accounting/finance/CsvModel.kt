package com.capital.accounting.finance

import android.app.Application
import android.util.Base64
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capital.accounting.CapitalApplication
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

class CsvModel(application: Application): AndroidViewModel(application){
    private val app get()=getApplication<CapitalApplication>()
    private val store by lazy{PlanningStore(app, "csv")}
    private var session:StoredSession?=null
    var unauthorized by mutableStateOf(false);private set
    var preview by mutableStateOf<ImportPreview?>(null);private set
    var pending by mutableStateOf<PlanningPending?>(null);private set
    var busy by mutableStateOf(false);private set
    var message by mutableStateOf("");private set
    var decisions by mutableStateOf<List<ImportDecision>>(emptyList());private set
    var match by mutableStateOf<Transaction?>(null);private set
    var export by mutableStateOf<ByteArray?>(null);private set
    private fun key(s:StoredSession)=MessageDigest.getInstance("SHA-256").digest("${s.origin}:${s.ownerId}:${s.workspaceId}:${s.sessionId}:${s.generationId}".toByteArray()).joinToString(""){"%02x".format(it)}
    fun bind(s:StoredSession){if(session?.let(::key)==key(s)){session=s;return};clear();session=s;refresh()}
    fun clear(){session=null;unauthorized=false;preview=null;pending=null;busy=false;message="";decisions=emptyList();match=null;export=null}
    private fun accept(s:StoredSession,p:ImportPreview){
        if(session?.let(::key)!=key(s))return
        require(p.workspace_id==s.workspaceId);preview=p
        if(p.state=="ready")decisions=p.rows.map{ImportDecision(it.row_number,if(it.status=="valid")"create" else "skip",null,null)}
        app.getSharedPreferences("csv_jobs",0).edit().putString(csvJobKey(s),p.id).apply()
    }
    private fun work(block:suspend(StoredSession)->Unit){
        val s=session?:return;if(busy)return;busy=true;message=""
        viewModelScope.launch{try{block(s)}catch(e:CancellationException){throw e}catch(e:Exception){if(session?.let(::key)==key(s)){message=if(e is ApiFailure)"${csvReason(e.code)}${e.requestReference}" else "Проверьте файл, поля и соединение.";if(e is ApiFailure&&e.status==401)unauthorized=true;if(e is ApiFailure&&e.status in setOf(401,403,404)){preview=null;match=null;export=null};runCatching{pending=store.pending(s)}}}finally{if(session?.let(::key)==key(s))busy=false}}
    }
    private suspend fun load(s:StoredSession,id:String){
        val p=decodeResponse<ImportPreview>(ApiClient(s.origin).request("/workspaces/${s.workspaceId}/imports/$id",session=s));accept(s,p)
    }
    fun refresh()=work{s->val saved=store.pending(s);if(session?.let(::key)!=key(s))return@work;pending=saved;val id=preview?.id?:app.getSharedPreferences("csv_jobs",0).getString(csvJobKey(s),null);if(id!=null)load(s,id)}
    private suspend fun submit(s:StoredSession,path:String,body:String){
        val command=store.enqueue(s,path,"POST",body);if(session?.let(::key)!=key(s))return;pending=command
        val result=decodeResponse<ImportPreview>(store.send(s));if(session?.let(::key)!=key(s))return;pending=null;accept(s,result)
    }
    fun upload(content:String,mapping:ImportMapping)=work{s->submit(s,"/workspaces/${s.workspaceId}/imports",ApiClient.json.encodeToString(ImportCreate(UUID.randomUUID().toString(),"csv",content,mapping)))}
    fun confirm()=work{s->val p=requireNotNull(preview);submit(s,"/workspaces/${s.workspaceId}/imports/${p.id}/confirm",ApiClient.json.encodeToString(ImportConfirm(p.version,if(p.state=="failed")emptyList() else decisions)))}
    fun retry()=work{s->val p=decodeResponse<ImportPreview>(store.send(s));if(session?.let(::key)==key(s)){pending=null;accept(s,p)}}
    fun dismiss()=work{s->store.dismissRejected(s);pending=null}
    fun decision(row:Int,action:String){decisions=decisions.map{if(it.row_number==row)ImportDecision(row,action,null,null)else it}}
    fun link(row:Int,id:String)=work{s->val t=decodeResponse<Transaction>(ApiClient(s.origin).request("/workspaces/${s.workspaceId}/transactions/$id",session=s));if(session?.let(::key)==key(s)){match=t;decisions=decisions.map{if(it.row_number==row)ImportDecision(row,"link",t.id,t.version)else it}}}
    fun download(from:String,to:String,account:String)=work{s->
        val q="from=${URLEncoder.encode(from,"UTF-8")}&to=${URLEncoder.encode(to,"UTF-8")}&account_id=$account"
        val result=decodeResponse<ExportResult>(ApiClient(s.origin).request("/workspaces/${s.workspaceId}/exports?$q",session=s,maxResponseBytes=16*1024*1024))
        if(session?.let(::key)==key(s))export=Base64.decode(result.content_base64,Base64.DEFAULT)
    }
    fun clearExport(){export=null}
}

internal fun csvReason(code: String): String = when(code) {
    "unauthenticated" -> "Сессия завершена. Войдите снова и явно продолжите импорт."
    "not_found" -> "Счет или пространство больше недоступны."
    "account_archived" -> "Счет находится в архиве."
    "sync_generation_conflict" -> "Состояние данных изменилось после восстановления. Проверьте оставшиеся строки."
    "amount_out_of_range" -> "Сумма выходит за поддерживаемый диапазон."
    else -> "Проверьте файл, строки и актуальные данные счета."
}

internal fun csvJobKey(s: StoredSession): String = MessageDigest.getInstance("SHA-256").digest("${s.origin}:${s.ownerId}:${s.workspaceId}".toByteArray()).joinToString("") { "%02x".format(it) }
