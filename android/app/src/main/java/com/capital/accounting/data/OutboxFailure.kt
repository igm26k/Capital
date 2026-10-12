package com.capital.accounting.data

import androidx.room.*

@Entity(tableName = "outbox_failures", foreignKeys = [ForeignKey(entity = OutboxCommand::class,
    parentColumns = ["position"], childColumns = ["commandPosition"], onDelete = ForeignKey.CASCADE)])
data class OutboxFailure(@PrimaryKey val commandPosition: Long, val status: Int?, val code: String?, val requestId: String?) {
    override fun toString() = "OutboxFailure([redacted])"
}
@Dao
interface OutboxFailureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(failure: OutboxFailure)
    @Query("SELECT * FROM outbox_failures WHERE commandPosition=:position") suspend fun get(position: Long): OutboxFailure?
    @Query("DELETE FROM outbox_failures WHERE commandPosition=:position") suspend fun clear(position: Long)
}
