package com.capital.accounting.finance

import android.util.AtomicFile
import com.capital.accounting.CapitalApplication
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.auth.VaultRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class PlanningPending(val origin: String, val owner: String, val workspace: String, val issuer: String, val generation: String,
    val key: String, val path: String, val method: String, val body: String, val rejected: Boolean = false) {
    override fun toString() = "PlanningPending([redacted])"
}

/** Explicit online auxiliary commands and source job submissions, separate from the financial outbox. No automatic resend or rebase. */
class PlanningStore(private val app: CapitalApplication, private val channel: String = "planning") {
    companion object { private val gate = Mutex() }
    init { require(channel in setOf("planning", "csv", "sources")) }
    private fun allowed(s: StoredSession) = when(channel) {
        "csv" -> Regex("/workspaces/${s.workspaceId}/imports(/[0-9a-f-]{36}/confirm)?")
        "sources" -> Regex("/workspaces/${s.workspaceId}/source-events(/[0-9a-f-]{36}/confirm)?")
        else -> Regex("/workspaces/${s.workspaceId}/(budgets|rules)(/[0-9a-f-]{36})?")
    }
    private fun file(s: StoredSession): AtomicFile {
        val key = MessageDigest.getInstance("SHA-256").digest("${s.origin}\n${s.ownerId}\n${s.workspaceId}\n${s.sessionId}".toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(app.noBackupFilesDir, "$channel-$key.json"))
    }
    private suspend fun check(s: StoredSession) {
        val current = (app.credentialVault.load(s.origin) as? VaultRead.Available)?.session
        require(current != null && current.ownerId == s.ownerId && current.workspaceId == s.workspaceId && current.sessionId == s.sessionId && current.token == s.token && !current.logoutPending && current.revokeSessionId == null)
    }
    private fun read(s: StoredSession): PlanningPending? {
        val target = file(s)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        return decodeResponse<PlanningPending>(target.readFully().toString(Charsets.UTF_8)).also {
            require(it.origin == s.origin && it.owner == s.ownerId && it.workspace == s.workspaceId && it.issuer == s.sessionId)
            require(it.path.matches(allowed(s)))
            require(it.method in setOf("POST", "PUT", "DELETE"))
            if(channel=="sources")require(it.method=="POST")
        }
    }
    private fun write(s: StoredSession, pending: PlanningPending) {
        val target = file(s); val stream = target.startWrite()
        try { stream.write(ApiClient.json.encodeToString(pending).toByteArray()); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    suspend fun pending(s: StoredSession): PlanningPending? = withContext(Dispatchers.IO) { gate.withLock { check(s); read(s) } }
    suspend fun enqueue(s: StoredSession, path: String, method: String, body: String): PlanningPending = withContext(Dispatchers.IO) {
        gate.withLock {
            check(s); require(read(s) == null)
            val value = PlanningPending(s.origin, s.ownerId, s.workspaceId, s.sessionId, s.generationId, UUID.randomUUID().toString(), path, method, body)
            require(path.matches(allowed(s)))
            require(method in setOf("POST", "PUT", "DELETE")); if(channel=="sources")require(method=="POST"); write(s, value); value
        }
    }
    suspend fun send(s: StoredSession): String = withContext(Dispatchers.IO) {
        gate.withLock {
            check(s); val value = requireNotNull(read(s)); require(!value.rejected)
            try {
                val response = ApiClient(s.origin).request(value.path, value.method, value.body, s, value.key, value.generation)
                if (value.path.contains("/imports")) {
                    val result = decodeResponse<ImportPreview>(response)
                    require(result.workspace_id == s.workspaceId)
                    val expected = if (value.path.endsWith("/imports")) org.json.JSONObject(value.body).getString("id") else value.path.substringBefore("/confirm").substringAfterLast('/')
                    require(result.id == expected)
                }
                if(channel=="sources") {
                    val result=decodeResponse<SourceEvent>(response)
                    require(result.workspace_id==s.workspaceId)
                    if(value.path.endsWith("/confirm"))require(result.id==value.path.substringBefore("/confirm").substringAfterLast('/'))
                    else {
                        val input=decodeResponse<SourceEventCreate>(value.body)
                        require(result.external_id==input.external_id && result.connection_id==input.connection_id && result.account_id==input.account_id)
                    }
                }
                check(s); require(read(s) == value); file(s).delete(); response
            } catch (e: ApiFailure) {
                if (e.status in setOf(400, 404, 409, 422) && e.code !in setOf("action_result_expired", "idempotency_conflict", "sync_generation_conflict")) {
                    check(s); require(read(s) == value); write(s, value.copy(rejected = true))
                }
                throw e
            }
        }
    }
    suspend fun dismissRejected(s: StoredSession) = withContext(Dispatchers.IO) {
        gate.withLock { check(s); require(requireNotNull(read(s)).rejected); file(s).delete() }
    }
    suspend fun eraseAfterAccountDeletion(s: StoredSession) = withContext(Dispatchers.IO) {
        gate.withLock {
            file(s).delete()
            val names = app.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("$channel-") && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
                .map { File(it.path.removeSuffix(".bak")) }.distinctBy { it.path }
            for (base in names) {
                val target = AtomicFile(base)
                val value = runCatching { decodeResponse<PlanningPending>(target.readFully().toString(Charsets.UTF_8)) }.getOrNull() ?: continue
                if (value.origin == s.origin && value.owner == s.ownerId) target.delete()
            }
        }
    }

}
