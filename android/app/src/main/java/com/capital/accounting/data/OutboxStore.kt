package com.capital.accounting.data

import androidx.room.*
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import java.math.BigInteger
import java.util.UUID

@Serializable
data class OutboxBase(val entityType: String, val entityId: String, val version: String, val field: String = "version")
@Serializable
data class OverlayEffect(val entityType: String, val entityId: String, val operation: String, val payload: String? = null) {
    override fun toString() = "OverlayEffect([redacted])"
    companion object {
        fun upsert(item: SnapshotItem): OverlayEffect = OverlayEffect(item.entity_type, snapshotIdentity(item).id, "upsert", ApiClient.json.encodeToString(item))
        fun delete(type: String, id: String): OverlayEffect = OverlayEffect(type, id, "delete")
    }
}
@Entity(tableName = "outbox_commands", indices = [Index(value = ["scope", "actionId"], unique = true)])
data class OutboxCommand(@PrimaryKey(autoGenerate = true) val position: Long = 0, val scope: String, val actionId: String,
    val origin: String, val owner: String, val workspace: String, val issuerSession: String, val generation: String,
    val path: String, val method: String, val body: String, val baseVersions: String, val dependencies: String, val state: String = "queued") {
    override fun toString() = "OutboxCommand([redacted])"
}
@Entity(tableName = "outbox_overlays", primaryKeys = ["commandPosition", "entityType", "entityId"],
    foreignKeys = [ForeignKey(entity = OutboxCommand::class, parentColumns = ["position"], childColumns = ["commandPosition"], onDelete = ForeignKey.CASCADE)])
data class OutboxOverlay(val commandPosition: Long, val entityType: String, val entityId: String, val operation: String, val payload: String?) {
    override fun toString() = "OutboxOverlay([redacted])"
}
@Dao
interface OutboxDao {
    @Insert suspend fun insert(command: OutboxCommand): Long
    @Insert suspend fun overlays(items: List<OutboxOverlay>)
    @Query("SELECT * FROM outbox_commands WHERE scope=:scope ORDER BY position") suspend fun commands(scope: String): List<OutboxCommand>
    @Query("SELECT * FROM outbox_overlays WHERE commandPosition=:position ORDER BY entityType,entityId") suspend fun overlays(position: Long): List<OutboxOverlay>
    @Query("DELETE FROM outbox_commands WHERE scope=:scope AND actionId=:action AND state='queued'") suspend fun cancel(scope: String, action: String): Int
}

/** Queue and optimistic effects are committed together; confirmed mirror is never overwritten. */
class OutboxStore(private val db: CapitalDatabase) {
    suspend fun enqueue(saved: StoredSession, suffix: String, method: String, body: String, bases: List<OutboxBase>, dependencies: List<String>, effects: List<OverlayEffect>, action: String = UUID.randomUUID().toString()): OutboxCommand = db.withTransaction {
        require(!saved.logoutPending && saved.revokeSessionId == null)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val path = "/workspaces/${saved.workspaceId}/$suffix"
        FinancialCommand(action, saved.origin, saved.ownerId, saved.workspaceId, saved.sessionId, saved.generationId, path, method, body)
        ApiClient.json.parseToJsonElement(body).jsonObject
        require(db.snapshots().head(scope.key)?.generation == saved.generationId)
        require(effects.size in 1..200 && effects.map { it.entityType to it.entityId }.toSet().size == effects.size)
        require(bases.size <= 200 && bases.map { Triple(it.entityType, it.entityId, it.field) }.toSet().size == bases.size)
        val mirror = db.snapshots().records(scope.key).associateBy { it.entityType to it.entityId }
        for (base in bases) {
            type(base.entityType); uuid(base.entityId)
            require(base.version.matches(Regex("[1-9][0-9]*")) && BigInteger(base.version) <= BigInteger.valueOf(Long.MAX_VALUE))
            val item = decodeResponse<SnapshotItem>(requireNotNull(mirror[base.entityType to base.entityId]).payload)
            val actual = if (base.field == "version") snapshotIdentity(item).version else {
                require(base.field == "balance_version" && item is SnapshotItemAccount); item.data.balance_version
            }
            require(actual == base.version)
        }
        require(dependencies.size <= 100 && dependencies.toSet().size == dependencies.size && action !in dependencies)
        val queued = db.outbox().commands(scope.key).associateBy { it.actionId }
        for (dependency in dependencies) { uuid(dependency); require(requireNotNull(queued[dependency]).state in setOf("queued", "sending", "uncertain", "applied")) }
        var bytes = 0L
        for (effect in effects) {
            type(effect.entityType); uuid(effect.entityId); require(effect.operation in setOf("upsert", "delete"))
            if (effect.operation == "upsert") {
                val item = decodeResponse<SnapshotItem>(requireNotNull(effect.payload)); val identity = snapshotIdentity(item)
                require(item.entity_type == effect.entityType && identity.id == effect.entityId && identity.workspace == saved.workspaceId && identity.deleted == null)
                bytes += effect.payload.toByteArray(Charsets.UTF_8).size
            } else require(effect.payload == null)
        }
        require(bytes <= 1024 * 1024)
        val command = OutboxCommand(scope = scope.key, actionId = action, origin = saved.origin, owner = saved.ownerId, workspace = saved.workspaceId,
            issuerSession = saved.sessionId, generation = saved.generationId, path = path, method = method, body = body,
            baseVersions = ApiClient.json.encodeToString(bases), dependencies = ApiClient.json.encodeToString(dependencies))
        val position = db.outbox().insert(command)
        db.outbox().overlays(effects.map { OutboxOverlay(position, it.entityType, it.entityId, it.operation, it.payload) })
        command.copy(position = position)
    }
    suspend fun cancelQueued(scope: MirrorScope, action: String) = db.withTransaction {
        val commands = db.outbox().commands(scope.key)
        require(commands.none { action in ApiClient.json.decodeFromString<List<String>>(it.dependencies) })
        require(db.outbox().cancel(scope.key, action) == 1)
    }
}
private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
private fun type(value: String) { require(value in setOf("account", "transaction", "category", "tag")) }
