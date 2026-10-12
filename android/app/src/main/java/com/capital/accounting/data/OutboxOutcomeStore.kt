package com.capital.accounting.data

import androidx.room.*
import com.capital.accounting.api.*
import com.capital.accounting.sync.checkedActionOutcome
import kotlinx.serialization.encodeToString

@Entity(tableName = "outbox_outcomes", foreignKeys = [ForeignKey(entity = OutboxCommand::class,
    parentColumns = ["position"], childColumns = ["commandPosition"], onDelete = ForeignKey.CASCADE)])
data class OutboxOutcome(@PrimaryKey val commandPosition: Long, val state: String, val status: Int,
    val generation: String?, val sequence: String?, val refs: String) {
    override fun toString() = "OutboxOutcome([redacted])"
}
@Dao
interface OutboxOutcomeDao {
    @Insert suspend fun insert(outcome: OutboxOutcome)
    @Query("SELECT * FROM outbox_outcomes WHERE commandPosition=:position") suspend fun get(position: Long): OutboxOutcome?
}

/** Compact evidence survives expiry of the original response; it never advances a pull cursor. */
class OutboxOutcomeStore(private val db: CapitalDatabase) {
    suspend fun accept(command: OutboxCommand, response: String): OutboxOutcome = db.withTransaction {
        require(response.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
        val outcome = checkedActionOutcome(command, response)
        val record = OutboxOutcome(command.position, outcome.state, outcome.original_http_status, outcome.generation_id,
            outcome.group_sequence, ApiClient.json.encodeToString(outcome.entity_refs.sortedWith(compareBy({ it.entity_type }, { it.id }))))
        val stored = requireNotNull(db.outbox().commands(command.scope).find { it.actionId == command.actionId })
        db.outcomes().get(command.position)?.let { existing ->
            require(existing == record && stored == command.copy(state = record.state)); return@withTransaction existing
        }
        require(stored == command && command.state in setOf("sending", "uncertain", "reconcile", "rejected", "applied"))
        if (outcome.state == "applied") {
            require(command.state != "rejected")
            val keys = outcome.entity_refs.map { it.entity_type to it.id }.toSet()
            require(db.outbox().overlays(command.position).all { (it.entityType to it.entityId) in keys })
            val receipt = db.receipts().get(command.position)
            if (receipt != null) {
                require(receipt.generation == record.generation && receipt.sequence == record.sequence && command.state == "applied")
                if (receipt.payload.isNotEmpty()) {
                    val original = decodeResponse<MutationResult>(receipt.payload)
                    checkedActionOutcome(command, ApiClient.json.encodeToString(outcome.copy(response_body = original)))
                    require(outcome.response_body == null || outcome.response_body == original)
                }
            }
            else {
                // Empty payload denotes applied evidence whose original response is unavailable.
                db.receipts().insert(OutboxReceipt(command.position, requireNotNull(record.generation), requireNotNull(record.sequence),
                    (outcome.response_body as? MutationResult)?.let { ApiClient.json.encodeToString(it) } ?: ""))
                require(db.outbox().transition(command.scope, command.actionId, command.state, "applied") == 1)
            }
            db.failures().clear(command.position)
        } else {
            require(command.state != "applied")
            val error = outcome.response_body as? com.capital.accounting.api.Error
            db.failures().save(OutboxFailure(command.position, record.status, error?.code,
                error?.request_id?.takeIf { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) }))
            if (command.state != "rejected") require(db.outbox().transition(command.scope, command.actionId, command.state, "rejected") == 1)
        }
        db.outcomes().insert(record)
        db.snapshots().head(command.scope)?.let { OutboxReceiptStore(db).coverLocked(it, authoritativeSnapshot = true) }
        record
    }
}
