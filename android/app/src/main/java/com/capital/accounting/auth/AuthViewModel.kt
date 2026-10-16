package com.capital.accounting.auth

import android.app.Application
import androidx.room.withTransaction
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.capital.accounting.CapitalApplication
import com.capital.accounting.api.*
import com.capital.accounting.data.ConnectionSettings
import com.capital.accounting.data.FinancialCommand
import com.capital.accounting.data.IdentityStore
import com.capital.accounting.data.OfflineClassificationStore
import com.capital.accounting.data.OfflineAdjustmentStore
import com.capital.accounting.data.OfflineRefundStore
import com.capital.accounting.data.OfflineTransferStore
import com.capital.accounting.data.OfflineBasicStore
import com.capital.accounting.data.OfflineAccountStore
import com.capital.accounting.data.OfflineCategoryStore
import com.capital.accounting.data.OfflineTagStore
import com.capital.accounting.data.LocalHistoryCursor
import com.capital.accounting.data.LocalHistoryStore
import com.capital.accounting.data.OutboxRecoveryStore
import com.capital.accounting.data.OutboxDismissalPlan
import com.capital.accounting.data.OutboxCommand
import com.capital.accounting.data.OutboxFailure
import com.capital.accounting.sync.OutboxReconciler
import com.capital.accounting.data.OpeningLinkStore
import com.capital.accounting.data.LocalProjection
import com.capital.accounting.data.LocalProjectionStore
import com.capital.accounting.data.MirrorFinance
import com.capital.accounting.data.MirrorScope
import com.capital.accounting.sync.MirrorSynchronizer
import com.capital.accounting.sync.QueueScheduler
import com.capital.accounting.finance.CommandRunner
import com.capital.accounting.normalizedOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.TimeZone

data class TransactionReceipt(val commandId: String, val transactionId: String, val deleted: Boolean, val created: Boolean, val kind: String?, val records: List<Transaction>) {
    fun version(id: String?): String? = records.find { it.id == id }?.version
    val transaction get() = records.find { it.id == transactionId }
}

data class LocalCollectionReceipt(val actionId: String, val collection: String, val recordId: String, val created: Boolean, val deleted: Boolean = false, val kind: String? = null, val ids: List<String> = emptyList())

data class CollectionReceipt(val commandId: String, val collection: String, val recordId: String, val created: Boolean, val result: MutationResult)
data class ClassificationReceipt(val commandId: String, val ids: Set<String>)

