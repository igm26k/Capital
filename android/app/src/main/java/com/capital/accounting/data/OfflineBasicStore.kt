package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.finance.Money
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.text.ParsePosition
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
        require(instant(input.occurred) >= instant(account.opened_at) && instant(input.occurred) <= BigInteger.valueOf(System.currentTimeMillis()).multiply(BigInteger.valueOf(1000)))
        require(input.note.length <= 2000 && input.payee.length <= 200)
        val amount = positive(input.amount, account.currency)
        require(input.tags.size <= 50 && input.tags.toSet().size == input.tags.size)
        require(input.tags.all { id -> projection.visible.tags.any { it.id == id && it.archived_at == null } })
        require(input.parts.size in 1..100 && input.parts.map { it.id }.toSet().size == input.parts.size)
        require(input.parts.all { part -> part.category_id == null || projection.visible.categories.any { it.id == part.category_id && it.archived_at == null } })
        require(input.parts.fold(BigInteger.ZERO) { sum, part -> sum + positive(part.amount_minor, account.currency) } == amount)
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val signed = if (input.kind == "expense") amount.negate() else amount
        val posted = (BigInteger(account.posted_balance_minor) + signed).toString()
        val projected = (BigInteger(posted) + BigInteger(account.pending_delta_minor)).toString()
        Money.display(posted, account.currency); Money.display(projected, account.currency)
        val changed = account.copy(version = bump(account.version), balance_version = bump(account.balance_version), updated_at = now,
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
        val command = OutboxStore(db).enqueue(saved, "transactions", "POST", body, listOf(OutboxBase("account", account.id, account.version)), dependencies.toList(),
            listOf(OverlayEffect.upsert(SnapshotItemAccount("account", changed)), OverlayEffect.upsert(SnapshotItemTransaction("transaction", tx))))
        LocalProjectionStore(db).read(scope)
        command
    }
}
private data class BasicIntent(val id: String, val kind: String, val accountId: String, val amount: String, val occurred: String, val timezone: String,
    val note: String, val payee: String, val tags: List<String>, val parts: List<AllocationInput>) {
    override fun toString() = "BasicIntent([redacted])"
}
private fun positive(value: String, currency: String): BigInteger {
    require(value.matches(Regex("[1-9][0-9]*"))); Money.display(value, currency); return BigInteger(value)
}
private fun bump(value: String): String = (BigInteger(value) + BigInteger.ONE).also { require(it <= BigInteger.valueOf(Long.MAX_VALUE)) }.toString()
private fun instant(value: String): BigInteger {
    require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")))
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val position = ParsePosition(0); val date = requireNotNull(parser.parse(value.take(19), position)); require(position.index == 19)
    return BigInteger.valueOf(date.time).multiply(BigInteger.valueOf(1000)) + BigInteger(value.drop(19).removeSuffix("Z").removePrefix(".").padEnd(6, '0'))
}
