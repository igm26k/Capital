package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.ApiClient
import com.capital.accounting.api.ApiFailure
import com.capital.accounting.auth.StoredSession

/** Call recovery once on startup, before any sender, while holding the scope SyncGate. */
class OutboxLifecycle(private val db: CapitalDatabase) {
    suspend fun recoverInterrupted(scope: MirrorScope): Int = db.withTransaction {
        db.outbox().recoverInterrupted(scope.key)
    }

    /** Persists sending before HTTP. A blocked head must never be overtaken. */
    suspend fun claim(saved: StoredSession): OutboxCommand? = db.withTransaction {
        require(!saved.logoutPending && saved.revokeSessionId == null)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val commands = db.outbox().commands(scope.key)
        val head = commands.firstOrNull { it.state !in setOf("applied", "dismissed") } ?: return@withTransaction null
        if (head.state !in setOf("queued", "uncertain")) return@withTransaction null
        // A renewed bearer is permitted only for the original issuing session and generation.
        require(head.issuerSession == saved.sessionId && head.generation == saved.generationId)
        require(db.snapshots().head(scope.key)?.generation == head.generation)
        val dependencies = ApiClient.json.decodeFromString<List<String>>(head.dependencies)
        if (dependencies.any { dependency -> commands.none { it.actionId == dependency && it.state == "applied" } }) return@withTransaction null
        require(db.outbox().transition(scope.key, head.actionId, head.state, "sending") == 1)
        head.copy(state = "sending")
    }

    /** Only unresolved/terminal failure states are handled here; success requires a durable receipt. */
    suspend fun failed(command: OutboxCommand, outcome: String, failure: ApiFailure? = null): OutboxCommand = db.withTransaction {
        require(command.state == "sending" && outcome in setOf("uncertain", "rejected", "reconcile"))
        val stored = requireNotNull(db.outbox().commands(command.scope).find { it.actionId == command.actionId })
        require(stored == command)
        val code = failure?.code?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,95}")) }
        val status = failure?.status?.takeIf { it in 400..599 }
        db.failures().save(OutboxFailure(command.position, status, code, failure?.requestId))
        require(db.outbox().transition(command.scope, command.actionId, "sending", outcome) == 1)
        command.copy(state = outcome)
    }
}
