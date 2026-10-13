package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.finance.transferRate
import java.math.BigInteger
import java.util.TimeZone
import java.util.UUID

class OfflineTransferStore(private val db: CapitalDatabase) {
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
        for (ref in refs.distinct()) for (parent in projection.pending.asReversed()) {
            if (db.outbox().overlays(parent.position).any { (it.entityType to it.entityId) == ref }) { result.add(parent.actionId); break }
        }
        return result.toList()
    }
    private suspend fun save(saved: StoredSession, body: String, id: String?): OutboxCommand {
        val projection = projection(saved)
        val old = id?.let { projection.visible.transactions.single { it.id == id }.also { require(it.kind == "transfer" && it.status == "posted") } }
        val oldFee = old?.fee_transaction_id?.let { feeId -> projection.visible.transactions.single { it.id == feeId }.also { require(it.parent_transaction_id == id && it.kind == "expense" && it.status == "posted") } }
        val input = if (old == null) decodeResponse<TransferCreate>(body) else decodeResponse<TransferReplace>(body).let {
            require(it.expected_version == old.version && it.expected_fee_version == oldFee?.version)
            require(oldFee == null || it.fee == null || it.fee.id == oldFee.id)
            TransferCreate(old.id, it.occurred_at, it.occurred_timezone, it.note, it.payee, it.tag_ids, it.kind,
                it.source_account_id, it.target_account_id, it.source_amount_minor, it.target_amount_minor, it.rate, it.fee)
        }
        require(input.source_account_id != input.target_account_id)
        require(input.occurred_timezone in TimeZone.getAvailableIDs() && input.note.length <= 2000 && input.payee.length <= 200)
        if (old == null) require(projection.visible.transactions.none { it.id == input.id })
        if (input.fee != null && oldFee == null) require(projection.visible.transactions.none { it.id == input.fee.id })
        val accountIds = (listOf(input.source_account_id, input.target_account_id) + listOfNotNull(input.fee?.account_id) + old?.entries.orEmpty().map { it.account_id } + oldFee?.entries.orEmpty().map { it.account_id }).distinct()
        val accounts = accountIds.map { accountId -> projection.visible.accounts.single { it.id == accountId } }
        accounts.forEach { require(it.archived_at == null) }
        val occurred = ledgerInstant(input.occurred_at)
        require(occurred <= BigInteger.valueOf(System.currentTimeMillis()).multiply(BigInteger.valueOf(1000)))
        val newIds = listOfNotNull(input.source_account_id, input.target_account_id, input.fee?.account_id)
        accounts.filter { it.id in newIds }.forEach { require(occurred >= ledgerInstant(it.opened_at)) }
        val source = accounts.single { it.id == input.source_account_id }; val target = accounts.single { it.id == input.target_account_id }
        val oldSource = old?.entries?.single { it.amount_minor.startsWith("-") }; val oldTarget = old?.entries?.single { !it.amount_minor.startsWith("-") }
        if (old != null) require(source.currency == oldSource!!.currency && target.currency == oldTarget!!.currency)
        val debit = ledgerPositive(input.source_amount_minor, source.currency); val credit = ledgerPositive(input.target_amount_minor, target.currency)
        val rate = transferRate(input.source_amount_minor, input.target_amount_minor, source.currency, target.currency)
        input.rate?.let {
            require(listOf(it.numerator, it.denominator).all { value -> value.matches(Regex("[1-9][0-9]{0,39}")) })
            require(BigInteger(it.numerator) * BigInteger(rate.denominator) == BigInteger(it.denominator) * BigInteger(rate.numerator))
        }
        fun tags(values: List<String>, prior: List<String>) {
            require(values.size <= 50 && values.toSet().size == values.size)
            require(values.all { tagId -> projection.visible.tags.any { it.id == tagId && (it.archived_at == null || tagId in prior) } })
        }
        tags(input.tag_ids, old?.tag_ids.orEmpty())
        val deltas = mutableMapOf<String, BigInteger>()
        fun movement(previous: Entry?, next: Entry?, previousDate: String?) {
            if (previous != null && next != null && previous.account_id == next.account_id && previous.amount_minor == next.amount_minor && ledgerInstant(requireNotNull(previousDate)) == occurred) return
            for ((entry, sign) in listOf(previous to -1, next to 1)) if (entry != null)
                deltas[entry.account_id] = (deltas[entry.account_id] ?: BigInteger.ZERO) + BigInteger(entry.amount_minor) * BigInteger.valueOf(sign.toLong())
        }
        val from = Entry(oldSource?.id ?: UUID.randomUUID().toString(), source.id, source.currency, debit.negate().toString())
        val to = Entry(oldTarget?.id ?: UUID.randomUUID().toString(), target.id, target.currency, credit.toString())
        movement(oldSource, from, old?.occurred_at); movement(oldTarget, to, old?.occurred_at)
        val now = ledgerNow()
        val transfer = Transaction(input.id, saved.workspaceId, old?.let { ledgerBump(it.version) } ?: "1", old?.created_at ?: now, now, null,
            "transfer", "posted", input.occurred_at, input.occurred_timezone, input.note, input.payee, null, null, input.fee?.id, rate, listOf(from, to), emptyList(), input.tag_ids)
        val effects = mutableListOf(OverlayEffect.upsert(SnapshotItemTransaction("transaction", transfer)))
        val hasRefund = oldFee != null && projection.visible.transactions.any { it.kind == "refund" && it.parent_transaction_id == oldFee.id }
        input.fee?.let { fee ->
            require(fee.id != input.id && fee.note.length <= 2000)
            val account = accounts.single { it.id == fee.account_id }; val previous = oldFee?.entries?.single()
            require(previous == null || previous.currency == account.currency)
            val amount = ledgerPositive(fee.amount_minor, account.currency)
            tags(fee.tag_ids, oldFee?.tag_ids.orEmpty())
            require(fee.allocations.size in 1..100 && fee.allocations.map { it.id }.toSet().size == fee.allocations.size)
            require(fee.allocations.all { part -> part.category_id == null || projection.visible.categories.any { it.id == part.category_id &&
                (it.archived_at == null || oldFee?.allocations.orEmpty().any { prior -> prior.id == part.id && prior.category_id == part.category_id }) } })
            require(fee.allocations.fold(BigInteger.ZERO) { sum, part -> sum + ledgerPositive(part.amount_minor, account.currency) } == amount)
            val entry = Entry(previous?.id ?: UUID.randomUUID().toString(), account.id, account.currency, amount.negate().toString())
            if (hasRefund) require(previous!!.account_id == entry.account_id && previous.amount_minor == entry.amount_minor && ledgerInstant(oldFee!!.occurred_at) == occurred &&
                oldFee.allocations.associate { it.id to (it.amount_minor to it.category_id) } == fee.allocations.associate { it.id to (it.amount_minor to it.category_id) })
            movement(previous, entry, oldFee?.occurred_at)
            val parts = fee.allocations.map { part ->
                val prior = oldFee?.allocations?.find { it.id == part.id }
                val refunded = prior?.let { BigInteger(it.amount_minor) - BigInteger(it.remaining_refundable_minor) } ?: BigInteger.ZERO
                Allocation(part.id, part.category_id, part.amount_minor, null, (BigInteger(part.amount_minor) - refunded).toString())
            }
            val transaction = Transaction(fee.id, saved.workspaceId, oldFee?.let { ledgerBump(it.version) } ?: "1", oldFee?.created_at ?: now, now, null,
                "expense", "posted", input.occurred_at, input.occurred_timezone, fee.note, input.payee, null, input.id, null, null, listOf(entry), parts, fee.tag_ids)
            effects.add(OverlayEffect.upsert(SnapshotItemTransaction("transaction", transaction)))
        } ?: oldFee?.let {
            require(!hasRefund); ledgerBump(it.version)
            movement(it.entries.single(), null, it.occurred_at); effects.add(OverlayEffect.delete("transaction", it.id))
        }
        for ((accountId, delta) in deltas) effects.add(OverlayEffect.upsert(SnapshotItemAccount("account", ledgerAccountDelta(accounts.single { it.id == accountId }, delta, now))))
        val refs = accounts.map { "account" to it.id } + listOfNotNull(old?.id, oldFee?.id).map { "transaction" to it } +
            (input.tag_ids + input.fee?.tag_ids.orEmpty()).map { "tag" to it } + input.fee?.allocations.orEmpty().mapNotNull { it.category_id?.let { category -> "category" to category } }
        val bases = accounts.map { OutboxBase("account", it.id, it.version) } + listOfNotNull(old, oldFee).map { OutboxBase("transaction", it.id, it.version) }
        val command = OutboxStore(db).enqueue(saved, if (id == null) "transactions" else "transactions/$id", if (id == null) "POST" else "PUT", body, bases, dependencies(projection, refs), effects)
        LocalProjectionStore(db).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))
        return command
    }

    suspend fun delete(saved: StoredSession, body: String, id: String): OutboxCommand = db.withTransaction {
        val projection = projection(saved); val old = projection.visible.transactions.single { it.id == id }
        require(old.kind == "transfer" && old.status == "posted")
        val fee = old.fee_transaction_id?.let { feeId -> projection.visible.transactions.single { it.id == feeId } }
        val transactions = listOfNotNull(old, fee); val input = decodeResponse<TransactionDelete>(body)
        require(input.expected_version == old.version && input.related_versions == (fee?.let { listOf(VersionExpectation(it.id, it.version)) } ?: emptyList<VersionExpectation>()))
        require(projection.visible.transactions.none { it.kind == "refund" && it.parent_transaction_id in transactions.map { parent -> parent.id } })
        transactions.forEach { ledgerBump(it.version) }
        val accounts = transactions.flatMap { it.entries }.map { it.account_id }.distinct().map { accountId -> projection.visible.accounts.single { it.id == accountId } }
        accounts.forEach { require(it.archived_at == null) }
        val effects = transactions.map { OverlayEffect.delete("transaction", it.id) }.toMutableList()
        val now = ledgerNow()
        for (account in accounts) {
            val delta = transactions.flatMap { it.entries }.filter { it.account_id == account.id }.fold(BigInteger.ZERO) { sum, entry -> sum - BigInteger(entry.amount_minor) }
            effects.add(OverlayEffect.upsert(SnapshotItemAccount("account", ledgerAccountDelta(account, delta, now))))
        }
        val refs = accounts.map { "account" to it.id } + transactions.map { "transaction" to it.id }
        val bases = accounts.map { OutboxBase("account", it.id, it.version) } + transactions.map { OutboxBase("transaction", it.id, it.version) }
        val command = OutboxStore(db).enqueue(saved, "transactions/$id", "DELETE", body, bases, dependencies(projection, refs), effects)
        LocalProjectionStore(db).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))
        command
    }
}
