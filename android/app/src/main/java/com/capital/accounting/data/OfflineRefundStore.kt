package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import java.math.BigInteger
import java.util.TimeZone
import java.util.UUID

class OfflineRefundStore(private val db: CapitalDatabase) {
    suspend fun create(saved: StoredSession, body: String): OutboxCommand = db.withTransaction { save(saved, body, null) }
    suspend fun replace(saved: StoredSession, body: String, id: String): OutboxCommand = db.withTransaction { save(saved, body, id) }
    private suspend fun projection(saved: StoredSession): LocalProjection {
        require(IdentityStore(db).read(saved) != null && db.commands().get(saved.origin, saved.ownerId, saved.workspaceId) == null)
        return requireNotNull(LocalProjectionStore(db).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))).also {
            require(it.confirmed.head.generation == saved.generationId && it.blocked.isEmpty())
        }
    }
    private suspend fun dependencies(projection: LocalProjection, refs: List<Pair<String, String>>): List<String> {
        val result = mutableSetOf<String>()
        for (ref in refs.distinct()) for (command in projection.pending.asReversed()) {
            if (db.outbox().overlays(command.position).any { (it.entityType to it.entityId) == ref }) { result.add(command.actionId); break }
        }
        return result.toList()
    }
    private suspend fun save(saved: StoredSession, body: String, id: String?): OutboxCommand {
        val projection = projection(saved)
        val old = id?.let { projection.visible.transactions.single { it.id == id }.also { require(it.kind == "refund" && it.status == "posted") } }
        val input = if (old == null) decodeResponse<RefundCreate>(body) else decodeResponse<RefundReplace>(body).let {
            require(it.expected_version == old.version && it.parent_transaction_id == old.parent_transaction_id)
            RefundCreate(old.id, it.occurred_at, it.occurred_timezone, it.note, it.payee, it.tag_ids, it.kind, it.account_id, it.amount_minor,
                it.parent_transaction_id, it.expected_parent_version, it.expected_transfer_version, it.allocations)
        }
        if (old == null) require(projection.visible.transactions.none { it.id == input.id })
        val parent = projection.visible.transactions.single { it.id == input.parent_transaction_id }
        require(parent.kind == "expense" && parent.status == "posted" && parent.version == input.expected_parent_version)
        val transfer = parent.parent_transaction_id?.let { transferId -> projection.visible.transactions.single { it.id == transferId }.also {
            require(it.kind == "transfer" && it.status == "posted" && it.fee_transaction_id == parent.id)
        } }
        require(input.expected_transfer_version == transfer?.version)
        val parentAccount = projection.visible.accounts.single { it.id == parent.entries.single().account_id }
        val receiver = projection.visible.accounts.single { it.id == input.account_id }
        val oldAccount = old?.entries?.single()?.account_id?.let { accountId -> projection.visible.accounts.single { it.id == accountId } }
        val accounts = listOfNotNull(parentAccount, receiver, oldAccount).distinctBy { it.id }
        accounts.forEach { require(it.archived_at == null && it.currency == parentAccount.currency) }
        require(input.occurred_timezone in TimeZone.getAvailableIDs() && input.note.length <= 2000 && input.payee.length <= 200)
        val occurred = ledgerInstant(input.occurred_at)
        require(occurred >= ledgerInstant(parent.occurred_at) && occurred >= ledgerInstant(receiver.opened_at) && occurred <= BigInteger.valueOf(System.currentTimeMillis()).multiply(BigInteger.valueOf(1000)))
        val amount = ledgerPositive(input.amount_minor, receiver.currency)
        require(input.tag_ids.size <= 50 && input.tag_ids.toSet().size == input.tag_ids.size)
        require(input.tag_ids.all { tagId -> projection.visible.tags.any { it.id == tagId && (it.archived_at == null || tagId in old?.tag_ids.orEmpty()) } })
        require(input.allocations.size in 1..100 && input.allocations.map { it.id }.toSet().size == input.allocations.size && input.allocations.map { it.original_allocation_id }.toSet().size == input.allocations.size)
        require(input.allocations.fold(BigInteger.ZERO) { sum, part -> sum + ledgerPositive(part.amount_minor, receiver.currency) } == amount)
        val oldParts = old?.allocations.orEmpty()
        val originals = parent.allocations.associateBy { it.id }
        for (part in input.allocations) {
            val original = requireNotNull(originals[part.original_allocation_id])
            val existing = oldParts.find { it.id == part.id }
            require(existing == null || existing.original_allocation_id == part.original_allocation_id)
            require(oldParts.none { it.original_allocation_id == part.original_allocation_id && it.id != part.id })
            val own = oldParts.find { it.original_allocation_id == original.id }?.amount_minor ?: "0"
            require(BigInteger(part.amount_minor) <= BigInteger(original.remaining_refundable_minor) + BigInteger(own))
        }
        val oldEntry = old?.entries?.single()
        val movementChanged = oldEntry == null || oldEntry.account_id != receiver.id || oldEntry.amount_minor != input.amount_minor || ledgerInstant(old.occurred_at) != occurred
        val financialChanged = movementChanged || oldParts.associate { it.id to (it.original_allocation_id to it.amount_minor) } != input.allocations.associate { it.id to (it.original_allocation_id to it.amount_minor) }
        val now = ledgerNow()
        val refund = Transaction(input.id, saved.workspaceId, old?.let { ledgerBump(it.version) } ?: "1", old?.created_at ?: now, now, null,
            "refund", "posted", input.occurred_at, input.occurred_timezone, input.note, input.payee, null, parent.id, null, null,
            listOf(Entry(oldEntry?.id ?: UUID.randomUUID().toString(), receiver.id, receiver.currency, input.amount_minor)),
            input.allocations.map { Allocation(it.id, originals.getValue(it.original_allocation_id).category_id, it.amount_minor, it.original_allocation_id, "0") }, input.tag_ids)
        val effects = mutableListOf(OverlayEffect.upsert(SnapshotItemTransaction("transaction", refund)))
        if (financialChanged) {
            val changed = parent.copy(version = ledgerBump(parent.version), updated_at = now, allocations = parent.allocations.map { part ->
                val own = oldParts.find { it.original_allocation_id == part.id }?.amount_minor ?: "0"
                val desired = input.allocations.find { it.original_allocation_id == part.id }?.amount_minor ?: "0"
                part.copy(remaining_refundable_minor = (BigInteger(part.remaining_refundable_minor) + BigInteger(own) - BigInteger(desired)).toString())
            })
            effects.add(OverlayEffect.upsert(SnapshotItemTransaction("transaction", changed)))
            transfer?.let { effects.add(OverlayEffect.upsert(SnapshotItemTransaction("transaction", it.copy(version = ledgerBump(it.version), updated_at = now)))) }
        }
        if (movementChanged) for (account in listOfNotNull(receiver, oldAccount).distinctBy { it.id }) {
            val delta = (if (account.id == receiver.id) amount else BigInteger.ZERO) - (if (account.id == oldEntry?.account_id) BigInteger(oldEntry.amount_minor) else BigInteger.ZERO)
            effects.add(OverlayEffect.upsert(SnapshotItemAccount("account", ledgerAccountDelta(account, delta, now))))
        }
        val transactions = listOfNotNull(old, parent, transfer)
        val refs = accounts.map { "account" to it.id } + transactions.map { "transaction" to it.id } + input.tag_ids.map { "tag" to it }
        val bases = accounts.map { OutboxBase("account", it.id, it.version) } + transactions.map { OutboxBase("transaction", it.id, it.version) }
        val command = OutboxStore(db).enqueue(saved, if (id == null) "transactions" else "transactions/$id", if (id == null) "POST" else "PUT", body, bases, dependencies(projection, refs), effects)
        LocalProjectionStore(db).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))
        return command
    }
    suspend fun delete(saved: StoredSession, body: String, id: String): OutboxCommand = db.withTransaction {
        val projection = projection(saved); val refund = projection.visible.transactions.single { it.id == id }
        require(refund.kind == "refund" && refund.status == "posted")
        val parent = projection.visible.transactions.single { it.id == refund.parent_transaction_id }
        require(parent.kind == "expense" && parent.status == "posted")
        val transfer = parent.parent_transaction_id?.let { transferId -> projection.visible.transactions.single { it.id == transferId } }
        val parents = listOfNotNull(parent, transfer); val transactions = listOf(refund) + parents
        val input = decodeResponse<TransactionDelete>(body)
        require(input.expected_version == refund.version && input.related_versions.size == parents.size && input.related_versions.associate { it.id to it.version } == parents.associate { it.id to it.version })
        transactions.forEach { ledgerBump(it.version) }
        val entry = refund.entries.single()
        val accounts = transactions.flatMap { it.entries }.map { it.account_id }.distinct().map { accountId -> projection.visible.accounts.single { it.id == accountId } }
        accounts.forEach { require(it.archived_at == null) }
        val now = ledgerNow()
        val changed = parent.copy(version = ledgerBump(parent.version), updated_at = now, allocations = parent.allocations.map { part ->
            part.copy(remaining_refundable_minor = (BigInteger(part.remaining_refundable_minor) + BigInteger(refund.allocations.find { it.original_allocation_id == part.id }?.amount_minor ?: "0")).toString())
        })
        val effects = mutableListOf(OverlayEffect.delete("transaction", id), OverlayEffect.upsert(SnapshotItemTransaction("transaction", changed)),
            OverlayEffect.upsert(SnapshotItemAccount("account", ledgerAccountDelta(accounts.single { it.id == entry.account_id }, BigInteger(entry.amount_minor).negate(), now))))
        transfer?.let { effects.add(OverlayEffect.upsert(SnapshotItemTransaction("transaction", it.copy(version = ledgerBump(it.version), updated_at = now)))) }
        val refs = accounts.map { "account" to it.id } + transactions.map { "transaction" to it.id }
        val bases = accounts.map { OutboxBase("account", it.id, it.version) } + transactions.map { OutboxBase("transaction", it.id, it.version) }
        val command = OutboxStore(db).enqueue(saved, "transactions/$id", "DELETE", body, bases, dependencies(projection, refs), effects)
        LocalProjectionStore(db).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))
        command
    }
}
