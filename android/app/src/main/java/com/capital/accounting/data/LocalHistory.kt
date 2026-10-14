package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.api.Transaction
import com.capital.accounting.auth.StoredSession
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.util.Locale

/** This cursor belongs only to this local dataset; it is never an API cursor. */
data class LocalHistoryCursor internal constructor(internal val scope: String, internal val generation: String,
    internal val fingerprint: String, internal val filter: TransactionFilter, internal val limit: Int, internal val offset: Int) {
    override fun toString() = "LocalHistoryCursor([redacted])"
}
data class LocalHistoryPage(val projection: LocalProjection, val items: List<Transaction>, val next: LocalHistoryCursor?, val dataset: LocalHistoryCursor) {
    override fun toString() = "LocalHistoryPage([redacted])"
}
data class LocalTransactionDetails(val projection: LocalProjection, val transaction: Transaction?, val openingAccountId: String?) {
    override fun toString() = "LocalTransactionDetails([redacted])"
}
class LocalHistoryStore(private val db: CapitalDatabase) {
    private suspend fun dataset(saved: StoredSession): Pair<LocalProjection, Map<String, String>> {
        if (IdentityStore(db).read(saved) == null) throw ApiFailure(401, "unauthenticated")
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val projection = requireNotNull(LocalProjectionStore(db).read(scope))
        require(projection.confirmed.head.generation == saved.generationId)
        val openings = mutableMapOf<String, String>()
        for (link in db.openingLinks().all(scope.key, saved.generationId)) {
            if (projection.visible.accounts.any { it.id == link.accountId } &&
                projection.visible.transactions.any { it.id == link.transactionId && it.kind == "opening" }) openings[link.transactionId] = link.accountId
        }
        for (item in projection.visible.transactions.filter { it.kind == "opening" && it.entries.isNotEmpty() }) {
            val account = item.entries.map { it.account_id }.distinct().single()
            require(openings[item.id] == null || openings[item.id] == account)
            openings[item.id] = account
        }
        for (command in projection.pending) {
            for (effect in db.outbox().overlays(command.position).filter { it.operation == "opening" }) {
                validateLocalOpening(command.path, command.method, command.body, saved.workspaceId,
                    OverlayEffect(effect.entityType, effect.entityId, effect.operation, effect.payload))
                val account = decodeResponse<AccountCreate>(command.body).id
                if (projection.visible.transactions.any { it.id == effect.entityId }) openings[effect.entityId] = account
            }
        }
        return projection to openings
    }
    suspend fun details(saved: StoredSession, id: String): LocalTransactionDetails = db.withTransaction {
        require(java.util.UUID.fromString(id).toString() == id)
        val (projection, openings) = dataset(saved)
        LocalTransactionDetails(projection, projection.visible.transactions.singleOrNull { it.id == id }, openings[id])
    }
    suspend fun page(saved: StoredSession, filter: TransactionFilter, limit: Int = 50, cursor: LocalHistoryCursor? = null): LocalHistoryPage = db.withTransaction {
        require(limit in 1..100)
        val (projection, openings) = dataset(saved)
        val finance = projection.visible
        require(filter.accountId == null || finance.accounts.any { it.id == filter.accountId })
        require(filter.categoryId == null || finance.categories.any { it.id == filter.categoryId })
        require(filter.tagId == null || finance.tags.any { it.id == filter.tagId })
        // Incomplete legacy mirrors cannot safely answer an account filter for zero openings.
        if (filter.accountId != null) require(finance.transactions.none { it.kind == "opening" && it.entries.isEmpty() && it.id !in openings })
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId).key
        val source = buildString {
            append(projection.confirmed.head.sequence); append('\n')
            finance.transactions.forEach { append(ApiClient.json.encodeToString(it)); append('\n') }
            finance.accounts.forEach { append(ApiClient.json.encodeToString(it)); append('\n') }
            finance.categories.forEach { append(ApiClient.json.encodeToString(it)); append('\n') }
            finance.tags.forEach { append(ApiClient.json.encodeToString(it)); append('\n') }
            openings.toSortedMap().forEach { (id, account) -> append(id); append(':'); append(account); append('\n') }
            projection.pending.forEach { append(it.actionId); append(':'); append(it.state); append('\n') }
            projection.blocked.forEach { append(it.actionId); append(':'); append(it.state); append('\n') }
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        require(cursor == null || cursor.scope == scope && cursor.generation == saved.generationId && cursor.fingerprint == fingerprint && cursor.filter == filter && cursor.limit == limit)
        val search = filter.search.lowercase(Locale.ROOT)
        val items = finance.transactions.filter { item ->
            (filter.accountId == null || item.entries.any { it.account_id == filter.accountId } || openings[item.id] == filter.accountId) &&
                (filter.categoryId == null || item.allocations.any { it.category_id == filter.categoryId }) &&
                (filter.tagId == null || filter.tagId in item.tag_ids) &&
                (filter.kind == null || item.kind == filter.kind) && (filter.status == null || item.status == filter.status) &&
                (filter.from == null || instant(item.occurred_at) >= instant(filter.from)) &&
                (filter.to == null || instant(item.occurred_at) < instant(filter.to)) &&
                (item.note.lowercase(Locale.ROOT).contains(search) || item.payee.lowercase(Locale.ROOT).contains(search))
        }.sortedWith(compareByDescending<Transaction> { instant(it.occurred_at) }.thenByDescending { it.id })
        val offset = cursor?.offset ?: 0
        require(offset in 0..items.size)
        val page = items.drop(offset).take(limit)
        val nextOffset = offset + page.size
        val next = if (nextOffset < items.size) LocalHistoryCursor(scope, saved.generationId, fingerprint, filter, limit, nextOffset) else null
        LocalHistoryPage(projection, page, next, LocalHistoryCursor(scope, saved.generationId, fingerprint, filter, limit, 0))
    }
}
private fun instant(value: String): String = value.take(19) + value.drop(19).removeSuffix("Z").removePrefix(".").padEnd(6, '0')
