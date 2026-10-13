package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class OfflineCategoryStore(private val db: CapitalDatabase) {
    suspend fun enqueue(saved: StoredSession, body: String, id: String? = null): OutboxCommand = db.withTransaction {
        require(IdentityStore(db).read(saved) != null)
        require(db.commands().get(saved.origin, saved.ownerId, saved.workspaceId) == null)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val projection = requireNotNull(LocalProjectionStore(db).read(scope))
        require(projection.confirmed.head.generation == saved.generationId && projection.blocked.isEmpty())
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val item: Category; val bases: List<OutboxBase>; val previousParent: String?
        if (id == null) {
            val input = decodeResponse<CategoryCreate>(body); require(input.name.isNotBlank() && input.name.length <= 100)
            require(projection.visible.categories.none { it.id == input.id })
            item = Category(input.id, saved.workspaceId, "1", now, now, null, input.name, null, input.parent_id)
            bases = emptyList(); previousParent = null
        } else {
            val input = decodeResponse<CategoryUpdate>(body); require(input.name.isNotBlank() && input.name.length <= 100)
            val visible = requireNotNull(projection.visible.categories.find { it.id == id }); require(input.expected_version == visible.version)
            val next = BigInteger(visible.version) + BigInteger.ONE; require(next <= BigInteger.valueOf(Long.MAX_VALUE))
            item = visible.copy(version = next.toString(), updated_at = now, name = input.name, parent_id = input.parent_id,
                archived_at = if (input.archived) visible.archived_at ?: now else null)
            bases = listOf(OutboxBase("category", id, input.expected_version)); previousParent = visible.parent_id
        }
        item.parent_id?.let { parentId ->
            require(parentId != item.id)
            val parent = requireNotNull(projection.visible.categories.find { it.id == parentId })
            require(parent.archived_at == null || previousParent == parentId)
        }
        val dependencies = mutableSetOf<String>()
        for (entity in listOfNotNull(id, item.parent_id).distinct()) {
            for (parent in projection.pending.asReversed()) {
                if (db.outbox().overlays(parent.position).any { it.entityType == "category" && it.entityId == entity }) {
                    dependencies.add(parent.actionId); break
                }
            }
        }
        val command = OutboxStore(db).enqueue(saved, if (id == null) "categories" else "categories/$id", if (id == null) "POST" else "PUT", body,
            bases, dependencies.toList(), listOf(OverlayEffect.upsert(SnapshotItemCategory("category", item))))
        LocalProjectionStore(db).read(scope) // Reject hierarchy cycles and roll back command + overlay together.
        command
    }
}
