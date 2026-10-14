package com.capital.accounting.data

import androidx.room.*
import com.capital.accounting.api.*
import com.capital.accounting.api.Transaction
import com.capital.accounting.auth.StoredSession

/** Explicit server relation: zero openings have no account entry to infer from. */
@Entity(tableName = "opening_links", primaryKeys = ["scope", "accountId"],
    indices = [Index(value = ["scope", "transactionId"], unique = true)])
data class OpeningLink(val scope: String, val accountId: String, val generation: String, val transactionId: String) {
    override fun toString() = "OpeningLink([redacted])"
}
@Dao
interface OpeningLinkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(link: OpeningLink)
    @Query("SELECT * FROM opening_links WHERE scope=:scope AND generation=:generation")
    suspend fun all(scope: String, generation: String): List<OpeningLink>
    @Query("DELETE FROM opening_links WHERE scope=:scope AND generation!=:generation")
    suspend fun prune(scope: String, generation: String)
}
class OpeningLinkStore(private val db: CapitalDatabase) {
    /** Never guess by timestamp: distinct zero accounts may share opened_at. */
    suspend fun accept(saved: StoredSession, accountId: String, opening: Transaction): OpeningLink = db.withTransaction {
        requireNotNull(IdentityStore(db).read(saved))
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val mirror = requireNotNull(SnapshotStore(db).read(scope))
        require(mirror.head.generation == saved.generationId)
        val finance = mirror.finance()
        val account = finance.accounts.single { it.id == accountId }
        require(opening.workspace_id == saved.workspaceId && opening.kind == "opening" &&
            opening.status == "posted" && opening.deleted_at == null && opening.allocations.isEmpty() &&
            opening.parent_transaction_id == null && opening.fee_transaction_id == null &&
            opening.entries.all { it.account_id == account.id })
        require(finance.transactions.single { it.id == opening.id } == opening)
        val link = OpeningLink(scope.key, account.id, saved.generationId, opening.id)
        db.openingLinks().prune(scope.key, saved.generationId)
        val previous = db.openingLinks().all(scope.key, saved.generationId).singleOrNull { it.accountId == accountId }
        if (previous == null) db.openingLinks().insert(link) else require(previous == link)
        link
    }

    suspend fun refresh(saved: StoredSession) {
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val missing = db.withTransaction {
            requireNotNull(IdentityStore(db).read(saved))
            val mirror = requireNotNull(SnapshotStore(db).read(scope))
            require(mirror.head.generation == saved.generationId)
            val known = db.openingLinks().all(scope.key, saved.generationId).map { it.accountId }.toSet()
            mirror.finance().accounts.filter { it.id !in known }.map { it.id }
        }
        val client = ApiClient(saved.origin)
        for (accountId in missing) {
            val opening = decodeResponse<Transaction>(client.request(
                "/workspaces/${saved.workspaceId}/accounts/$accountId/opening", session = saved))
            accept(saved, accountId, opening)
        }
    }
}
