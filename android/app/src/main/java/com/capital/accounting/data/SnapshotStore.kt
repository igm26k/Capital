package com.capital.accounting.data

import androidx.room.*
import com.capital.accounting.api.*
import com.capital.accounting.normalizedOrigin
import kotlinx.serialization.encodeToString
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

/** Session-independent scope; credentials never participate in mirror storage. */
data class MirrorScope(val origin: String, val owner: String, val workspace: String) {
    init { require(normalizedOrigin(origin) == origin); uuid(owner); uuid(workspace) }
    val key get() = digest("$origin\n$owner\n$workspace")
}
private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
private fun sequence(value: String, positive: Boolean = false) {
    require(value.matches(Regex(if (positive) "[1-9][0-9]*" else "0|[1-9][0-9]*")))
    require(BigInteger(value) <= BigInteger.valueOf(Long.MAX_VALUE))
}

@Entity(tableName = "snapshot_attempts")
data class SnapshotAttempt(@PrimaryKey val scope: String, val snapshot: String) {
    override fun toString() = "SnapshotAttempt([redacted])"
}
@Entity(tableName = "snapshot_stages")
data class SnapshotStage(@PrimaryKey val scope: String, val snapshot: String, val descriptor: String,
    val nextToken: String?, val received: Int = 0, val bytes: Long = 0, val lastKey: String? = null, val complete: Boolean = false) {
    override fun toString() = "SnapshotStage([redacted])"
}
@Entity(tableName = "snapshot_records", primaryKeys = ["scope", "entityType", "entityId"])
data class SnapshotRecord(val scope: String, val entityType: String, val entityId: String, val payload: String) {
    override fun toString() = "SnapshotRecord([redacted])"
}
@Entity(tableName = "snapshot_tokens", primaryKeys = ["scope", "token"])
data class SnapshotToken(val scope: String, val token: String)
@Entity(tableName = "mirror_heads")
data class MirrorHead(@PrimaryKey val scope: String, val generation: String, val sequence: String, val cursor: String) {
    override fun toString() = "MirrorHead([redacted])"
}
@Entity(tableName = "mirror_records", primaryKeys = ["scope", "entityType", "entityId"])
data class MirrorRecord(val scope: String, val entityType: String, val entityId: String, val payload: String) {
    override fun toString() = "MirrorRecord([redacted])"
}

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM snapshot_attempts WHERE scope=:scope") suspend fun attempt(scope: String): SnapshotAttempt?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun start(attempt: SnapshotAttempt): Long
    @Query("DELETE FROM snapshot_attempts WHERE scope=:scope AND snapshot=:snapshot") suspend fun finish(scope: String, snapshot: String): Int
    @Query("SELECT * FROM snapshot_stages WHERE scope=:scope") suspend fun stage(scope: String): SnapshotStage?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(stage: SnapshotStage)
    @Insert suspend fun insert(records: List<SnapshotRecord>)
    @Insert suspend fun token(token: SnapshotToken)
    @Query("SELECT COUNT(*) FROM snapshot_tokens WHERE scope=:scope AND token=:token") suspend fun tokenCount(scope: String, token: String): Int
    @Query("SELECT * FROM snapshot_records WHERE scope=:scope ORDER BY entityType,entityId") suspend fun staged(scope: String): List<SnapshotRecord>
    @Query("DELETE FROM snapshot_stages WHERE scope=:scope") suspend fun deleteStage(scope: String)
    @Query("DELETE FROM snapshot_records WHERE scope=:scope") suspend fun deleteStaged(scope: String)
    @Query("DELETE FROM snapshot_tokens WHERE scope=:scope") suspend fun deleteTokens(scope: String)
    @Query("SELECT * FROM mirror_heads WHERE scope=:scope") suspend fun head(scope: String): MirrorHead?
    @Query("SELECT * FROM mirror_records WHERE scope=:scope ORDER BY entityType,entityId") suspend fun records(scope: String): List<MirrorRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun head(head: MirrorHead)
    @Insert suspend fun confirmed(records: List<MirrorRecord>)
    @Query("DELETE FROM mirror_records WHERE scope=:scope") suspend fun deleteConfirmed(scope: String)
}

