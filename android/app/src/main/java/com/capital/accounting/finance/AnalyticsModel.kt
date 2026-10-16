package com.capital.accounting.finance

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capital.accounting.CapitalApplication
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import kotlinx.coroutines.launch
import java.net.URLEncoder

class AnalyticsModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<CapitalApplication>()
    private val store by lazy { PlanningStore(app) }
    private var session: StoredSession? = null
    var unauthorized by mutableStateOf(false); private set
    var budgets by mutableStateOf<List<BudgetView>>(emptyList()); private set
    var rules by mutableStateOf<List<Rule>>(emptyList()); private set
    var report by mutableStateOf<Report?>(null); private set
    var preview by mutableStateOf<RulePreview?>(null); private set
    var pending by mutableStateOf<PlanningPending?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    private fun scope(s: StoredSession) = "${s.origin}:${s.ownerId}:${s.workspaceId}:${s.sessionId}:${s.generationId}"
    fun bind(s: StoredSession) {
        if (session?.let(::scope) == scope(s)) { session = s; return }
        session = s; unauthorized = false; budgets = emptyList(); rules = emptyList(); report = null; preview = null; pending = null; message = ""; busy = false
    }
    fun clear() { session = null; unauthorized = false; budgets = emptyList(); rules = emptyList(); report = null; preview = null; pending = null; message = ""; busy = false }
    private fun work(block: suspend (StoredSession) -> Unit) {
        val saved = session ?: return
        if (busy) return
        busy = true; message = ""
        viewModelScope.launch {
            try { block(saved) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (session?.let(::scope) != scope(saved)) return@launch
                if (e is ApiFailure && e.status == 401) unauthorized = true
                if (e is ApiFailure && e.status in setOf(401, 403, 404)) { budgets = emptyList(); rules = emptyList(); report = null; preview = null }
                message = if (e is ApiFailure) "Не удалось выполнить запрос. Обновите данные и проверьте значения.${e.requestReference}" else "Не удалось загрузить или сохранить данные. Проверьте соединение и значения полей."
                runCatching { pending = store.pending(saved) }
            } finally { if (session?.let(::scope) == scope(saved)) busy = false }
        }
    }
    private suspend fun refresh(s: StoredSession, from: String, to: String, filter: String) {
        val api = ApiClient(s.origin); val root = "/workspaces/${s.workspaceId}"
        val b = decodeResponse<BudgetList>(api.request("$root/budgets", session = s))
        val r = decodeResponse<RuleList>(api.request("$root/rules", session = s))
        val a = decodeResponse<Report>(api.request("$root/reports?from=${URLEncoder.encode(from,"UTF-8")}&to=${URLEncoder.encode(to,"UTF-8")}$filter", session = s))
        val p = store.pending(s)
        if (session?.let(::scope) != scope(s)) return
        budgets = b.items; rules = r.items; report = a; pending = p; preview = null
    }
    fun load(from: String, to: String, filter: String = "") = work { refresh(it, from, to, filter) }
    fun save(resource: String, method: String, body: String, from: String, to: String, filter: String) = work {
        val value = store.enqueue(it, "/workspaces/${it.workspaceId}/$resource", method, body)
        if (session?.let(::scope) != scope(it)) return@work
        pending = value; store.send(it); refresh(it, from, to, filter)
    }
    fun retry(from: String, to: String, filter: String) = work { store.send(it); refresh(it, from, to, filter) }
    fun dismiss(from: String, to: String, filter: String) = work { store.dismissRejected(it); refresh(it, from, to, filter) }
    fun preview(from: String, to: String) = work {
        val value = decodeResponse<RulePreview>(ApiClient(it.origin).request("/workspaces/${it.workspaceId}/rules/preview?from=${URLEncoder.encode(from,"UTF-8")}&to=${URLEncoder.encode(to,"UTF-8")}", session = it))
        if (session?.let(::scope) == scope(it)) preview = value
    }
}
