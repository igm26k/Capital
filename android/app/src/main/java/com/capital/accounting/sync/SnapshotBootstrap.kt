package com.capital.accounting.sync

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

/** Read-only bootstrap; it never sends, rebinds or acknowledges financial commands. */
class SnapshotBootstrap(private val db: CapitalDatabase) {
    suspend fun load(session: StoredSession): ConfirmedMirror {
        require(!session.logoutPending && session.revokeSessionId == null)
        val scope = MirrorScope(session.origin, session.ownerId, session.workspaceId)
        val gate = synchronized(gates) { gates[scope.key] ?: Mutex().also { gates[scope.key] = it } }
        return gate.withLock {
            val api = ApiClient(session.origin)
            val root = "/workspaces/${session.workspaceId}/sync/snapshots"
            suspend fun generation(): String {
                val auth = decodeResponse<BearerAuth>(api.request("/auth/session", session = session))
                require(auth.profile.id == session.ownerId && auth.workspace.id == session.workspaceId && auth.session.id == session.sessionId)
                return auth.workspace.sync_generation_id
            }
            repeat(2) {
                val liveGeneration = generation()
                val stage = db.snapshots().stage(scope.key)
                db.snapshots().start(SnapshotAttempt(scope.key, stage?.snapshot ?: UUID.randomUUID().toString()))
                val attempt = requireNotNull(db.snapshots().attempt(scope.key))
                require(stage == null || stage.snapshot == attempt.snapshot)
                try {
                    val metadata: Snapshot = if (stage == null) decodeResponse(api.request(root, "POST",
                        ApiClient.json.encodeToString(SnapshotCreate(attempt.snapshot)), session, attempt.snapshot, liveGeneration))
                    else decodeResponse(api.request("$root/${attempt.snapshot}", session = session))
                    require(metadata.id == attempt.snapshot && metadata.workspace_id == scope.workspace)
                    if (metadata.generation_id != liveGeneration) throw IOException("Sync generation changed")
                    SnapshotStore(db).begin(scope, metadata)
                    while (true) {
                        val progress = requireNotNull(db.snapshots().stage(scope.key))
                        require(progress.snapshot == attempt.snapshot)
                        if (progress.complete) break
                        val token = requireNotNull(progress.nextToken)
                        val page = decodeResponse<SnapshotPage>(api.request("$root/${attempt.snapshot}/pages?page_token=${URLEncoder.encode(token, "UTF-8")}",
                            session = session, maxResponseBytes = 65 * 1024 * 1024))
                        SnapshotStore(db).append(scope, token, page)
                    }
                    if (generation() != metadata.generation_id) throw IOException("Sync generation changed")
                    db.withTransaction {
                        SnapshotStore(db).install(scope)
                        require(db.snapshots().finish(scope.key, attempt.snapshot) == 1)
                    }
                    return@withLock requireNotNull(SnapshotStore(db).read(scope))
                } catch (e: SnapshotSuperseded) {
                    return@withLock db.withTransaction {
                        val newer = requireNotNull(SnapshotStore(db).read(scope))
                        require(newer.head.generation == e.newer.generation && java.math.BigInteger(newer.head.sequence) >= java.math.BigInteger(e.newer.sequence))
                        SnapshotStore(db).discard(scope, attempt.snapshot)
                        require(db.snapshots().finish(scope.key, attempt.snapshot) == 1)
                        newer
                    }
                } catch (e: ApiFailure) {
                    if (e.status != 410 || e.code != "snapshot_expired") throw e
                    db.withTransaction {
                        SnapshotStore(db).discard(scope, attempt.snapshot)
                        db.snapshots().finish(scope.key, attempt.snapshot)
                    }
                }
            }
            throw IOException("Snapshot changed during bootstrap")
        }
    }
    companion object { private val gates = mutableMapOf<String, Mutex>() }
}
