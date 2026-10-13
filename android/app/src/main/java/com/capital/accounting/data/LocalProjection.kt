package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*

/** Confirmed versions stay authoritative; visible local changes are explicitly marked pending. */
data class LocalProjection(val confirmed: ConfirmedMirror, val visible: MirrorFinance,
    val pending: List<OutboxCommand>, val blocked: List<OutboxCommand>, val affected: Set<Pair<String, String>>) {
    override fun toString() = "LocalProjection([redacted])"
}

class LocalProjectionStore(private val db: CapitalDatabase) {
    suspend fun read(scope: MirrorScope): LocalProjection? = db.withTransaction {
        val mirror = SnapshotStore(db).read(scope) ?: return@withTransaction null
        val items = mirror.items.associateBy { it.entity_type to snapshotIdentity(it).id }.toMutableMap()
        val commands = db.outbox().commands(scope.key)
        val blockedIds = mutableSetOf<String>()
        val pending = mutableListOf<OutboxCommand>(); val blocked = mutableListOf<OutboxCommand>()
        val affected = mutableSetOf<Pair<String, String>>()
        for (command in commands) {
            val effects = db.outbox().overlays(command.position)
            if (command.state == "applied" && effects.isEmpty()) continue
            val dependencies = ApiClient.json.decodeFromString<List<String>>(command.dependencies)
            val unavailable = command.generation != mirror.head.generation || command.state !in setOf("queued", "sending", "uncertain", "applied") ||
                dependencies.any { it in blockedIds }
            if (unavailable) { blockedIds.add(command.actionId); blocked.add(command); continue }
            if (effects.isEmpty()) continue // Applied and covered commands no longer affect presentation.
            pending.add(command)
            for (effect in effects) {
                val key = effect.entityType to effect.entityId
                when (effect.operation) {
                    "delete" -> { require(effect.payload == null); items.remove(key) }
                    "opening" -> {
                        validateLocalOpening(command.path, command.method, command.body, command.workspace,
                            OverlayEffect(effect.entityType, effect.entityId, effect.operation, effect.payload))
                        items[key] = decodeResponse<SnapshotItem>(requireNotNull(effect.payload))
                    }
                    "upsert" -> {
                        val item = decodeResponse<SnapshotItem>(requireNotNull(effect.payload)); val identity = snapshotIdentity(item)
                        require(item.entity_type == effect.entityType && identity.id == effect.entityId && identity.workspace == scope.workspace && identity.deleted == null)
                        items[key] = item
                    }
                    else -> error("Invalid local effect")
                }
                affected.add(key)
            }
        }
        val projected = items.values.toList()
        validateSnapshotGraph(projected)
        LocalProjection(mirror, ConfirmedMirror(mirror.head, projected).finance(), pending, blocked, affected)
    }
}
