package com.capital.accounting.data

import androidx.room.*
import com.capital.accounting.api.*
import com.capital.accounting.sync.pullIdentity
import com.capital.accounting.sync.validatedPullSequence
import kotlinx.serialization.encodeToString
import java.math.BigInteger

@Entity(tableName = "mirror_tombstones", primaryKeys = ["scope", "entityType", "entityId"])
data class MirrorTombstone(val scope: String, val entityType: String, val entityId: String, val version: String, val payload: String) {
    override fun toString() = "MirrorTombstone([redacted])"
}
@Entity(tableName = "mirror_changes", primaryKeys = ["scope", "changeId"])
data class MirrorChange(val scope: String, val changeId: String)
@Dao
interface PullDao {
    @Query("SELECT * FROM mirror_tombstones WHERE scope=:scope") suspend fun tombstones(scope: String): List<MirrorTombstone>
    @Query("SELECT COUNT(*) FROM mirror_changes WHERE scope=:scope AND changeId=:id") suspend fun seen(scope: String, id: String): Int
    @Insert suspend fun seen(change: MirrorChange)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun tombstone(value: MirrorTombstone)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun record(value: MirrorRecord)
    @Query("DELETE FROM mirror_records WHERE scope=:scope AND entityType=:type AND entityId=:id") suspend fun delete(scope: String, type: String, id: String)
    @Query("DELETE FROM mirror_tombstones WHERE scope=:scope") suspend fun clearTombstones(scope: String)
    @Query("DELETE FROM mirror_changes WHERE scope=:scope") suspend fun clearChanges(scope: String)
}

class PullStore(private val db: CapitalDatabase) {
    suspend fun apply(scope: MirrorScope, captured: MirrorHead, page: SyncChanges) = db.withTransaction {
        require(db.snapshots().head(scope.key) == captured) // Reject delayed responses after another commit/bootstrap.
        val last = validatedPullSequence(scope, captured, page)
        val records = db.snapshots().records(scope.key).associateBy { it.entityType to it.entityId }.toMutableMap()
        val tombstones = db.pull().tombstones(scope.key).associateBy { it.entityType to it.entityId }.toMutableMap()
        for (group in page.groups) {
            for (change in group.changes) {
                require(db.pull().seen(scope.key, change.change_id) == 0)
                val identity = pullIdentity(change); val key = identity.type to identity.id
                val incoming = BigInteger(identity.version)
                val old = records[key]?.let { decodeResponse<SnapshotItem>(it.payload) }
                val oldVersion = old?.let { BigInteger(snapshotIdentity(it).version) }
                val dead = tombstones[key]
                if (change.operation == "delete") {
                    val payload = tombstone(change)
                    if (oldVersion != null) require(incoming != oldVersion)
                    if (dead != null && incoming == BigInteger(dead.version)) require(payload == decodeResponse<Tombstone>(dead.payload))
                    val newest = maxOf(oldVersion ?: BigInteger.ZERO, dead?.version?.let(::BigInteger) ?: BigInteger.ZERO)
                    if (incoming > newest) {
                        val value = MirrorTombstone(scope.key, key.first, key.second, identity.version, ApiClient.json.encodeToString(payload))
                        db.pull().delete(scope.key, key.first, key.second); records.remove(key)
                        db.pull().tombstone(value); tombstones[key] = value
                    }
                } else {
                    val item = upsert(change)
                    if (dead != null) require(incoming < BigInteger(dead.version)) // IDs cannot be reused after deletion.
                    else if (oldVersion == null || incoming > oldVersion) {
                        val value = MirrorRecord(scope.key, key.first, key.second, ApiClient.json.encodeToString(item))
                        db.pull().record(value); records[key] = value
                    } else if (incoming == oldVersion) require(item == old)
                }
                db.pull().seen(MirrorChange(scope.key, change.change_id))
            }
            validateSnapshotGraph(records.values.map { decodeResponse<SnapshotItem>(it.payload) })
        }
        db.snapshots().head(captured.copy(sequence = last, cursor = page.next_cursor))
    }
}
private fun tombstone(change: WorkspaceChange): Tombstone = when (change) {
    is WorkspaceChangeAccountDelete -> change.data
    is WorkspaceChangeTransactionDelete -> change.data
    is WorkspaceChangeCategoryDelete -> change.data
    is WorkspaceChangeTagDelete -> change.data
    else -> error("Expected tombstone")
}
private fun upsert(change: WorkspaceChange): SnapshotItem = when (change) {
    is WorkspaceChangeAccountUpsert -> SnapshotItemAccount("account", change.data)
    is WorkspaceChangeTransactionUpsert -> SnapshotItemTransaction("transaction", change.data)
    is WorkspaceChangeCategoryUpsert -> SnapshotItemCategory("category", change.data)
    is WorkspaceChangeTagUpsert -> SnapshotItemTag("tag", change.data)
    else -> error("Expected upsert")
}
