package com.capital.accounting

import com.capital.accounting.api.*
import com.capital.accounting.data.OutboxCommand
import com.capital.accounting.sync.checkedActionOutcome
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ActionOutcomeTest {
    private fun id() = UUID.randomUUID().toString()
    private val date = "2026-10-12T12:00:00Z"
    private val command = OutboxCommand(1, "scope", id(), "https://accounting.example", id(), id(), id(), id(), "/tags", "POST", "{}", "[]", "[]", "uncertain")
    private val tag = Tag(id(), command.workspace, "1", date, date, null, "Synthetic", null)
    private val body = MutationResult(command.actionId, emptyList(), emptyList(), emptyList(), listOf(tag), emptyList(), "1", command.generation)
    private fun outcome() = ActionOutcome(command.actionId, command.workspace, "applied", date, date, 201, "1", listOf(ReceiptEntity("tag", tag.id, "1")), body, command.generation)
    private fun check(outcome: ActionOutcome) = checkedActionOutcome(command, ApiClient.json.encodeToString(outcome))
    @Test fun typedSuccessRejectedAndExpiredPayloadRoundTrip() {
        assertEquals(outcome(), check(outcome())); assertEquals(outcome().copy(response_body = null), check(outcome().copy(response_body = null)))
        val error = com.capital.accounting.api.Error(id(), "version_conflict", "Synthetic", emptyList(), emptyList(), null)
        val rejected = outcome().copy(state = "rejected", original_http_status = 409, group_sequence = null, generation_id = null, entity_refs = emptyList(), response_body = error)
        assertEquals(rejected, check(rejected)); assertEquals(rejected.copy(response_body = null), check(rejected.copy(response_body = null)))
        assertEquals("Error([redacted])", error.toString())
    }
    @Test fun mismatchedScopeStateSequenceRefsAndPayloadAreRejected() {
        for (bad in listOf(outcome().copy(action_id = id()), outcome().copy(workspace_id = id()), outcome().copy(generation_id = id()), outcome().copy(group_sequence = "01"), outcome().copy(original_http_status = 409), outcome().copy(entity_refs = emptyList()), outcome().copy(entity_refs = outcome().entity_refs + outcome().entity_refs), outcome().copy(response_body = body.copy(tags = listOf(tag.copy(workspace_id = id())))), outcome().copy(entity_refs = listOf(ReceiptEntity("tag", id(), "1"))), outcome().copy(state = "rejected"))) {
            try { check(bad); fail("Invalid outcome accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
