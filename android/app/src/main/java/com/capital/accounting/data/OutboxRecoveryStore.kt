package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.sync.SyncGate

/** The immutable request and its failure evidence remain available after explicit local dismissal. */
data class OutboxDismissalPlan(val commands: List<OutboxCommand>) {
    override fun toString() = "OutboxDismissalPlan([redacted])"
}
class OutboxRecoveryStore(private val db: CapitalDatabase) {
    private suspend fun planLocked(saved: StoredSession, actionId: String): OutboxDismissalPlan {
        if (IdentityStore(db).read(saved) == null) throw ApiFailure(401, "unauthenticated")
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        require(db.snapshots().head(scope.key)?.generation == saved.generationId)
        val commands = db.outbox().commands(scope.key)
        val root = commands.single { it.actionId == actionId }
        require(root.state in setOf("queued", "rejected"))
        val ids = mutableSetOf(root.actionId)
        for (command in commands) {
            if (ApiClient.json.decodeFromString<List<String>>(command.dependencies).any { it in ids }) ids.add(command.actionId)
        }
        val branch = commands.filter { it.actionId in ids && it.state != "dismissed" }
        for (command in branch) {
            require(command.state in setOf("queued", "rejected"))
            require(db.receipts().get(command.position) == null)
            if (command.state == "rejected") {
                val failure = requireNotNull(db.failures().get(command.position))
                require(failure.status != null && failure.status in 400..499 && failure.status !in setOf(401, 408, 425, 429))
                require(failure.code !in setOf("action_result_expired", "idempotency_conflict", "sync_generation_conflict"))
                require(db.outcomes().get(command.position)?.state != "applied")
            }
        }
        return OutboxDismissalPlan(branch)
    }
    suspend fun preview(saved: StoredSession, actionId: String): OutboxDismissalPlan = db.withTransaction { planLocked(saved, actionId) }
    /** A stale preview cannot acknowledge a different branch or race a sender. */
    suspend fun dismiss(saved: StoredSession, expected: OutboxDismissalPlan) {
        require(expected.commands.isNotEmpty())
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        SyncGate.serialized(scope) {
            db.withTransaction {
                val actual = planLocked(saved, expected.commands.first().actionId)
                require(actual == expected)
                for (command in actual.commands) require(db.outbox().transition(scope.key, command.actionId, command.state, "dismissed") == 1)
            }
        }
    }
}