data class AuthState(
    val origin: String = "", val busy: Boolean = true, val message: String = "",
    val auth: BearerAuth? = null, val restorePending: Boolean = false, val emailStep: String = "",
    val logoutPending: Boolean = false, val persisted: Boolean = true,
    val sessions: List<Session> = emptyList(), val revokePendingId: String? = null,
    val accounts: List<Account> = emptyList(), val transactions: List<Transaction> = emptyList(), val categories: List<Category> = emptyList(), val tags: List<Tag> = emptyList(), val financialCommand: FinancialCommand? = null,
    val detailOpeningAccountId: String? = null, val detailLocal: Boolean = false,
    val detailStack: List<String> = emptyList(), val detailTransaction: Transaction? = null, val detailMissing: Boolean = false, val detailError: String = "",
    val collectionReceipt: CollectionReceipt? = null, val localCollectionReceipt: LocalCollectionReceipt? = null,
    val localAffected: Set<Pair<String, String>> = emptySet(),
    val classificationReceipt: ClassificationReceipt? = null,
    val accountsLoaded: Boolean = false, val overviewLoaded: Boolean = false, val overviewError: String = "", val overviewCached: Boolean = false, val offline: Boolean = false, val localPending: Int = 0, val localBlocked: Int = 0, val queueRunning: Boolean = false, val queueMessage: String = "",
    val transactionsLoaded: Boolean = false, val transactionReceipt: TransactionReceipt? = null,
    val historyFilter: TransactionFilter? = null, val historyItems: List<Transaction>? = null,
    val recoveryOpen: Boolean = false, val recoveryCommands: List<OutboxCommand> = emptyList(), val recoveryFailure: OutboxFailure? = null,
    val recoveryPlan: OutboxDismissalPlan? = null, val recoveryError: String = "",
    val historyDataset: LocalHistoryCursor? = null, val historyLocalCursor: LocalHistoryCursor? = null, val historyLocal: Boolean = false,
    val historyLimit: Int = 50, val historyNextCursor: String? = null, val historyCursors: List<String> = emptyList(), val historyError: String = "",
    val financeBlocked: Boolean = true, val accountConflict: Account? = null, val transactionConflict: Transaction? = null, val categoryConflict: Category? = null, val tagConflict: Tag? = null,

) {
    val canQueueClassification get() = canQueueCatalog
    val canQueueAdjustments get() = canQueueCatalog
    val canQueueRefunds get() = canQueueCatalog
    val canQueueTransfers get() = canQueueCatalog
    val canQueueBasicCreates get() = canQueueCatalog
    val canQueueAccounts get() = canQueueCatalog
    val canQueueCatalog get() = offline && auth != null && persisted && !busy && !logoutPending && revokePendingId == null && financialCommand == null && localBlocked == 0
}

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
                val response = ApiClient(origin).request(if (register) "/auth/register" else "/auth/login", "POST", body)
                if (register && ApiClient.json.parseToJsonElement(response).jsonObject["status"]?.jsonPrimitive?.content == "confirmation_required") {
                    decodeResponse<EmailChallenge>(response)
                    state = AuthState(origin, busy = false, emailStep = "registration", message = "Если запрос можно подтвердить, письмо с кодом будет отправлено. Повторите исходный пароль и введите код. Код действует 30 минут.")
                } else accept(decodeResponse<BearerAuth>(response), origin)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = AuthState(origin, busy = false, message = errorMessage(e, authAttempt = true)) }
        }
    }

    fun requestPasswordRecovery(email: String) {
        if (state.busy || state.auth != null || state.origin.isEmpty()) return
        val origin = state.origin
        state = AuthState(origin)
        viewModelScope.launch {
            try {
                decodeResponse<EmailChallenge>(ApiClient(origin).request("/auth/recovery/request", "POST", ApiClient.json.encodeToString(RecoveryRequest(email))))
                state = AuthState(origin, busy = false, emailStep = "recovery", message = "Если запрос можно подтвердить, письмо с кодом будет отправлено. Введите код и новый пароль длиной от 12 символов.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = AuthState(origin, busy = false, message = errorMessage(e)) }
        }
    }
    fun confirmEmail(email: String, code: String, password: String, device: String) {
        if (state.busy || state.auth != null || state.emailStep.isEmpty()) return
        val origin = state.origin
        val step = state.emailStep
        state = AuthState(origin, emailStep = step)
        viewModelScope.launch {
            try {
                if (step == "registration") {
                    val result = decodeResponse<BearerAuth>(ApiClient(origin).request("/auth/registration/confirm", "POST", ApiClient.json.encodeToString(RegistrationConfirm(email, password, device, "bearer", code))))
                    accept(result, origin)
                } else {
                    decodeResponse<PasswordChanged>(ApiClient(origin).request("/auth/recovery/confirm", "POST", ApiClient.json.encodeToString(RecoveryConfirm(email, code, password))))
                    expireSession(origin, "Пароль изменён, прежние сессии отозваны. Войдите с новым паролем.")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = AuthState(origin, busy = false, emailStep = step, message = if (e is ApiFailure && e.code == "confirmation_invalid") "Код недействителен, истёк или пароль регистрации не совпадает. После потерянного ответа попробуйте обычный вход." else errorMessage(e)) }
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
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiFailure && e.status in setOf(401, 403, 404)) {
                expireSession(saved.origin, "Сессия завершена. Войдите снова.")
            } else if (!renew && e is java.io.IOException && (e !is ApiFailure || e.status >= 500) && restoreOffline(saved, holdBusy)) {
                // Cached identity permits only local reading until explicit online verification.
            } else throw e
        }
    }

    private suspend fun restoreOffline(saved: StoredSession, holdBusy: Boolean): Boolean {
        val identity = IdentityStore(app.database).read(saved) ?: return false
        val projection = LocalProjectionStore(app.database).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)) ?: return false
        if (projection.confirmed.head.generation != saved.generationId) return false
        val command = app.database.commands().get(saved.origin, saved.ownerId, saved.workspaceId)
        candidate = saved
        state = AuthState(saved.origin, busy = holdBusy, auth = BearerAuth(identity.profile, identity.workspace, identity.session, "bearer", saved.token, "Bearer"),
            offline = true, overviewCached = true, financeBlocked = true, financialCommand = command).withProjection(projection)
        if (state.localPending > 0) synchronizeQueue()
        return true
    }

    private suspend fun accept(auth: BearerAuth, origin: String, holdBusy: Boolean = false) {
        val saved = auth.credential(origin)
        candidate = saved
        try {
            app.credentialVault.save(saved)
            IdentityStore(app.database).save(auth, origin)
            val command = app.database.commands().get(saved.origin, saved.ownerId, saved.workspaceId)
            state = AuthState(origin, busy = true, auth = auth, financialCommand = command, financeBlocked = command != null)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state = AuthState(origin, busy = holdBusy, auth = auth, persisted = false, message = "Вход выполнен, но сессия не сохранена. Повторите сохранение."); return }
        fetchOverview(saved, holdBusy)
        if (state.localPending > 0) synchronizeQueue()
    }

    fun loadOverview() {
        val saved = candidate ?: return
        if (state.busy || state.auth == null || !state.persisted || state.logoutPending || state.revokePendingId != null) return
        state = state.copy(busy = true, overviewError = "")
        viewModelScope.launch {
            try { if (state.offline) refreshSaved(saved) else fetchOverview(saved) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = state.copy(busy = false, overviewError = errorMessage(e)) }
        }
    }

    fun synchronizeQueue() {
        val saved = candidate ?: return
        if (state.busy || state.queueRunning || state.auth == null || !state.persisted || state.logoutPending || state.revokePendingId != null ||
            state.financialCommand != null || state.localPending + state.localBlocked == 0) return
        state = state.copy(queueRunning = true, queueMessage = "Синхронизация ожидает соединения или выполняется.")
        viewModelScope.launch {
            try {
                val id = QueueScheduler(app).schedule(saved)
                val info = requireNotNull(WorkManager.getInstance(app).getWorkInfoByIdFlow(id).first { it != null && it.state.isFinished })
                if (!queueScopeCurrent(saved)) return@launch
                while (state.busy && queueScopeCurrent(saved)) delay(50)
                if (!queueScopeCurrent(saved)) return@launch
                val status = info.outputData.getString("status")
                if (status == "AUTH_REQUIRED") {
                    val stored = (app.credentialVault.load(saved.origin) as? VaultRead.Available)?.session
                    if (stored != null && stored.ownerId == saved.ownerId && stored.workspaceId == saved.workspaceId && stored.sessionId == saved.sessionId && stored.generationId == saved.generationId && stored.token == saved.token &&
                        !stored.logoutPending && stored.revokeSessionId == null) expireSession(saved.origin)
                    else { candidate = null; state = AuthState(saved.origin, busy = false, message = "Сессия требует проверки. Войдите снова.") }
                    return@launch
                }
                if (status == "SCOPE_CHANGED") {
                    candidate = null; state = AuthState(saved.origin, busy = false, message = "Сессия или пространство изменились. Войдите снова.")
                    return@launch
                }
                state = state.copy(queueRunning = false, queueMessage = when {
                    info.state == WorkInfo.State.CANCELLED -> "Синхронизация прервана. Изменения сохранены."
                    status == "IDLE" -> "Синхронизация завершена."
                    else -> "Сохраненные изменения требуют проверки."
                })
                loadOverview()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (queueScopeCurrent(saved)) state = state.copy(queueRunning = false, queueMessage = "Не удалось запустить синхронизацию. Изменения сохранены.")
            }
        }
    }
    fun closeQueueRecovery() { if (!state.busy) state = state.copy(recoveryOpen = false, recoveryPlan = null, recoveryError = "") }
    fun openQueueRecovery() {
        val saved = candidate ?: return
        if (state.busy || state.auth == null || !state.persisted || state.logoutPending || state.revokePendingId != null) return
        state = state.copy(busy = true, recoveryOpen = true, recoveryPlan = null, recoveryError = "")
        viewModelScope.launch {
            try { refreshRecovery(saved); state = state.copy(busy = false) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = state.copy(busy = false, recoveryError = "Не удалось прочитать сохранённые изменения.") }
        }
    }
    private suspend fun refreshRecovery(saved: StoredSession) = app.database.withTransaction {
        if (IdentityStore(app.database).read(saved) == null) throw ApiFailure(401, "unauthenticated")
        val commands = app.database.outbox().commands(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId).key)
            .filter { it.state !in setOf("applied", "dismissed") }
        val failure = commands.firstOrNull()?.let { app.database.failures().get(it.position) }
        val projection = requireNotNull(LocalProjectionStore(app.database).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)))
        require(projection.confirmed.head.generation == saved.generationId)
        state = state.withProjection(projection).copy(recoveryCommands = commands, recoveryFailure = failure, recoveryPlan = null)
    }
    fun inspectQueueHead() {
        val saved = candidate ?: return
        val command = state.recoveryCommands.firstOrNull() ?: return
        if (state.busy || state.offline || command.state !in setOf("sending", "uncertain", "reconcile", "rejected")) return
        state = state.copy(busy = true, recoveryError = "")
        viewModelScope.launch {
            try {
                val outcome = OutboxReconciler(app.database).inspect(saved, command.actionId)
                readFinance(saved); refreshRecovery(candidate ?: saved)
                state = state.copy(busy = false, recoveryError = if (outcome == null) "Результат неизвестен. Исходная команда сохранена; исключение недоступно." else "Результат сверён. Новая команда не отправлялась.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = state.copy(busy = false, recoveryError = "Не удалось сверить результат. Исходная команда сохранена.") }
        }
    }
    fun previewQueueDismissal() {
        val saved = candidate ?: return
        val head = state.recoveryCommands.firstOrNull() ?: return
        if (state.busy || state.queueRunning || state.financialCommand != null) return
        state = state.copy(busy = true, recoveryError = "")
        viewModelScope.launch {
            try { val plan = OutboxRecoveryStore(app.database).preview(saved, head.actionId); state = state.copy(busy = false, recoveryPlan = plan) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = state.copy(busy = false, recoveryError = "Результат не доказан или зависимые команды уже отправлялись. Исключение недоступно.") }
        }
    }
    fun cancelQueueDismissal() { if (!state.busy) state = state.copy(recoveryPlan = null) }
    fun dismissQueueBranch() {
        val saved = candidate ?: return
        val plan = state.recoveryPlan ?: return
        if (state.busy || state.queueRunning || state.financialCommand != null) return
        state = state.copy(busy = true, recoveryError = "")
        viewModelScope.launch {
            try {
                OutboxRecoveryStore(app.database).dismiss(saved, plan)
                refreshRecovery(saved)
                state = state.copy(busy = false, recoveryError = "Изменения исключены из отправки. Исходные команды сохранены. Остальные изменения можно синхронизировать отдельно.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = state.copy(busy = false, recoveryPlan = null, recoveryError = "Очередь изменилась. Проверьте её заново; команды сохранены.") }
        }
    }

    private fun queueScopeCurrent(saved: StoredSession): Boolean {
        val active = candidate ?: return false
        return state.auth != null && !state.logoutPending && state.revokePendingId == null &&
            active.origin == saved.origin && active.ownerId == saved.ownerId && active.workspaceId == saved.workspaceId &&
            active.sessionId == saved.sessionId && active.generationId == saved.generationId && active.token == saved.token && !active.logoutPending && active.revokeSessionId == null
    }

    private fun AuthState.withFinance(finance: MirrorFinance): AuthState = copy(accounts = finance.accounts, accountsLoaded = true,
        transactions = finance.transactions, transactionsLoaded = true, categories = finance.categories, tags = finance.tags, overviewLoaded = true)

    private fun AuthState.withProjection(projection: LocalProjection): AuthState = withFinance(projection.visible).copy(
        localPending = projection.pending.size, localBlocked = projection.blocked.size, localAffected = projection.affected,
        financeBlocked = offline || financialCommand != null || projection.pending.isNotEmpty() || projection.blocked.isNotEmpty())

    private suspend fun readFinance(saved: StoredSession): MirrorFinance {
        val mirror = MirrorSynchronizer(app.database).refresh(saved)
        if (state.auth?.workspace?.sync_generation_id != mirror.head.generation) {
            val auth = decodeResponse<BearerAuth>(ApiClient(saved.origin).request("/auth/session", session = saved))
            require(auth.profile.id == saved.ownerId && auth.workspace.id == saved.workspaceId && auth.session.id == saved.sessionId && auth.access_token == saved.token && auth.workspace.sync_generation_id == mirror.head.generation)
            val updated = auth.credential(saved.origin)
            app.credentialVault.save(updated); IdentityStore(app.database).save(auth, saved.origin); candidate = updated; state = state.copy(auth = auth, queueRunning = false, queueMessage = "")
        }
        OpeningLinkStore(app.database).refresh(candidate ?: saved)
        val projection = requireNotNull(LocalProjectionStore(app.database).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)))
        require(projection.confirmed.head.generation == mirror.head.generation)
        state = state.copy(localPending = projection.pending.size, localBlocked = projection.blocked.size, localAffected = projection.affected)
        return projection.visible
    }

    private suspend fun fetchOverview(saved: StoredSession, holdBusy: Boolean = false) {
        try {
            if (!state.overviewLoaded) {
                LocalProjectionStore(app.database).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))?.takeIf { it.confirmed.head.generation == state.auth?.workspace?.sync_generation_id }?.let { state = state.withProjection(it).copy(overviewCached = true) }
            }
            val finance = readFinance(saved)
            val command = app.database.commands().get(saved.origin, saved.ownerId, saved.workspaceId)
            val page = state.historyFilter?.let { LocalHistoryStore(app.database).page(candidate ?: saved, it, state.historyLimit) }
            state = state.withFinance(finance).copy(busy = holdBusy, overviewError = "", overviewCached = false, financialCommand = command, financeBlocked = command != null || state.localPending > 0 || state.localBlocked > 0,
                historyItems = page?.items, historyDataset = page?.dataset, historyLocalCursor = page?.next, historyLocal = page != null, historyNextCursor = null, historyCursors = emptyList(), historyError = "")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiFailure && e.status in setOf(403, 404)) expireSession(saved.origin, "Пространство недоступно. Войдите снова.")
            else if (!financialFailure(saved, e)) state = state.copy(busy = holdBusy,
                overviewError = "Не удалось загрузить финансовые данные. Повторите загрузку. Ранее загруженные данные могут быть устаревшими.")
        }
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
        app.notificationInbox.pauseCapture()
        app.credentialVault.clear()
        candidate = null
        state = AuthState(origin, busy = false, message = message)
    }



    /** Actual401 from auxiliary screens must hide the same cached financial scope. */
    fun sessionUnauthorized(sessionId: String) {
        val auth = state.auth ?: return
        if (auth.session.id != sessionId) return
        app.notificationInbox.pauseCapture()
        val origin = state.origin
        candidate = null
        state = AuthState(origin,busy=true,message="Сессия завершена. Войдите снова.")
        viewModelScope.launch {
            try {
                try {
                    val scope = com.capital.accounting.data.MirrorScope(origin,auth.profile.id,auth.workspace.id)
                    app.database.identities().remove(scope.key)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Vault cleanup below remains independent. */ }
                val current = (app.credentialVault.load(origin) as? VaultRead.Available)?.session
                if (current?.sessionId == sessionId) {
                    app.notificationInbox.pauseCapture()
                    app.credentialVault.clear()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Financial data stays hidden in this process. */ }
            finally { state = AuthState(origin,busy=false,message="Сессия завершена. Войдите снова.") }
        }
    }

    fun deleteUserData(password: String, confirmation: String) {
        val saved = candidate ?: return
        if (state.busy || !state.persisted || confirmation != "DELETE") return
        state = state.copy(busy = true, message = "")
        viewModelScope.launch {
            try {
                val body = org.json.JSONObject().put("password", password).put("confirmation", confirmation).toString()
                ApiClient(saved.origin).request("/me", "DELETE", body, saved)
                app.notificationInbox.pauseCapture()
                app.credentialVault.clear()
                candidate = null
                state = AuthState(saved.origin,busy=true,message="Профиль удалён. Очистка данных устройства…")
                com.capital.accounting.sync.SyncGate.serialized(com.capital.accounting.data.MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)) {
                    val scope = com.capital.accounting.data.MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
                    app.database.withTransaction {
                        val db = app.database.openHelper.writableDatabase
                        for (table in listOf("outbox_commands", "cached_identities", "opening_links", "snapshot_records", "snapshot_tokens", "snapshot_stages", "snapshot_attempts", "mirror_changes", "mirror_tombstones", "mirror_records", "mirror_heads")) db.execSQL("DELETE FROM $table WHERE scope=?", arrayOf(scope.key))
                        db.execSQL("DELETE FROM financial_commands WHERE origin=? AND ownerId=? AND workspaceId=?", arrayOf(saved.origin,saved.ownerId,saved.workspaceId))
                    }
                }
                app.notificationInbox.eraseAfterAccountDeletion(saved)
                app.financialDrafts.erase(saved.origin,saved.ownerId,saved.workspaceId)
                com.capital.accounting.finance.PlanningStore(app).eraseAfterAccountDeletion(saved)
                com.capital.accounting.finance.PlanningStore(app,"csv").eraseAfterAccountDeletion(saved)
                app.getSharedPreferences("csv_jobs",0).edit().remove(com.capital.accounting.finance.csvJobKey(saved)).apply()
                candidate = null
                state = AuthState(saved.origin,busy=false,message="Профиль и личные данные удалены.")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiFailure && e.status == 401) { sessionUnauthorized(saved.sessionId); return@launch }
                state = if (candidate == null) AuthState(saved.origin,busy=false,message="Профиль удалён; очистка локальных данных требует проверки.")
                else state.copy(busy=false,message="Не удалось подтвердить удаление. Проверьте соединение и пароль; при необходимости войдите снова.")
            }
        }
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

    fun loadAccounts() = loadOverview()

    fun loadTransactions() = loadOverview()

    /** Refresh authoritative data when reachable, then read one scoped local projection. */
    private suspend fun prepareLocalRead(saved: StoredSession): StoredSession {
        if (!state.offline) {
            try { readFinance(saved) }
            catch (e: CancellationException) { throw e }
            catch (e: java.io.IOException) {
                if (e is ApiFailure && e.status < 500) throw e
                requireNotNull(IdentityStore(app.database).read(candidate ?: saved))
                state = state.copy(offline = true, overviewCached = true, financeBlocked = true)
            }
        }
        return candidate ?: saved
    }

    fun applyHistoryFilter(filter: TransactionFilter, limit: Int = 50) {
        require(limit in 1..100)
        val saved = candidate ?: return
        if (state.busy || state.auth == null || !state.persisted || state.logoutPending || state.revokePendingId != null) return
        val previous = state
        state = state.copy(busy = true, historyError = "")
        viewModelScope.launch {
            try {
                val active = prepareLocalRead(saved)
                val page = LocalHistoryStore(app.database).page(active, filter, limit)
                state = state.withProjection(page.projection).copy(busy = false, historyFilter = filter, historyItems = page.items,
                    historyLimit = limit, historyNextCursor = null, historyCursors = emptyList(), historyDataset = page.dataset, historyLocalCursor = page.next,
                    historyLocal = true, historyError = "")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = state.copy(busy = false, historyFilter = previous.historyFilter,
                historyItems = previous.historyItems, historyDataset = previous.historyDataset, historyLocalCursor = previous.historyLocalCursor,
                historyError = "Не удалось применить фильтры. Предыдущие результаты сохранены.") }
        }
    }

    fun nextHistoryPage() {
        val saved = candidate ?: return
        val filter = state.historyFilter ?: return
        val cursor = state.historyLocalCursor ?: return
        if (state.busy || state.auth == null || !state.persisted || state.logoutPending || state.revokePendingId != null) return
        val previous = state
        state = state.copy(busy = true, historyError = "")
        viewModelScope.launch {
            try {
                val active = prepareLocalRead(saved)
                val page = LocalHistoryStore(app.database).page(active, filter, previous.historyLimit, cursor)
                state = state.withProjection(page.projection).copy(busy = false,
                    historyItems = previous.historyItems.orEmpty() + page.items, historyDataset = page.dataset, historyLocalCursor = page.next, historyError = "")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = state.copy(busy = false,
                historyError = "Данные изменились или недоступны. Примените фильтры заново; предыдущая страница сохранена.") }
        }
    }

    fun clearHistoryFilters() {
        if (state.busy) return
        state = state.copy(historyFilter = null, historyItems = null, historyDataset = null, historyLocalCursor = null, historyLocal = false, historyNextCursor = null, historyCursors = emptyList(), historyError = "")
        loadTransactions()
    }

    fun openTransactionDetails(id: String) {
        require(java.util.UUID.fromString(id).toString() == id)
        val existing = state.detailStack.indexOf(id)
        val stack = if (existing >= 0) state.detailStack.take(existing + 1) else state.detailStack + id
        loadTransactionDetails(id, stack)
    }

    fun refreshTransactionDetails() { state.detailStack.lastOrNull()?.let { loadTransactionDetails(it, state.detailStack) } }

    fun backTransactionDetails() {
        if (state.busy) return
        if (state.detailStack.size <= 1) closeTransactionDetails()
        else { val stack = state.detailStack.dropLast(1); loadTransactionDetails(stack.last(), stack) }
    }

    fun closeTransactionDetails() {
        if (state.busy) return
        state = state.copy(detailStack = emptyList(), detailTransaction = null, detailMissing = false, detailError = "", detailOpeningAccountId = null, detailLocal = false)
    }

    private fun loadTransactionDetails(id: String, stack: List<String>) {
        val saved = candidate ?: return
        if (state.busy || state.auth == null || !state.persisted || state.logoutPending || state.revokePendingId != null) return
        state = state.copy(busy = true, detailStack = stack, detailTransaction = null, detailMissing = false, detailError = "", detailOpeningAccountId = null, detailLocal = false)
        viewModelScope.launch {
            try {
                val active = prepareLocalRead(saved)
                val (details, historyPage) = app.database.withTransaction {
                    val store = LocalHistoryStore(app.database)
                    store.details(active, id) to state.historyFilter?.let { store.page(active, it, state.historyLimit) }
                }
                val keepHistory = historyPage != null && historyPage.dataset == state.historyDataset
                state = state.withProjection(details.projection).copy(busy = false, detailStack = stack,
                    detailTransaction = details.transaction, detailMissing = details.transaction == null, detailError = "",
                    detailOpeningAccountId = details.openingAccountId, detailLocal = true,
                    historyItems = if (keepHistory) state.historyItems else historyPage?.items, historyDataset = historyPage?.dataset,
                    historyLocalCursor = if (keepHistory) state.historyLocalCursor else historyPage?.next, historyLocal = historyPage != null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!financialFailure(saved, e)) state = state.copy(busy = false, detailStack = stack,
                detailTransaction = null, detailMissing = false, detailError = "Не удалось загрузить детали. Повторите обновление.") }
        }
    }

    fun loadCatalog() = loadOverview()

    fun submitAccount(body: String, id: String? = null) {
        if (!state.offline) { submitFinancial(body, if (id == null) "accounts" else "accounts/$id", if (id == null) "POST" else "PUT"); return }
        val saved = candidate ?: return
        if (!state.canQueueAccounts) return
        state = state.copy(busy = true, message = "")
        viewModelScope.launch {
            try {
                val store = OfflineAccountStore(app.database)
                val queued = if (id == null) store.create(saved, body) else store.update(saved, body, id)
                val projection = requireNotNull(LocalProjectionStore(app.database).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)))
                val recordId = id ?: decodeResponse<AccountCreate>(body).id
                state = state.withProjection(projection).copy(busy = false, localCollectionReceipt = LocalCollectionReceipt(queued.actionId, "accounts", recordId, id == null),
                    message = "Изменение счета сохранено на устройстве и ожидает синхронизации.")
                synchronizeQueue()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = state.copy(busy = false, message = "Не удалось сохранить локальное изменение счета. Проверьте сессию, сумму и версию; черновик сохранен.") }
        }
    }
    fun submitAdjustment(body: String, accountId: String) {
        if (!state.offline) { submitFinancial(body, "accounts/$accountId/adjustments", "POST"); return }
        val saved = candidate ?: return
        if (!state.canQueueAdjustments) return
        state = state.copy(busy = true, message = "")
        viewModelScope.launch {
            try {
                val queued = OfflineAdjustmentStore(app.database).create(saved, body, accountId)
                val projection = requireNotNull(LocalProjectionStore(app.database).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)))
                state = state.withProjection(projection).copy(busy = false,
                    localCollectionReceipt = LocalCollectionReceipt(queued.actionId, "transactions", decodeResponse<AdjustmentCreate>(body).id, true, kind = "adjustment"),
                    message = "Корректировка сохранена на устройстве и ожидает синхронизации.")
                synchronizeQueue()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = state.copy(busy = false, message = "Не удалось сохранить локальную корректировку. Проверьте остаток, версию и причину; черновик сохранен.") }
        }
    }
    fun submitTransaction(body: String, id: String? = null, delete: Boolean = false) {
        if (!state.offline) { submitFinancial(body, if (id == null) "transactions" else "transactions/$id", if (delete) "DELETE" else if (id == null) "POST" else "PUT"); return }
        val saved = candidate ?: return
        if (!state.canQueueBasicCreates || delete && id == null) return
        state = state.copy(busy = true, message = "")
        viewModelScope.launch {
            try {
                val store = OfflineBasicStore(app.database)
                val kind = ApiClient.json.parseToJsonElement(body).jsonObject["kind"]?.jsonPrimitive?.content ?: state.transactions.find { it.id == id }?.kind
                val queued = if (kind == "adjustment" && id != null) OfflineAdjustmentStore(app.database).let { if (delete) it.delete(saved, body, id) else it.replace(saved, body, id) } else if (kind == "refund") OfflineRefundStore(app.database).let { if (id == null) it.create(saved, body) else if (delete) it.delete(saved, body, id) else it.replace(saved, body, id) } else if (kind == "transfer") OfflineTransferStore(app.database).let { if (id == null) it.create(saved, body) else if (delete) it.delete(saved, body, id) else it.replace(saved, body, id) } else if (id == null) store.create(saved, body) else if (delete) store.delete(saved, body, id) else store.replace(saved, body, id)
                val projection = requireNotNull(LocalProjectionStore(app.database).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)))
                val recordId = id ?: requireNotNull(ApiClient.json.parseToJsonElement(body).jsonObject["id"]).jsonPrimitive.content
                state = state.withProjection(projection).copy(busy = false, localCollectionReceipt = LocalCollectionReceipt(queued.actionId, "transactions", recordId, id == null, delete, kind),
                    message = "Операция сохранена на устройстве и ожидает синхронизации.")
                synchronizeQueue()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = state.copy(busy = false, message = "Не удалось сохранить локальную операцию. Проверьте сумму, дату и ссылки; черновик сохранен.") }
        }
    }
    fun submitClassification(body: String) {
        if (!state.offline) { submitFinancial(body, "transactions/classification", "POST"); return }
        val saved = candidate ?: return
        if (!state.canQueueClassification) return
        state = state.copy(busy = true, message = "")
        viewModelScope.launch {
            try {
                val queued = OfflineClassificationStore(app.database).enqueue(saved, body)
                val projection = requireNotNull(LocalProjectionStore(app.database).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)))
                state = state.withProjection(projection).copy(busy = false,
                    localCollectionReceipt = LocalCollectionReceipt(queued.actionId, "classification", queued.actionId, false, ids = decodeResponse<BulkClassification>(body).items.map { it.id }),
                    message = "Классификация сохранена на устройстве и ожидает синхронизации.")
                synchronizeQueue()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state = state.copy(busy = false, message = "Не удалось сохранить локальный пакет. Проверьте версии и доступность операций; выбор сохранен.") }
        }
    }

    fun submitCatalog(body: String, collection: String, id: String? = null) {
        require(collection in setOf("categories", "tags"))
        if (state.offline) {
            val saved = candidate ?: return
            if (!state.canQueueCatalog) return
            state = state.copy(busy = true, message = "")
            viewModelScope.launch {
                try {
                    val queued = if (collection == "tags") OfflineTagStore(app.database).enqueue(saved, body, id) else OfflineCategoryStore(app.database).enqueue(saved, body, id)
                    val projection = requireNotNull(LocalProjectionStore(app.database).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)))
                    val recordId = id ?: if (collection == "tags") decodeResponse<TagCreate>(body).id else decodeResponse<CategoryCreate>(body).id
                    state = state.withProjection(projection).copy(busy = false, localCollectionReceipt = LocalCollectionReceipt(queued.actionId, collection, recordId, id == null),
                        message = "Изменение классификации сохранено на устройстве и ожидает синхронизации.")
                    synchronizeQueue()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { state = state.copy(busy = false, message = "Не удалось сохранить локальное изменение. Проверьте сессию и версию и связи; черновик сохранен.") }
            }
            return
        }
        submitFinancial(body, if (id == null) collection else "$collection/$id", if (id == null) "POST" else "PUT")
    }

    private fun submitFinancial(body: String, suffix: String, method: String) {
        val saved = candidate ?: return
        if (state.busy || state.auth == null || !state.persisted || state.financeBlocked || state.logoutPending || state.revokePendingId != null) return
        state = state.copy(busy = true, financeBlocked = true, message = "", accountConflict = null, transactionConflict = null, categoryConflict = null, tagConflict = null)
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
                state = state.copy(busy = false, financialCommand = pending, financeBlocked = pending != null || state.localPending > 0 || state.localBlocked > 0,
                    message = if (pending == null) "Не удалось сохранить команду. Запрос не отправлен." else "Результат не подтвержден. Повторите ту же команду.")
            }
        }
    }

    fun retryAccountCommand() {
        val saved = candidate ?: return
        val command = state.financialCommand ?: return
        if (state.busy || state.offline || command.state != "pending" || !command.matches(saved)) return
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
            val finance = readFinance(saved)
            val accounts = finance.accounts; val history = finance.transactions; val categories = finance.categories; val tags = finance.tags
            val page = state.historyFilter?.let { LocalHistoryStore(app.database).page(candidate ?: saved, it, state.historyLimit) }
            val collection = collectionReceipt(result) ?: state.collectionReceipt
            val receipt = transactionReceipt(result) ?: state.transactionReceipt
            val classification = classificationReceipt(result) ?: state.classificationReceipt
            require(app.database.commands().remove(result.commandId, "confirmed") == 1)
            state = state.copy(busy = false, accounts = accounts, accountsLoaded = true, overviewLoaded = true, overviewError = "", transactions = history, transactionsLoaded = true, categories = categories, tags = tags, financialCommand = null, financeBlocked = state.localPending > 0 || state.localBlocked > 0, historyItems = page?.items, historyDataset = page?.dataset, historyLocalCursor = page?.next, historyLocal = page != null, historyNextCursor = null,
                historyCursors = emptyList(), historyError = "", transactionReceipt = receipt, collectionReceipt = collection, classificationReceipt = classification, message = savedMessage(result.path))
        } else {
            var current: Account? = null
            var transaction: Transaction? = null
            var category: Category? = null
            var tag: Tag? = null
            if (result.errorCode == "version_conflict" && result.path.contains("/transactions/") && !result.path.endsWith("/classification")) {
                try { transaction = decodeResponse<Transaction>(ApiClient(saved.origin).request(result.path, session = saved)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            if (result.errorCode == "version_conflict" && result.method == "PUT" && result.path.contains("/accounts/")) {
                try { current = decodeResponse<Account>(ApiClient(saved.origin).request(result.path, session = saved)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            if (result.errorCode == "version_conflict" && result.path.contains("/categories/")) {
                try { category = decodeResponse<Category>(ApiClient(saved.origin).request(result.path, session = saved)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            if (result.errorCode == "version_conflict" && result.path.contains("/tags/")) {
                try { tag = decodeResponse<Tag>(ApiClient(saved.origin).request(result.path, session = saved)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            state = state.copy(busy = false, accountConflict = current, transactionConflict = transaction, categoryConflict = category, tagConflict = tag,
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
                val finance = readFinance(requireNotNull(candidate))
                val accounts = finance.accounts; val history = finance.transactions; val categories = finance.categories; val tags = finance.tags
                val page = state.historyFilter?.let { LocalHistoryStore(app.database).page(candidate ?: saved, it, state.historyLimit) }
                val collection = if (command.state == "confirmed") collectionReceipt(command) ?: state.collectionReceipt else state.collectionReceipt
                val receipt = if (command.state == "confirmed") transactionReceipt(command) ?: state.transactionReceipt else state.transactionReceipt
                val classification = if (command.state == "confirmed") classificationReceipt(command) ?: state.classificationReceipt else state.classificationReceipt
                require(app.database.commands().remove(command.commandId, command.state) == 1)
                state = state.copy(busy = false, accounts = accounts, accountsLoaded = true, overviewLoaded = true, overviewError = "", transactions = history, transactionsLoaded = true, categories = categories, tags = tags, financialCommand = null, financeBlocked = state.localPending > 0 || state.localBlocked > 0,
                    historyItems = page?.items, historyDataset = page?.dataset, historyLocalCursor = page?.next, historyLocal = page != null, historyNextCursor = null, historyCursors = emptyList(), historyError = "",
                    transactionReceipt = receipt, collectionReceipt = collection, classificationReceipt = classification,
                    accountConflict = null, transactionConflict = null, categoryConflict = null, tagConflict = null, message = if (command.state == "confirmed") savedMessage(command.path) else "Данные обновлены. Проверьте поля перед новой командой.")
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
        if (state.busy || state.offline || command.state != "pending" || command.origin != saved.origin || command.ownerId != saved.ownerId ||
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
        if (state.busy || (state.financeBlocked && !state.logoutPending && !(state.offline && state.financialCommand == null && state.localPending == 0 && state.localBlocked == 0)) || state.revokePendingId != null) return
        state = AuthState(saved.origin, auth = state.auth, logoutPending = true)
        viewModelScope.launch {
            try {
                app.notificationInbox.pauseCapture()
                val pending = StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, saved.generationId, saved.sessionId, true)
                app.credentialVault.save(pending)
                candidate = pending
                try {
                    val acknowledgement = decodeResponse<Acknowledgement>(ApiClient(saved.origin).request("/auth/logout", "POST", session = pending))
                    require(acknowledgement.ok)
                } catch (e: ApiFailure) { if (e.status != 401) throw e }
                app.notificationInbox.pauseCapture()
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
    error is ApiFailure && error.code == "registration_disabled" -> "Эта возможность на сервере выключена. Обратитесь к владельцу сервера."
    error is ApiFailure && error.status == 429 -> "Слишком много попыток. Попробуйте позже."
    error is ApiFailure && error.status in 400..499 -> "Сервер отклонил запрос. Проверьте введенные данные."
    authAttempt -> "Ответ не получен. Вход мог выполниться. Повторите вход после восстановления связи."
    else -> "Не удалось связаться с сервером. Повторите проверку сессии."
}.let { it + ((error as? ApiFailure)?.requestReference ?: "") }

private fun savedMessage(path: String): String = when {
    path.endsWith("/transactions/classification") -> "Классификация выбранных операций сохранена"
    path.endsWith("/adjustments") -> "Операция сохранена"
    path.contains("/accounts") -> "Счет сохранен"
    path.contains("/categories") -> "Категория сохранена"
    path.contains("/tags") -> "Тег сохранен"
    else -> "Операция сохранена"
}

private fun transactionReceipt(command: FinancialCommand): TransactionReceipt? {
    if (command.path.endsWith("/transactions/classification")) return null
    if (!command.path.contains("/transactions") && !command.path.endsWith("/adjustments")) return null
    val id = if (command.method == "POST") ApiClient.json.parseToJsonElement(command.body).jsonObject.getValue("id").jsonPrimitive.content
        else command.path.substringAfterLast('/')
    val kind = ApiClient.json.parseToJsonElement(command.body).jsonObject["kind"]?.jsonPrimitive?.content
        ?: if (command.path.endsWith("/adjustments")) "adjustment" else null
    val records = decodeResponse<MutationResult>(requireNotNull(command.result)).transactions
    require(command.method == "DELETE" || records.any { it.id == id })
    return TransactionReceipt(command.commandId, id, command.method == "DELETE", command.method == "POST", kind, records)
}

private fun classificationReceipt(command: FinancialCommand): ClassificationReceipt? {
    if (command.path != "/workspaces/${command.workspaceId}/transactions/classification" || command.method != "POST") return null
    val request = decodeResponse<BulkClassification>(command.body)
    val records = decodeResponse<MutationResult>(requireNotNull(command.result)).transactions
    val ids = request.items.map { it.id }.toSet()
    require(request.items.size in 1..100 && ids.size == request.items.size && records.size == ids.size && records.map { it.id }.toSet() == ids)
    require(records.all { record -> record.workspace_id == command.workspaceId &&
        java.math.BigInteger(record.version) == java.math.BigInteger(request.items.single { it.id == record.id }.expected_version) + java.math.BigInteger.ONE &&
        record.allocations.single().category_id == request.category_id && record.tag_ids.toSet() == request.tag_ids.toSet() })
    return ClassificationReceipt(command.commandId, ids)
}

private fun collectionReceipt(command: FinancialCommand): CollectionReceipt? {
    val match = Regex("^/workspaces/([0-9a-f-]{36})/(accounts|categories|tags)(?:/([0-9a-f-]{36}))?$").matchEntire(command.path) ?: return null
    require(match.groupValues[1] == command.workspaceId && command.method in listOf("POST", "PUT"))
    val collection = match.groupValues[2]
    val id = if (command.method == "POST") ApiClient.json.parseToJsonElement(command.body).jsonObject.getValue("id").jsonPrimitive.content else match.groupValues[3]
    val result = decodeResponse<MutationResult>(requireNotNull(command.result))
    val valid = when (collection) {
        "accounts" -> result.accounts.any { it.id == id && it.workspace_id == command.workspaceId }
        "categories" -> result.categories.any { it.id == id && it.workspace_id == command.workspaceId }
        else -> result.tags.any { it.id == id && it.workspace_id == command.workspaceId }
    }
    require(valid)
    return CollectionReceipt(command.commandId, collection, id, command.method == "POST", result)
}
