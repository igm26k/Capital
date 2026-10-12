package com.capital.accounting.data

import androidx.room.*
import com.capital.accounting.api.*
import java.math.BigInteger
import java.util.UUID

@Entity(tableName = "outbox_receipts", foreignKeys = [ForeignKey(entity = OutboxCommand::class,
    parentColumns = ["position"], childColumns = ["commandPosition"], onDelete = ForeignKey.CASCADE)])
data class OutboxReceipt(@PrimaryKey val commandPosition: Long, val generation: String, val sequence: String, val payload: String) {
    override fun toString() = "OutboxReceipt([redacted])"
}
@Dao
interface OutboxReceiptDao {
    @Insert suspend fun insert(receipt: OutboxReceipt)
    @Query("SELECT * FROM outbox_receipts WHERE commandPosition=:position") suspend fun get(position: Long): OutboxReceipt?
    @Query("DELETE FROM outbox_overlays WHERE commandPosition=:position") suspend fun clearOverlay(position: Long)
}

class OutboxReceiptStore(private val db: CapitalDatabase) {
    suspend fun applied(command: OutboxCommand, response: String): OutboxReceipt = db.withTransaction {
        require(response.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
        val result = decodeResponse<MutationResult>(response)
        require(result.action_id == command.actionId && result.generation_id == command.generation)
        positive(result.sync_group_sequence)
        val identities = result.accounts.map { "account" to Identity(it.id, it.workspace_id, it.version, it.deleted_at) } +
            result.transactions.map { "transaction" to Identity(it.id, it.workspace_id, it.version, it.deleted_at) } +
            result.categories.map { "category" to Identity(it.id, it.workspace_id, it.version, it.deleted_at) } +
            result.tags.map { "tag" to Identity(it.id, it.workspace_id, it.version, it.deleted_at) }
        val keys = identities.map { (type, item) ->
            uuid(item.id); positive(item.version); require(item.workspace == command.workspace && item.deleted == null); type to item.id
        } + result.deleted.map {
            require(it.entity_type in setOf("account", "transaction", "category", "tag")); uuid(it.id); positive(it.version)
            require(it.workspace_id == command.workspace); it.entity_type to it.id
        }
        require(keys.size in 1..200 && keys.toSet().size == keys.size)
        val stored = requireNotNull(db.outbox().commands(command.scope).find { it.actionId == command.actionId })
        val receipt = OutboxReceipt(command.position, result.generation_id, result.sync_group_sequence, response)
        if (stored.state == "applied") {
            val existing = requireNotNull(db.receipts().get(command.position))
            require(stored == command.copy(state = "applied") && existing.generation == receipt.generation && existing.sequence == receipt.sequence &&
                decodeResponse<MutationResult>(existing.payload) == result)
            return@withTransaction existing
        }
        require(stored == command && command.state in setOf("sending", "uncertain"))
        require(db.outbox().overlays(command.position).all { (it.entityType to it.entityId) in keys })
        db.receipts().insert(receipt)
        db.failures().clear(command.position)
        require(db.outbox().transition(command.scope, command.actionId, command.state, "applied") == 1)
        db.snapshots().head(command.scope)?.let { coverLocked(it, authoritativeSnapshot = true) }
        receipt
    }

    /** Called inside the same transaction that installs the confirmed mirror/cursor. */
    internal suspend fun coverLocked(head: MirrorHead, authoritativeSnapshot: Boolean) {
        for (command in db.outbox().commands(head.scope).filter { it.state == "applied" }) {
            val receipt = requireNotNull(db.receipts().get(command.position))
            val covered = if (receipt.generation == head.generation) BigInteger(head.sequence) >= BigInteger(receipt.sequence)
                else authoritativeSnapshot
            if (covered) db.receipts().clearOverlay(command.position)
        }
    }
}
private fun positive(value: String) { require(value.matches(Regex("[1-9][0-9]*")) && BigInteger(value) <= BigInteger.valueOf(Long.MAX_VALUE)) }
private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
