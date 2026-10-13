package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.finance.Money
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class OfflineBasicStore(private val db: CapitalDatabase) {
    suspend fun create(saved: StoredSession, body: String): OutboxCommand = db.withTransaction {
        require(IdentityStore(db).read(saved) != null)
        require(db.commands().get(saved.origin, saved.ownerId, saved.workspaceId) == null)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val projection = requireNotNull(LocalProjectionStore(db).read(scope))
        require(projection.confirmed.head.generation == saved.generationId && projection.blocked.isEmpty())
        val input = when (ApiClient.json.parseToJsonElement(body).jsonObject["kind"]?.jsonPrimitive?.content) {
            "expense" -> decodeResponse<ExpenseCreate>(body).let { BasicIntent(it.id, it.kind, it.account_id, it.amount_minor, it.occurred_at, it.occurred_timezone, it.note, it.payee, it.tag_ids, it.allocations) }
            "income" -> decodeResponse<IncomeCreate>(body).let { BasicIntent(it.id, it.kind, it.account_id, it.amount_minor, it.occurred_at, it.occurred_timezone, it.note, it.payee, it.tag_ids, it.allocations) }
            else -> error("Unsupported local transaction")
        }
        require(projection.visible.transactions.none { it.id == input.id })
        val account = requireNotNull(projection.visible.accounts.find { it.id == input.accountId })
        require(account.archived_at == null && input.timezone in TimeZone.getAvailableIDs())
        require(ledgerInstant(input.occurred) >= ledgerInstant(account.opened_at) && ledgerInstant(input.occurred) <= BigInteger.valueOf(System.currentTimeMillis()).multiply(BigInteger.valueOf(1000)))
        require(input.note.length <= 2000 && input.payee.length <= 200)
        val amount = ledgerPositive(input.amount, account.currency)
        require(input.tags.size <= 50 && input.tags.toSet().size == input.tags.size)
        require(input.tags.all { id -> projection.visible.tags.any { it.id == id && it.archived_at == null } })
        require(input.parts.size in 1..100 && input.parts.map { it.id }.toSet().size == input.parts.size)
        require(input.parts.all { part -> part.category_id == null || projection.visible.categories.any { it.id == part.category_id && it.archived_at == null } })
        require(input.parts.fold(BigInteger.ZERO) { sum, part -> sum + ledgerPositive(part.amount_minor, account.currency) } == amount)
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val signed = if (input.kind == "expense") amount.negate() else amount
        val posted = (BigInteger(account.posted_balance_minor) + signed).toString()
        val projected = (BigInteger(posted) + BigInteger(account.pending_delta_minor)).toString()
        Money.display(posted, account.currency); Money.display(projected, account.currency)
        val changed = account.copy(version = ledgerBump(account.version), balance_version = ledgerBump(account.balance_version), updated_at = now,
            posted_balance_minor = posted, projected_balance_minor = projected)
        val tx = Transaction(input.id, saved.workspaceId, "1", now, now, null, input.kind, "posted", input.occurred, input.timezone, input.note, input.payee,
            null, null, null, null, listOf(Entry(UUID.randomUUID().toString(), account.id, account.currency, signed.toString())),
            input.parts.map { Allocation(it.id, it.category_id, it.amount_minor, null, if (input.kind == "expense") it.amount_minor else "0") }, input.tags)
        val references = listOf("account" to account.id) + input.tags.map { "tag" to it } + input.parts.mapNotNull { it.category_id?.let { id -> "category" to id } }
        val dependencies = mutableSetOf<String>()
        for (reference in references.distinct()) {
            for (parent in projection.pending.asReversed()) {
                if (db.outbox().overlays(parent.position).any { (it.entityType to it.entityId) == reference }) { dependencies.add(parent.actionId); break }
            }
        }
        val command = OutboxStore(db).enqueue(saved, "transactions", "POST", body, ledgerAccountBases(listOf(account)), dependencies.toList(),
            listOf(OverlayEffect.upsert(SnapshotItemAccount("account", changed)), OverlayEffect.upsert(SnapshotItemTransaction("transaction", tx))))
        LocalProjectionStore(db).read(scope)
        command
    }

    suspend fun replace(saved: StoredSession, body: String, id: String): OutboxCommand = db.withTransaction {
        val projection = editableProjection(saved)
        val old = projection.visible.transactions.single { it.id == id }
        require(old.kind in setOf("income", "expense") && old.parent_transaction_id == null && old.status == "posted")
        val input = if (old.kind == "expense") decodeResponse<ExpenseReplace>(body).let {
            require(it.expected_version == old.version && it.expected_parent_version == null)
            BasicIntent(id, it.kind, it.account_id, it.amount_minor, it.occurred_at, it.occurred_timezone, it.note, it.payee, it.tag_ids, it.allocations)
        } else decodeResponse<IncomeReplace>(body).let {
            require(it.expected_version == old.version)
            BasicIntent(id, it.kind, it.account_id, it.amount_minor, it.occurred_at, it.occurred_timezone, it.note, it.payee, it.tag_ids, it.allocations)
        }
        require(input.kind == old.kind)
        val previousEntry = old.entries.single()
        val account = projection.visible.accounts.single { it.id == input.accountId }
        val previousAccount = projection.visible.accounts.single { it.id == previousEntry.account_id }
        require(account.archived_at == null && previousAccount.archived_at == null && account.currency == previousAccount.currency)
        require(input.timezone in TimeZone.getAvailableIDs() && input.note.length <= 2000 && input.payee.length <= 200)
        require(ledgerInstant(input.occurred) >= ledgerInstant(account.opened_at) && ledgerInstant(input.occurred) <= BigInteger.valueOf(System.currentTimeMillis()).multiply(BigInteger.valueOf(1000)))
        val amount = ledgerPositive(input.amount, account.currency)
        require(input.tags.size <= 50 && input.tags.toSet().size == input.tags.size)
        require(input.tags.all { id -> projection.visible.tags.any { it.id == id && (it.archived_at == null || id in old.tag_ids) } })
        require(input.parts.size in 1..100 && input.parts.map { it.id }.toSet().size == input.parts.size)
        require(input.parts.all { part -> part.category_id == null || projection.visible.categories.any { it.id == part.category_id &&
            (it.archived_at == null || old.allocations.any { prior -> prior.id == part.id && prior.category_id == part.category_id }) } })
        require(input.parts.fold(BigInteger.ZERO) { sum, part -> sum + ledgerPositive(part.amount_minor, account.currency) } == amount)
        val signed = if (old.kind == "expense") amount.negate() else amount
        val movementChanged = previousEntry.account_id != account.id || BigInteger(previousEntry.amount_minor) != signed || ledgerInstant(old.occurred_at) != ledgerInstant(input.occurred)
        val hasRefund = projection.visible.transactions.any { it.kind == "refund" && it.parent_transaction_id == id && it.status in setOf("posted", "pending") }
        if (hasRefund) require(!movementChanged && old.allocations.associate { it.id to (it.amount_minor to it.category_id) } == input.parts.associate { it.id to (it.amount_minor to it.category_id) })
        val now = ledgerNow()
        val parts = input.parts.map { part ->
            val prior = old.allocations.find { it.id == part.id }
            val refunded = if (old.kind == "expense" && prior != null) BigInteger(prior.amount_minor) - BigInteger(prior.remaining_refundable_minor) else BigInteger.ZERO
            Allocation(part.id, part.category_id, part.amount_minor, null, if (old.kind == "expense") (BigInteger(part.amount_minor) - refunded).toString() else "0")
        }
        val changed = old.copy(version = ledgerBump(old.version), updated_at = now, occurred_at = input.occurred, occurred_timezone = input.timezone,
            note = input.note, payee = input.payee, tag_ids = input.tags, allocations = parts,
            entries = listOf(previousEntry.copy(account_id = account.id, amount_minor = signed.toString())))
        val effects = mutableListOf(OverlayEffect.upsert(SnapshotItemTransaction("transaction", changed)))
        val affected = listOf(previousAccount, account).distinctBy { it.id }
        if (movementChanged) for (item in affected) {
            val delta = (if (item.id == previousAccount.id) BigInteger(previousEntry.amount_minor).negate() else BigInteger.ZERO) +
                (if (item.id == account.id) signed else BigInteger.ZERO)
            effects.add(OverlayEffect.upsert(SnapshotItemAccount("account", ledgerAccountDelta(item, delta, now))))
        }
        val refs = listOf("transaction" to id) + affected.map { "account" to it.id } + input.tags.map { "tag" to it } + input.parts.mapNotNull { it.category_id?.let { id -> "category" to id } }
        val bases = listOf(OutboxBase("transaction", id, old.version)) + ledgerAccountBases(affected)
        val command = OutboxStore(db).enqueue(saved, "transactions/$id", "PUT", body, bases, dependencies(projection, refs), effects)
        LocalProjectionStore(db).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))
        command
    }

    suspend fun delete(saved: StoredSession, body: String, id: String): OutboxCommand = db.withTransaction {
        val projection = editableProjection(saved)
        val old = projection.visible.transactions.single { it.id == id }
        require(old.kind in setOf("income", "expense") && old.parent_transaction_id == null && old.status == "posted")
        val input = decodeResponse<TransactionDelete>(body)
        require(input.expected_version == old.version && input.related_versions.isEmpty())
        require(projection.visible.transactions.none { it.kind == "refund" && it.parent_transaction_id == id && it.status in setOf("posted", "pending") })
        val entry = old.entries.single(); val account = projection.visible.accounts.single { it.id == entry.account_id }
        require(account.archived_at == null)
        ledgerBump(old.version)
        val effects = listOf(OverlayEffect.delete("transaction", id), OverlayEffect.upsert(SnapshotItemAccount("account", ledgerAccountDelta(account, BigInteger(entry.amount_minor).negate(), ledgerNow()))))
        val command = OutboxStore(db).enqueue(saved, "transactions/$id", "DELETE", body,
            listOf(OutboxBase("transaction", id, old.version)) + ledgerAccountBases(listOf(account)),
            dependencies(projection, listOf("transaction" to id, "account" to account.id)), effects)
        LocalProjectionStore(db).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))
        command
    }

    private suspend fun editableProjection(saved: StoredSession): LocalProjection {
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

}
private data class BasicIntent(val id: String, val kind: String, val accountId: String, val amount: String, val occurred: String, val timezone: String,
    val note: String, val payee: String, val tags: List<String>, val parts: List<AllocationInput>) {
    override fun toString() = "BasicIntent([redacted])"
}
