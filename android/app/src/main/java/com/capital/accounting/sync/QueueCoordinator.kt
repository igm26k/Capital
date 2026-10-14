package com.capital.accounting.sync

import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class QueueDrainStatus { IDLE, MORE, BLOCKED }
data class QueueDrainResult(val completed: Int, val status: QueueDrainStatus)

/** A bounded drain composes existing scope-serialized read/send operations without nested SyncGate locks. */
class QueueCoordinator(private val db: CapitalDatabase) {
    suspend fun drain(session: StoredSession, maximum: Int = 20): QueueDrainResult {
        require(maximum in 1..100 && !session.logoutPending && session.revokeSessionId == null)
        val scope = MirrorScope(session.origin, session.ownerId, session.workspaceId)
        return QueueDrainGate.serialized(scope) {
            var completed = 0
            var mirror = MirrorSynchronizer(db).refresh(session)
            repeat(maximum) {
                // Preserve the existing single-command editor's explicit ACK/recovery contract.
                if (db.commands().get(scope.origin, scope.owner, scope.workspace) != null)
                    return@serialized QueueDrainResult(completed, QueueDrainStatus.BLOCKED)
                var head = db.outbox().commands(scope.key).firstOrNull { it.state !in setOf("applied", "dismissed") }
                    ?: return@serialized QueueDrainResult(completed, QueueDrainStatus.IDLE)
                if (head.state == "rejected") return@serialized QueueDrainResult(completed, QueueDrainStatus.BLOCKED)
                if (head.state in setOf("sending", "uncertain", "reconcile")) {
                    val evidence = OutboxReconciler(db).inspect(session, head.actionId)
                    if (evidence?.state == "applied") {
                        completed++; mirror = MirrorSynchronizer(db).refresh(session); return@repeat
                    }
                    if (evidence?.state == "rejected" || head.state == "reconcile")
                        return@serialized QueueDrainResult(completed, QueueDrainStatus.BLOCKED)
                    head = requireNotNull(db.outbox().commands(scope.key).find { it.actionId == head.actionId })
                }
                // Unknown outcomes never acquire a new issuer, generation, key or body.
                if (head.issuerSession != session.sessionId || head.generation != session.generationId || mirror.head.generation != head.generation)
                    return@serialized QueueDrainResult(completed, QueueDrainStatus.BLOCKED)
                val sent = OutboxSender(db).sendNext(session)
                    ?: return@serialized QueueDrainResult(completed, QueueDrainStatus.BLOCKED)
                when (sent.state) {
                    "applied" -> { completed++; mirror = MirrorSynchronizer(db).refresh(session) }
                    "reconcile" -> {
                        if (OutboxReconciler(db).inspect(session, sent.actionId)?.state != "applied")
                            return@serialized QueueDrainResult(completed, QueueDrainStatus.BLOCKED)
                        completed++; mirror = MirrorSynchronizer(db).refresh(session)
                    }
                    else -> return@serialized QueueDrainResult(completed, QueueDrainStatus.BLOCKED)
                }
            }
            val remaining = db.outbox().commands(scope.key).any { it.state !in setOf("applied", "dismissed") }
            QueueDrainResult(completed, if (remaining) QueueDrainStatus.MORE else QueueDrainStatus.IDLE)
        }
    }
}
private object QueueDrainGate {
    private val gates = mutableMapOf<String, Mutex>()
    suspend fun <T> serialized(scope: MirrorScope, work: suspend () -> T): T {
        val gate = synchronized(gates) { gates[scope.key] ?: Mutex().also { gates[scope.key] = it } }
        return gate.withLock { work() }
    }
}
