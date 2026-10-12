package com.capital.accounting.sync

import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*
import java.io.IOException
import java.net.URLEncoder

/** Read-only convergence. Financial commands and their original generation remain separate. */
class MirrorSynchronizer(private val db: CapitalDatabase) {
    suspend fun refresh(session: StoredSession, limit: Int = 50): ConfirmedMirror {
        require(limit in 1..100 && !session.logoutPending && session.revokeSessionId == null)
        val scope = MirrorScope(session.origin, session.ownerId, session.workspaceId)
        return SyncGate.serialized(scope) {
            val api = ApiClient(session.origin)
            val auth = decodeResponse<BearerAuth>(api.request("/auth/session", session = session))
            require(auth.profile.id == session.ownerId && auth.workspace.id == session.workspaceId && auth.session.id == session.sessionId)
            val head = db.snapshots().head(scope.key)
            if (head == null || head.generation != auth.workspace.sync_generation_id || db.snapshots().stage(scope.key) != null || db.snapshots().attempt(scope.key) != null) {
                SnapshotBootstrap(db).loadLocked(session)
            }
            var recoveries = 0
            while (true) {
                val captured = requireNotNull(db.snapshots().head(scope.key))
                try {
                    val page = decodeResponse<SyncChanges>(api.request("/workspaces/${scope.workspace}/sync/changes?cursor=${URLEncoder.encode(captured.cursor, "UTF-8")}&limit=$limit",
                        session = session, maxResponseBytes = 4 * 1024 * 1024))
                    PullStore(db).apply(scope, captured, page)
                    if (!page.has_more) return@serialized requireNotNull(SnapshotStore(db).read(scope))
                } catch (e: ApiFailure) {
                    if (e.status != 410 || e.code != "cursor_expired") throw e
                    if (++recoveries > 2) throw IOException("Cursor changed during synchronization")
                    SnapshotBootstrap(db).loadLocked(session)
                }
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable synchronization state")
        }
    }
}
