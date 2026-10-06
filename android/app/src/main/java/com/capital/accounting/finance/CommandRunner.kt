package com.capital.accounting.finance

import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.CommandDao
import com.capital.accounting.data.FinancialCommand
import java.util.UUID

/** The caller persists before sending and explicitly retries or acknowledges results. */
class CommandRunner(private val commands: CommandDao) {
    suspend fun prepare(session: StoredSession, suffix: String, method: String, body: String): FinancialCommand {
        require(!session.logoutPending && session.revokeSessionId == null)
        val command = FinancialCommand(UUID.randomUUID().toString(), session.origin, session.ownerId,
            session.workspaceId, session.sessionId, session.generationId,
            "/workspaces/${session.workspaceId}/$suffix", method, body)
        commands.insert(command)
        return command
    }

    suspend fun send(session: StoredSession): FinancialCommand {
        val command = requireNotNull(commands.get(session.origin, session.ownerId, session.workspaceId))
        require(command.matches(session) && !session.logoutPending && session.revokeSessionId == null)
        if (command.state != "pending") return command
        try {
            val response = ApiClient(session.origin).request(command.path, command.method, command.body,
                session, command.commandId, command.generationId)
            val result = decodeResponse<MutationResult>(response)
            require(result.action_id == command.commandId && result.generation_id == command.generationId)
            require(result.accounts.all { it.workspace_id == command.workspaceId } &&
                result.transactions.all { it.workspace_id == command.workspaceId } &&
                result.categories.all { it.workspace_id == command.workspaceId } &&
                result.tags.all { it.workspace_id == command.workspaceId } &&
                result.deleted.all { it.workspace_id == command.workspaceId })
            commands.confirm(command.commandId, response)
        } catch (e: ApiFailure) {
            if (e.code in setOf("action_result_expired", "idempotency_conflict")) {
                commands.reject(command.commandId, "reconcile", e.code)
            } else if (e.status in 400..499 && e.status !in setOf(401, 408, 425, 429)) {
                commands.reject(command.commandId, "rejected", e.code)
            } else throw e
        }
        return requireNotNull(commands.get(command.origin, command.ownerId, command.workspaceId))
    }
}
