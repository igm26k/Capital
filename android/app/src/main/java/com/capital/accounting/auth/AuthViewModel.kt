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
import com.capital.accounting.normalizedOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.TimeZone

class AuthState(
    val origin: String = "", val busy: Boolean = true, val message: String = "",
    val auth: BearerAuth? = null, val restorePending: Boolean = false,
    val logoutPending: Boolean = false, val persisted: Boolean = true,
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CapitalApplication
    var state by mutableStateOf(AuthState())
        private set
    private var candidate: StoredSession? = null
    init { restoreInternal() }

    fun saveOrigin(value: String) {
        if (state.busy || state.auth != null || state.logoutPending) return
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
        if (state.busy || state.auth != null || state.logoutPending || state.origin.isEmpty()) return
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
        if (state.busy || state.auth != null || state.logoutPending) return
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
                        if (saved.session.logoutPending) {
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

    private suspend fun refreshSaved(saved: StoredSession, renew: Boolean = false) {
        try {
            val result = decodeResponse<BearerAuth>(ApiClient(saved.origin).request(if (renew) "/auth/renew" else "/auth/session", if (renew) "POST" else "GET", session = saved))
            require(result.profile.id == saved.ownerId && result.session.id == saved.sessionId && result.access_token == saved.token && result.workspace.id == saved.workspaceId)
            accept(result, saved.origin)
        } catch (e: ApiFailure) {
            if (e.status != 401) throw e
            app.credentialVault.clear()
            candidate = null
            state = AuthState(saved.origin, busy = false, message = "Сессия завершена. Войдите снова.")
        }
    }

    private suspend fun accept(auth: BearerAuth, origin: String) {
        val saved = auth.credential(origin)
        candidate = saved
        try {
            app.credentialVault.save(saved)
            state = AuthState(origin, busy = false, auth = auth)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state = AuthState(origin, busy = false, auth = auth, persisted = false, message = "Вход выполнен, но сессия не сохранена. Повторите сохранение.") }
    }

    fun persist() {
        val auth = state.auth ?: return
        if (state.busy || state.logoutPending) return
        state = AuthState(state.origin, auth = auth)
        viewModelScope.launch { accept(auth, state.origin) }
    }

    fun renew() {
        val saved = candidate ?: return
        if (state.busy || state.logoutPending) return
        val previous = state
        state = AuthState(saved.origin, auth = previous.auth, persisted = previous.persisted)
        viewModelScope.launch {
            try { refreshSaved(saved, renew = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = AuthState(saved.origin, busy = false, auth = previous.auth, persisted = previous.persisted, message = errorMessage(e), restorePending = previous.auth == null) }
        }
    }

    fun logout() {
        val saved = candidate ?: return
        if (state.busy) return
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
