package com.capital.accounting.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.capital.accounting.CapitalApplication
import com.capital.accounting.api.*
import com.capital.accounting.data.ConnectionSettings
import com.capital.accounting.data.FinancialCommand
import com.capital.accounting.finance.CommandRunner
import com.capital.accounting.normalizedOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.TimeZone

data class AuthState(
    val origin: String = "", val busy: Boolean = true, val message: String = "",
    val auth: BearerAuth? = null, val restorePending: Boolean = false,
    val logoutPending: Boolean = false, val persisted: Boolean = true,
    val sessions: List<Session> = emptyList(), val revokePendingId: String? = null,
    val accounts: List<Account> = emptyList(), val transactions: List<Transaction> = emptyList(), val financialCommand: FinancialCommand? = null,
    val financeBlocked: Boolean = true, val accountConflict: Account? = null, val transactionConflict: Transaction? = null,

)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CapitalApplication
    var state by mutableStateOf(AuthState())
        private set
    private var candidate: StoredSession? = null
    init { restoreInternal() }

    fun saveOrigin(value: String) {
        if (state.busy || state.auth != null || state.logoutPending || state.revokePendingId != null) return
        viewModelScope.launch {
            state = AuthState(state.origin)
            try {
                val origin = normalizedOrigin(value)
                app.database.settings().save(ConnectionSettings(origin = origin))
                candidate = null
                state = AuthState(origin, busy = false, message = "Адрес сохранен")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = AuthState(state.origin, busy = false, message = "Не удалось сохранить HTTPS-адрес сервера.") }
        }
    }

    fun authenticate(email: String, password: String, device: String, register: Boolean) {
        if (state.busy || state.auth != null || state.logoutPending || state.revokePendingId != null || state.origin.isEmpty()) return
        val origin = state.origin
        state = AuthState(origin)
        viewModelScope.launch {
            try {
                val body = if (register) ApiClient.json.encodeToString(Register(email, password, device, "bearer", TimeZone.getDefault().id))
                    else ApiClient.json.encodeToString(Login(email, password, device, "bearer"))
                val result = decodeResponse<BearerAuth>(ApiClient(origin).request(if (register) "/auth/register" else "/auth/login", "POST", body))
                accept(result, origin)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = AuthState(origin, busy = false, message = errorMessage(e, authAttempt = true)) }
        }
    }

    fun restore() {
        if (state.busy || state.auth != null || state.logoutPending || state.revokePendingId != null) return
        restoreInternal()
    }

    private fun restoreInternal() {
        state = AuthState(state.origin)
        viewModelScope.launch {
            var origin = state.origin
            try {
                if (origin.isEmpty()) origin = app.database.settings().get()?.origin ?: ""
                if (origin.isEmpty()) { state = AuthState(busy = false); return@launch }
                when (val saved = app.credentialVault.load(origin)) {
                    is VaultRead.Available -> {
                        candidate = saved.session
                        if (saved.session.revokeSessionId != null) {
                            state = AuthState(origin, busy = false, message = "Отзыв устройства еще не подтвержден. Повторите отзыв.", revokePendingId = saved.session.revokeSessionId)
                        } else if (saved.session.logoutPending) {
                            state = AuthState(origin, busy = false, message = "Выход еще не подтвержден. Повторите выход.", logoutPending = true)
                        } else refreshSaved(saved.session)
                    }
                    VaultRead.Invalid -> state = AuthState(origin, busy = false, message = "Сохраненная сессия недоступна. Войдите снова.")
                    else -> { candidate = null; state = AuthState(origin, busy = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = AuthState(origin, busy = false, message = errorMessage(e), restorePending = candidate != null) }
        }
    }

    private suspend fun refreshSaved(saved: StoredSession, renew: Boolean = false, holdBusy: Boolean = false) {
        try {
            val result = decodeResponse<BearerAuth>(ApiClient(saved.origin).request(if (renew) "/auth/renew" else "/auth/session", if (renew) "POST" else "GET", session = saved))
            require(result.profile.id == saved.ownerId && result.session.id == saved.sessionId && result.access_token == saved.token && result.workspace.id == saved.workspaceId)
            accept(result, saved.origin, holdBusy)
        } catch (e: ApiFailure) {
            if (e.status != 401) throw e
            app.credentialVault.clear()
            candidate = null
            state = AuthState(saved.origin, busy = false, message = "Сессия завершена. Войдите снова.")
        }
    }

    private suspend fun accept(auth: BearerAuth, origin: String, holdBusy: Boolean = false) {
        val saved = auth.credential(origin)
        candidate = saved
        try {
            app.credentialVault.save(saved)
            val command = app.database.commands().get(saved.origin, saved.ownerId, saved.workspaceId)
            state = AuthState(origin, busy = holdBusy, auth = auth, financialCommand = command, financeBlocked = command != null)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state = AuthState(origin, busy = holdBusy, auth = auth, persisted = false, message = "Вход выполнен, но сессия не сохранена. Повторите сохранение.") }
    }

    fun persist() {
        val auth = state.auth ?: return
        if (state.busy || state.logoutPending || state.revokePendingId != null) return
        state = AuthState(state.origin, auth = auth)
        viewModelScope.launch { accept(auth, state.origin) }
    }

    fun renew() {
        val saved = candidate ?: return
        if (state.busy || state.financeBlocked || state.logoutPending || state.revokePendingId != null) return
        val previous = state
        state = AuthState(saved.origin, auth = previous.auth, persisted = previous.persisted)
        viewModelScope.launch {
            try { refreshSaved(saved, renew = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = AuthState(saved.origin, busy = false, auth = previous.auth, persisted = previous.persisted, message = errorMessage(e), restorePending = previous.auth == null) }
        }
    }

    fun loadSessions() {
        val saved = candidate ?: return
        if (state.busy || state.auth == null || state.logoutPending || state.revokePendingId != null || !state.persisted) return
        val previous = state
        state = previous.copy(busy = true, message = "")
        viewModelScope.launch {
            try { state = previous.copy(busy = false, sessions = ApiClient(saved.origin).sessions(saved)) }
            catch (e: CancellationException) { throw e }
            catch (e: ApiFailure) {
                if (e.status == 401) expireSession(saved.origin)
                else state = previous.copy(busy = false, message = errorMessage(e))
            }
            catch (e: Exception) { state = previous.copy(busy = false, message = errorMessage(e)) }
        }
    }

    private suspend fun expireSession(origin: String, message: String = "Сессия завершена. Войдите снова.") {
        app.credentialVault.clear()
        candidate = null
        state = AuthState(origin, busy = false, message = message)
    }

    fun revokeSession(id: String) {
        val saved = candidate ?: return
        if (state.busy || (state.financeBlocked && state.revokePendingId == null) || state.logoutPending || !state.persisted) return
        if (saved.revokeSessionId != null && saved.revokeSessionId != id) return
        if (saved.revokeSessionId == null && state.sessions.none { it.id == id }) return
        val previous = state
        state = previous.copy(busy = true, revokePendingId = id, message = "")
        viewModelScope.launch {
            var intentCleared = false
            try {
                val pending = StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, saved.generationId, saved.sessionId, revokeSessionId = id)
                app.credentialVault.save(pending)
                candidate = pending
                try {
                    val result = decodeResponse<Acknowledgement>(ApiClient(saved.origin).request("/sessions/$id", "DELETE", session = pending))
                    require(result.ok)
                } catch (e: ApiFailure) {
                    if (e.status == 401) {
                        expireSession(saved.origin, if (id == saved.sessionId) "Вы вышли из аккаунта" else "Сессия завершена. Войдите снова и проверьте список устройств: отзыв не подтвержден.")
                        return@launch
                    }
                    if (e.status in 400..499 && e.status !in listOf(408, 425, 429)) {
                        val cleared = StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, saved.generationId, saved.sessionId)
                        app.credentialVault.save(cleared)
                        candidate = cleared
                        state = previous.copy(busy = false, revokePendingId = null, restorePending = previous.auth == null, message = "Сервер отклонил отзыв. Обновите список устройств.")
                        return@launch
                    }
                    throw e
                }
                if (id == saved.sessionId) {
                    expireSession(saved.origin, "Вы вышли из аккаунта")
                } else {
                    val cleared = StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, saved.generationId, saved.sessionId)
                    app.credentialVault.save(cleared)
                    candidate = cleared
                    intentCleared = true
                    refreshSaved(cleared, holdBusy = true)
                    if (state.auth != null) state = state.copy(busy = false, sessions = ApiClient(saved.origin).sessions(cleared), message = "Устройство отключено")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                state = if (intentCleared) previous.copy(busy = false, revokePendingId = null,
                    sessions = previous.sessions.filterNot { it.id == id }, restorePending = previous.auth == null,
                    message = "Устройство отключено. Не удалось обновить список; повторите проверку.")
                else previous.copy(busy = false, revokePendingId = id, message = "Отзыв устройства еще не подтвержден. Повторите отзыв.")
            }
        }
    }

    fun loadAccounts() {
        val saved = candidate ?: return
        if (state.busy || state.auth == null || !state.persisted || state.logoutPending || state.revokePendingId != null) return
        val previous = state
        state = previous.copy(busy = true)
        viewModelScope.launch {
            try {
                val command = app.database.commands().get(saved.origin, saved.ownerId, saved.workspaceId)
                val accounts = ApiClient(saved.origin).accounts(saved)
                state = previous.copy(busy = false, accounts = accounts, financialCommand = command, financeBlocked = command != null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = previous.copy(busy = false, message = "Не удалось загрузить счета. Повторите обновление.") }
        }
    }

    fun loadTransactions() {
        val saved = candidate ?: return
        if (state.busy || state.auth == null || !state.persisted || state.logoutPending || state.revokePendingId != null) return
        val previous = state
        state = state.copy(busy = true)
        viewModelScope.launch {
            try { state = previous.copy(busy = false, transactions = ApiClient(saved.origin).transactions(saved)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = previous.copy(busy = false, message = "Не удалось загрузить историю. Повторите обновление.") }
        }
    }

    fun submitAccount(body: String, id: String? = null) = submitFinancial(body, if (id == null) "accounts" else "accounts/$id", if (id == null) "POST" else "PUT")
    fun submitTransaction(body: String, id: String? = null, delete: Boolean = false) = submitFinancial(body, if (id == null) "transactions" else "transactions/$id", if (delete) "DELETE" else if (id == null) "POST" else "PUT")

    private fun submitFinancial(body: String, suffix: String, method: String) {
        val saved = candidate ?: return
        if (state.busy || state.auth == null || !state.persisted || state.financeBlocked || state.logoutPending || state.revokePendingId != null) return
        state = state.copy(busy = true, financeBlocked = true, message = "", accountConflict = null, transactionConflict = null)
        viewModelScope.launch {
            try {
                val command = CommandRunner(app.database.commands()).prepare(saved, suffix, method, body)
                state = state.copy(financialCommand = command)
                sendAccountCommand(saved)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (financialFailure(saved, e)) return@launch
                val pending = try { app.database.commands().get(saved.origin, saved.ownerId, saved.workspaceId) }
                catch (_: Exception) { state = state.copy(busy = false, financeBlocked = true, message = "Не удалось прочитать сохраненную команду. Повторите обновление."); return@launch }
                state = state.copy(busy = false, financialCommand = pending, financeBlocked = pending != null,
                    message = if (pending == null) "Не удалось сохранить команду. Запрос не отправлен." else "Результат не подтвержден. Повторите ту же команду.")
            }
        }
    }

    fun retryAccountCommand() {
        val saved = candidate ?: return
        val command = state.financialCommand ?: return
        if (state.busy || command.state != "pending" || !command.matches(saved)) return
        state = state.copy(busy = true, message = "")
        viewModelScope.launch {
            try { sendAccountCommand(saved) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = state.copy(busy = false, message = "Результат не подтвержден. Повторите ту же команду.") }
        }
    }

    private suspend fun sendAccountCommand(saved: StoredSession) {
        val result = CommandRunner(app.database.commands()).send(saved)
        state = state.copy(financialCommand = result, financeBlocked = true)
        if (result.state == "confirmed") {
            val accounts = ApiClient(saved.origin).accounts(saved)
            val history = ApiClient(saved.origin).transactions(saved)
            require(app.database.commands().remove(result.commandId, "confirmed") == 1)
            state = state.copy(busy = false, accounts = accounts, transactions = history, financialCommand = null, financeBlocked = false, message = if (result.path.contains("/accounts")) "Счет сохранен" else "Операция сохранена")
        } else {
            var current: Account? = null
            var transaction: Transaction? = null
            if (result.errorCode == "version_conflict" && result.path.contains("/transactions/")) {
                try { transaction = decodeResponse<Transaction>(ApiClient(saved.origin).request(result.path, session = saved)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            if (result.errorCode == "version_conflict" && result.method == "PUT" && result.path.contains("/accounts/")) {
                try { current = decodeResponse<Account>(ApiClient(saved.origin).request(result.path, session = saved)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            state = state.copy(busy = false, accountConflict = current, transactionConflict = transaction,
                message = if (result.state == "rejected") "Изменение отклонено: ${result.errorCode}. Обновите данные и проверьте черновик." else "Исходный результат требует сверки. Команда сохранена; новый ключ не создается.")
        }
    }

    fun acceptAccountResult() {
        val saved = candidate ?: return
        val command = state.financialCommand ?: return
        if (state.busy || command.state == "pending" || command.state == "reconcile") return
        state = state.copy(busy = true)
        viewModelScope.launch {
            try {
                if (command.errorCode == "sync_generation_conflict") {
                    refreshSaved(saved, holdBusy = true)
                    require(state.auth != null && state.persisted)
                }
                val accounts = ApiClient(saved.origin).accounts(saved)
                val history = ApiClient(saved.origin).transactions(saved)
                require(app.database.commands().remove(command.commandId, command.state) == 1)
                state = state.copy(busy = false, accounts = accounts, transactions = history, financialCommand = null, financeBlocked = false,
                    accountConflict = null, transactionConflict = null, message = if (command.state == "confirmed") (if (command.path.contains("/accounts")) "Счет сохранен" else "Операция сохранена") else "Данные обновлены. Проверьте поля перед новой командой.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = state.copy(busy = false, message = "Не удалось обновить данные. Команда сохранена.") }
        }
    }

    private suspend fun financialFailure(saved: StoredSession, error: Exception): Boolean {
        if (error !is ApiFailure || error.status != 401) return false
        try { expireSession(saved.origin, "Сессия завершена. Сохраненная команда остается в базе; войдите снова.") }
        catch (_: Exception) { state = state.copy(busy = false, financeBlocked = true, message = "Не удалось завершить локальную сессию. Команда сохранена.") }
        return true
    }

    fun rebindAccountCommand() {
        val saved = candidate ?: return
        val command = state.financialCommand ?: return
        if (state.busy || command.state != "pending" || command.origin != saved.origin || command.ownerId != saved.ownerId ||
            command.workspaceId != saved.workspaceId || command.generationId != saved.generationId) return
        state = state.copy(busy = true)
        viewModelScope.launch {
            try {
                require(app.database.commands().rebind(command.commandId, saved.origin, saved.ownerId, saved.workspaceId, saved.generationId, saved.sessionId) == 1)
                state = state.copy(busy = false, financialCommand = command.copy(sessionId = saved.sessionId), message = "Команда сохранена для текущей сессии. Проверьте черновик перед повтором.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = state.copy(busy = false, message = "Не удалось подготовить повтор. Команда сохранена.") }
        }
    }

    fun logout() {
        val saved = candidate ?: return
        if (state.busy || (state.financeBlocked && !state.logoutPending) || state.revokePendingId != null) return
        state = AuthState(saved.origin, auth = state.auth, logoutPending = true)
        viewModelScope.launch {
            try {
                val pending = StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, saved.generationId, saved.sessionId, true)
                app.credentialVault.save(pending)
                candidate = pending
                try {
                    val acknowledgement = decodeResponse<Acknowledgement>(ApiClient(saved.origin).request("/auth/logout", "POST", session = pending))
                    require(acknowledgement.ok)
                } catch (e: ApiFailure) { if (e.status != 401) throw e }
                app.credentialVault.clear()
                candidate = null
                state = AuthState(saved.origin, busy = false, message = "Вы вышли из аккаунта")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = AuthState(saved.origin, busy = false, message = "Выход еще не подтвержден. Повторите выход.", logoutPending = true) }
        }
    }
}

private fun errorMessage(error: Exception, authAttempt: Boolean = false): String = when {
    error is ApiFailure && error.status == 401 -> "Неверный email или пароль либо сессия завершена."
    error is ApiFailure && error.code == "registration_disabled" -> "Регистрация на этом сервере выключена."
    error is ApiFailure && error.status == 429 -> "Слишком много попыток. Попробуйте позже."
    error is ApiFailure && error.status in 400..499 -> "Сервер отклонил запрос. Проверьте введенные данные."
    authAttempt -> "Ответ не получен. Вход мог выполниться. Повторите вход после восстановления связи."
    else -> "Не удалось связаться с сервером. Повторите проверку сессии."
}
