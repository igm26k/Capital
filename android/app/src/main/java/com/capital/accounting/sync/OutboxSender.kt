package com.capital.accounting.sync

import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One ordered command per call. No bearer is persisted in the queue. */
class OutboxSender(private val db: CapitalDatabase) {
    suspend fun sendNext(session: StoredSession): OutboxCommand? {
        require(!session.logoutPending && session.revokeSessionId == null)
        val scope = MirrorScope(session.origin, session.ownerId, session.workspaceId)
        return SyncGate.serialized(scope) {
            val api = ApiClient(session.origin)
            val auth = decodeResponse<BearerAuth>(api.request("/auth/session", session = session))
            require(auth.profile.id == session.ownerId && auth.workspace.id == session.workspaceId &&
                auth.session.id == session.sessionId && auth.session.is_current && auth.workspace.sync_generation_id == session.generationId)
            val lifecycle = OutboxLifecycle(db)
            // Gate excludes every other sender and mirror installation during recovery/send.
            lifecycle.recoverInterrupted(scope)
            val command = lifecycle.claim(session) ?: return@serialized null
            try {
                val response = api.request(command.path, command.method, command.body, session, command.actionId, command.generation)
                OutboxReceiptStore(db).applied(command, response)
            } catch (e: ApiFailure) {
                val outcome = when {
                    e.code in setOf("action_result_expired", "idempotency_conflict", "sync_generation_conflict") -> "reconcile"
                    e.status in 400..499 && e.status !in setOf(401, 408, 425, 429) -> "rejected"
                    else -> "uncertain"
                }
                withContext(NonCancellable) { failIfSending(command, outcome, lifecycle) }
                if (outcome == "uncertain") throw e
            } catch (e: Exception) {
                // Cancellation, transport loss, malformed response and local receipt failure do not prove rejection.
                withContext(NonCancellable) { failIfSending(command, "uncertain", lifecycle) }
                throw e
            }
            requireNotNull(db.outbox().commands(scope.key).find { it.actionId == command.actionId })
        }
    }
    private suspend fun failIfSending(command: OutboxCommand, outcome: String, lifecycle: OutboxLifecycle) {
        // Cancellation may be delivered after Room has already committed the applied receipt.
        val current = requireNotNull(db.outbox().commands(command.scope).find { it.actionId == command.actionId })
        if (current.state == "sending") lifecycle.failed(command, outcome)
        else require(current == command.copy(state = "applied") && db.receipts().get(command.position) != null)
    }
}
