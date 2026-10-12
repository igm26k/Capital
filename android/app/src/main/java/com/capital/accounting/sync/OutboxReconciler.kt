package com.capital.accounting.sync

import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*

class OutboxReconciler(private val db: CapitalDatabase) {
    suspend fun inspect(session: StoredSession, actionId: String): OutboxOutcome? {
        require(!session.logoutPending && session.revokeSessionId == null)
        val scope = MirrorScope(session.origin, session.ownerId, session.workspaceId)
        return SyncGate.serialized(scope) {
            val api = ApiClient(session.origin)
            val auth = decodeResponse<BearerAuth>(api.request("/auth/session", session = session))
            require(auth.profile.id == session.ownerId && auth.workspace.id == session.workspaceId && auth.session.id == session.sessionId && auth.session.is_current)
            // Read-only inspection may resolve an old-generation command after recovery; never rebind it.
            val command = requireNotNull(db.outbox().commands(scope.key).find { it.actionId == actionId })
            require(command.state in setOf("sending", "uncertain", "reconcile", "rejected", "applied"))
            try {
                val response = api.request("/workspaces/${scope.workspace}/actions/$actionId", session = session)
                OutboxOutcomeStore(db).accept(command, response)
            } catch (e: ApiFailure) {
                if (e.status == 404 && e.code == "not_found") null else throw e
            }
        }
    }
}
