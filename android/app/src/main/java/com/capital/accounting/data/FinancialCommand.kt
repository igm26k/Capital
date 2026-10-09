package com.capital.accounting.data

import androidx.room.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.normalizedOrigin
import java.util.UUID

@Entity(tableName = "financial_commands", indices = [Index(value = ["origin", "ownerId", "workspaceId"], unique = true)])
data class FinancialCommand(
    @PrimaryKey val commandId: String,
    val origin: String,
    val ownerId: String,
    val workspaceId: String,
    val sessionId: String,
    val generationId: String,
    val path: String,
    val method: String,
    val body: String,
    val state: String = "pending",
    val result: String? = null,
    val errorCode: String? = null,
) {
    init {
        require(normalizedOrigin(origin) == origin)
        listOf(commandId, ownerId, workspaceId, sessionId, generationId).forEach { require(UUID.fromString(it).toString() == it) }
        require(method in setOf("POST", "PUT", "DELETE"))
        val adjustmentPath = path.matches(Regex("/workspaces/$workspaceId/accounts/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/adjustments"))
        require(path.matches(Regex("/workspaces/$workspaceId/(accounts|transactions|categories|tags)(/[a-z0-9-]+)?")) || (method == "POST" && adjustmentPath))
        require(body.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        require(state in setOf("pending", "confirmed", "rejected", "reconcile"))
        require((state == "confirmed") == (result != null))
        require((state in setOf("rejected", "reconcile")) == (errorCode != null))
    }
    fun matches(session: StoredSession) = origin == session.origin && ownerId == session.ownerId &&
        workspaceId == session.workspaceId && sessionId == session.sessionId && generationId == session.generationId
    override fun toString() = "FinancialCommand([redacted])"
}

@Dao
interface CommandDao {
    @Query("SELECT * FROM financial_commands WHERE origin=:origin AND ownerId=:owner AND workspaceId=:workspace")
    suspend fun get(origin: String, owner: String, workspace: String): FinancialCommand?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(command: FinancialCommand)
    @Query("UPDATE financial_commands SET state='confirmed', result=:result, errorCode=NULL WHERE commandId=:id AND state='pending'")
    suspend fun confirm(id: String, result: String): Int
    @Query("UPDATE financial_commands SET state=:state, errorCode=:code WHERE commandId=:id AND state='pending'")
    suspend fun reject(id: String, state: String, code: String): Int
    @Query("UPDATE financial_commands SET sessionId=:session WHERE commandId=:id AND origin=:origin AND ownerId=:owner AND workspaceId=:workspace AND generationId=:generation AND state='pending'")
    suspend fun rebind(id: String, origin: String, owner: String, workspace: String, generation: String, session: String): Int
    @Query("DELETE FROM financial_commands WHERE commandId=:id AND state=:state")
    suspend fun remove(id: String, state: String): Int
}