data class ConfirmedMirror(val head: MirrorHead, val items: List<SnapshotItem>) {
    override fun toString() = "ConfirmedMirror([redacted])"
}

class SnapshotStore(private val db: CapitalDatabase) {
    private val dao get() = db.snapshots()
    suspend fun begin(scope: MirrorScope, snapshot: Snapshot) = db.withTransaction {
        uuid(snapshot.id); uuid(snapshot.generation_id); sequence(snapshot.base_sequence)
        require(snapshot.workspace_id == scope.workspace && snapshot.item_count in 0..50000)
        require(snapshot.first_page_token.isNotEmpty() && snapshot.resume_cursor.isNotEmpty())
        val descriptor = ApiClient.json.encodeToString(snapshot)
        val old = dao.stage(scope.key)
        if (old?.snapshot == snapshot.id) { require(old.descriptor == descriptor); return@withTransaction }
        clearStage(scope.key)
        dao.save(SnapshotStage(scope.key, snapshot.id, descriptor, snapshot.first_page_token))
    }
    suspend fun append(scope: MirrorScope, token: String, page: SnapshotPage) = db.withTransaction {
        val stage = requireNotNull(dao.stage(scope.key))
        val snapshot = decodeResponse<Snapshot>(stage.descriptor)
        require(!stage.complete && stage.nextToken == token)
        require(page.snapshot_id == stage.snapshot && page.workspace_id == scope.workspace &&
            page.generation_id == snapshot.generation_id && page.base_sequence == snapshot.base_sequence)
        require(page.items.size <= 100 && (page.items.isNotEmpty() || page.next_page_token == null))
        require(page.next_page_token == null || (page.next_page_token.isNotEmpty() && page.next_page_token != token && dao.tokenCount(scope.key, digest(page.next_page_token)) == 0))
        var previous = stage.lastKey
        val records = page.items.map { item ->
            val (id, workspace, version, deleted) = identity(item)
            uuid(id); sequence(version, positive = true)
            require(workspace == scope.workspace && deleted == null)
            val key = "${rank(item.entity_type)}:$id"
            require(previous == null || key > previous!!); previous = key
            SnapshotRecord(scope.key, item.entity_type, id, ApiClient.json.encodeToString(item))
        }
        val count = stage.received + records.size
        val bytes = stage.bytes + records.sumOf { it.payload.toByteArray(Charsets.UTF_8).size.toLong() }
        require(count <= snapshot.item_count && bytes <= 64L * 1024 * 1024)
        require(page.next_page_token != null || count == snapshot.item_count)
        dao.insert(records); dao.token(SnapshotToken(scope.key, digest(token)))
        dao.save(stage.copy(nextToken = page.next_page_token, received = count, bytes = bytes, lastKey = previous, complete = page.next_page_token == null))
    }
    suspend fun install(scope: MirrorScope) = db.withTransaction {
        val stage = requireNotNull(dao.stage(scope.key)); require(stage.complete)
        val snapshot = decodeResponse<Snapshot>(stage.descriptor)
        val records = dao.staged(scope.key); require(records.size == snapshot.item_count)
        val items = records.map { decodeResponse<SnapshotItem>(it.payload) }
        validateGraph(items)
        dao.deleteConfirmed(scope.key)
        dao.confirmed(records.map { MirrorRecord(it.scope, it.entityType, it.entityId, it.payload) })
        dao.head(MirrorHead(scope.key, snapshot.generation_id, snapshot.base_sequence, snapshot.resume_cursor))
        clearStage(scope.key)
    }
    suspend fun read(scope: MirrorScope): ConfirmedMirror? = db.withTransaction {
        dao.head(scope.key)?.let { head -> ConfirmedMirror(head, dao.records(scope.key).map { decodeResponse<SnapshotItem>(it.payload) }) }
    }
    suspend fun discard(scope: MirrorScope, snapshot: String) = db.withTransaction {
        if (dao.stage(scope.key)?.snapshot == snapshot) clearStage(scope.key)
    }
    private suspend fun clearStage(scope: String) { dao.deleteStaged(scope); dao.deleteTokens(scope); dao.deleteStage(scope) }
}

