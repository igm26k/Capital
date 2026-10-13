package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession

class OfflineClassificationStore(private val db: CapitalDatabase) {
    suspend fun enqueue(saved: StoredSession, body: String): OutboxCommand = db.withTransaction {
        require(IdentityStore(db).read(saved) != null && db.commands().get(saved.origin, saved.ownerId, saved.workspaceId) == null)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val projection = requireNotNull(LocalProjectionStore(db).read(scope))
        require(projection.confirmed.head.generation == saved.generationId && projection.blocked.isEmpty())
        val input = decodeResponse<BulkClassification>(body)
        require(input.items.size in 1..100 && input.items.map { it.id }.toSet().size == input.items.size)
        require(input.tag_ids.size <= 50 && input.tag_ids.toSet().size == input.tag_ids.size)
        val selected = input.items.map { requested ->
            projection.visible.transactions.single { it.id == requested.id }.also {
                require(it.version == requested.expected_version && it.kind in setOf("income", "expense") && it.status == "posted" && it.parent_transaction_id == null && it.entries.size == 1 && it.allocations.size == 1)
                require(projection.visible.transactions.none { child -> child.kind == "refund" && child.parent_transaction_id == it.id })
                require(projection.visible.accounts.any { account -> account.id == it.entries.single().account_id && account.archived_at == null })
            }
        }
        input.category_id?.let { id ->
            val category = projection.visible.categories.single { it.id == id }
            require(category.archived_at == null || selected.all { it.allocations.single().category_id == id })
        }
        for (id in input.tag_ids) {
            val tag = projection.visible.tags.single { it.id == id }
            require(tag.archived_at == null || selected.all { id in it.tag_ids })
        }
        val refs = selected.map { "transaction" to it.id } + selected.map { "account" to it.entries.single().account_id } +
            input.tag_ids.map { "tag" to it } + listOfNotNull(input.category_id).map { "category" to it }
        val dependencies = mutableSetOf<String>()
        for (ref in refs.distinct()) for (parent in projection.pending.asReversed()) {
            if (db.outbox().overlays(parent.position).any { (it.entityType to it.entityId) == ref }) { dependencies.add(parent.actionId); break }
        }
        val now = ledgerNow()
        val effects = selected.map { old -> OverlayEffect.upsert(SnapshotItemTransaction("transaction", old.copy(version = ledgerBump(old.version), updated_at = now,
            allocations = listOf(old.allocations.single().copy(category_id = input.category_id)), tag_ids = input.tag_ids))) }
        val command = OutboxStore(db).enqueue(saved, "transactions/classification", "POST", body,
            selected.map { OutboxBase("transaction", it.id, it.version) }, dependencies.toList(), effects)
        LocalProjectionStore(db).read(scope)
        command
    }
}
