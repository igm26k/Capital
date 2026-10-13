package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.finance.transferRate
import java.math.BigInteger
import java.util.TimeZone
import java.util.UUID

class OfflineTransferStore(private val db: CapitalDatabase) {
    suspend fun create(saved: StoredSession, body: String): OutboxCommand = db.withTransaction {
        require(IdentityStore(db).read(saved) != null)
        require(db.commands().get(saved.origin, saved.ownerId, saved.workspaceId) == null)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val projection = requireNotNull(LocalProjectionStore(db).read(scope))
        require(projection.confirmed.head.generation == saved.generationId && projection.blocked.isEmpty())
        val input = decodeResponse<TransferCreate>(body)
        require(input.source_account_id != input.target_account_id)
        require(input.occurred_timezone in TimeZone.getAvailableIDs() && input.note.length <= 2000 && input.payee.length <= 200)
        val ids = (listOf(input.source_account_id, input.target_account_id) + listOfNotNull(input.fee?.account_id)).distinct()
        val accounts = ids.map { id -> projection.visible.accounts.single { it.id == id } }
        val occurred = ledgerInstant(input.occurred_at)
        require(occurred <= BigInteger.valueOf(System.currentTimeMillis()).multiply(BigInteger.valueOf(1000)))
        accounts.forEach { require(it.archived_at == null && occurred >= ledgerInstant(it.opened_at)) }
        val source = accounts.single { it.id == input.source_account_id }
        val target = accounts.single { it.id == input.target_account_id }
        val debit = ledgerPositive(input.source_amount_minor, source.currency)
        val credit = ledgerPositive(input.target_amount_minor, target.currency)
        val rate = transferRate(input.source_amount_minor, input.target_amount_minor, source.currency, target.currency)
        input.rate?.let {
            require(listOf(it.numerator, it.denominator).all { value -> value.matches(Regex("[1-9][0-9]{0,39}")) })
            require(BigInteger(it.numerator) * BigInteger(rate.denominator) == BigInteger(it.denominator) * BigInteger(rate.numerator))
        }
        fun tags(values: List<String>) {
            require(values.size <= 50 && values.toSet().size == values.size)
            require(values.all { id -> projection.visible.tags.any { it.id == id && it.archived_at == null } })
        }
        tags(input.tag_ids)
        require(projection.visible.transactions.none { it.id == input.id || it.id == input.fee?.id })
        val deltas = mutableMapOf(source.id to debit.negate(), target.id to credit)
        val now = ledgerNow()
        val transfer = Transaction(input.id, saved.workspaceId, "1", now, now, null, "transfer", "posted", input.occurred_at,
            input.occurred_timezone, input.note, input.payee, null, null, input.fee?.id, rate,
            listOf(Entry(UUID.randomUUID().toString(), source.id, source.currency, debit.negate().toString()),
                Entry(UUID.randomUUID().toString(), target.id, target.currency, credit.toString())), emptyList(), input.tag_ids)
        val effects = mutableListOf(OverlayEffect.upsert(SnapshotItemTransaction("transaction", transfer)))
        input.fee?.let { fee ->
            require(fee.id != input.id && fee.note.length <= 2000)
            val account = accounts.single { it.id == fee.account_id }
            val amount = ledgerPositive(fee.amount_minor, account.currency)
            tags(fee.tag_ids)
            require(fee.allocations.size in 1..100 && fee.allocations.map { it.id }.toSet().size == fee.allocations.size)
            require(fee.allocations.all { part -> part.category_id == null || projection.visible.categories.any { it.id == part.category_id && it.archived_at == null } })
            require(fee.allocations.fold(BigInteger.ZERO) { sum, part -> sum + ledgerPositive(part.amount_minor, account.currency) } == amount)
            deltas[account.id] = (deltas[account.id] ?: BigInteger.ZERO) - amount
            val transaction = Transaction(fee.id, saved.workspaceId, "1", now, now, null, "expense", "posted", input.occurred_at,
                input.occurred_timezone, fee.note, input.payee, null, input.id, null, null,
                listOf(Entry(UUID.randomUUID().toString(), account.id, account.currency, amount.negate().toString())),
                fee.allocations.map { Allocation(it.id, it.category_id, it.amount_minor, null, it.amount_minor) }, fee.tag_ids)
            effects.add(OverlayEffect.upsert(SnapshotItemTransaction("transaction", transaction)))
        }
        accounts.forEach { effects.add(OverlayEffect.upsert(SnapshotItemAccount("account", ledgerAccountDelta(it, requireNotNull(deltas[it.id]), now)))) }
        val refs = accounts.map { "account" to it.id } + (input.tag_ids + (input.fee?.tag_ids ?: emptyList())).map { "tag" to it } +
            input.fee?.allocations.orEmpty().mapNotNull { it.category_id?.let { id -> "category" to id } }
        val dependencies = mutableSetOf<String>()
        for (ref in refs.distinct()) for (parent in projection.pending.asReversed()) {
            if (db.outbox().overlays(parent.position).any { (it.entityType to it.entityId) == ref }) { dependencies.add(parent.actionId); break }
        }
        val command = OutboxStore(db).enqueue(saved, "transactions", "POST", body, accounts.map { OutboxBase("account", it.id, it.version) }, dependencies.toList(), effects)
        LocalProjectionStore(db).read(scope)
        command
    }
}