private fun rank(type: String) = listOf("category", "tag", "account", "transaction").indexOf(type).also { require(it >= 0) }
private data class Identity(val id: String, val workspace: String, val version: String, val deleted: String?)
private fun identity(item: SnapshotItem): Identity = when (item) {
    is SnapshotItemAccount -> item.data.let { Identity(it.id, it.workspace_id, it.version, it.deleted_at) }
    is SnapshotItemTransaction -> item.data.let { Identity(it.id, it.workspace_id, it.version, it.deleted_at) }
    is SnapshotItemCategory -> item.data.let { Identity(it.id, it.workspace_id, it.version, it.deleted_at) }
    is SnapshotItemTag -> item.data.let { Identity(it.id, it.workspace_id, it.version, it.deleted_at) }
}

/** References can cross page boundaries; validate only the complete staged graph. */
private fun validateGraph(items: List<SnapshotItem>) {
    val accounts = items.filterIsInstance<SnapshotItemAccount>().associate { it.data.id to it.data }
    val transactions = items.filterIsInstance<SnapshotItemTransaction>().associate { it.data.id to it.data }
    val categories = items.filterIsInstance<SnapshotItemCategory>().associate { it.data.id to it.data }
    val tags = items.filterIsInstance<SnapshotItemTag>().map { it.data.id }.toSet()
    for (category in categories.values) {
        val seen = mutableSetOf(category.id); var parent = category.parent_id
        while (parent != null) { require(seen.add(parent)); parent = requireNotNull(categories[parent]).parent_id }
    }
    val entryIds = mutableSetOf<String>(); val allocationIds = mutableSetOf<String>()
    val posted = mutableMapOf<String, BigInteger>(); val pending = mutableMapOf<String, BigInteger>()
    for (transaction in transactions.values) {
        require(transaction.entries.isNotEmpty() && transaction.tag_ids.toSet().size == transaction.tag_ids.size && transaction.tag_ids.all { it in tags })
        transaction.parent_transaction_id?.let { require(it in transactions && it != transaction.id) }
        transaction.fee_transaction_id?.let { require(it in transactions && it != transaction.id) }
        for (entry in transaction.entries) {
            uuid(entry.id); require(entryIds.add(entry.id))
            val account = requireNotNull(accounts[entry.account_id]); require(entry.currency == account.currency)
            com.capital.accounting.finance.Money.display(entry.amount_minor, entry.currency)
            val balances = if (transaction.status == "posted") posted else pending
            balances[account.id] = (balances[account.id] ?: BigInteger.ZERO) + BigInteger(entry.amount_minor)
        }
        for (allocation in transaction.allocations) {
            uuid(allocation.id); require(allocationIds.add(allocation.id))
            require(allocation.category_id == null || allocation.category_id in categories)
            com.capital.accounting.finance.Money.display(allocation.amount_minor, transaction.entries.first().currency)
            require(BigInteger(allocation.amount_minor).signum() > 0)
            com.capital.accounting.finance.Money.display(allocation.remaining_refundable_minor, transaction.entries.first().currency)
            require(BigInteger(allocation.remaining_refundable_minor).signum() >= 0 && BigInteger(allocation.remaining_refundable_minor) <= BigInteger(allocation.amount_minor))
        }
    }
    val originals = transactions.values.flatMap { it.allocations }.map { it.id }.toSet()
    transactions.values.flatMap { it.allocations }.forEach { it.original_allocation_id?.let { original -> require(original in originals && original != it.id) } }
    for (account in accounts.values) {
        sequence(account.balance_version, positive = true)
        for (amount in listOf(account.posted_balance_minor, account.pending_delta_minor, account.projected_balance_minor)) com.capital.accounting.finance.Money.display(amount, account.currency)
        val p = posted[account.id] ?: BigInteger.ZERO; val q = pending[account.id] ?: BigInteger.ZERO
        require(p.toString() == account.posted_balance_minor && q.toString() == account.pending_delta_minor && (p + q).toString() == account.projected_balance_minor)
    }
}
