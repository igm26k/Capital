package com.capital.accounting.sync

import com.capital.accounting.api.*
import com.capital.accounting.data.OutboxCommand
import java.math.BigInteger
import java.util.UUID

/** A 404 is handled by the caller as unknown, never as proof that an action was not applied. */
fun checkedActionOutcome(command: OutboxCommand, response: String): ActionOutcome {
    val outcome = decodeResponse<ActionOutcome>(response)
    require(outcome.action_id == command.actionId && outcome.workspace_id == command.workspace)
    require(outcome.entity_refs.size <= 200 && outcome.entity_refs.map { it.entity_type to it.id }.toSet().size == outcome.entity_refs.size)
    for (ref in outcome.entity_refs) {
        require(UUID.fromString(ref.id).toString() == ref.id)
        positive(ref.version)
    }
    if (outcome.state == "applied") {
        require(outcome.original_http_status in 200..299 && outcome.generation_id == command.generation)
        positive(requireNotNull(outcome.group_sequence)); require(outcome.entity_refs.isNotEmpty())
        outcome.response_body?.let { body ->
            require(body is MutationResult && body.action_id == command.actionId && body.generation_id == command.generation && body.sync_group_sequence == outcome.group_sequence)
            val refs = body.accounts.map { ReceiptEntity("account", it.id, it.version) }.also { require(body.accounts.all { it.workspace_id == command.workspace }) } +
                body.transactions.map { ReceiptEntity("transaction", it.id, it.version) }.also { require(body.transactions.all { it.workspace_id == command.workspace }) } +
                body.categories.map { ReceiptEntity("category", it.id, it.version) }.also { require(body.categories.all { it.workspace_id == command.workspace }) } +
                body.tags.map { ReceiptEntity("tag", it.id, it.version) }.also { require(body.tags.all { it.workspace_id == command.workspace }) } +
                body.deleted.map { ReceiptEntity(it.entity_type, it.id, it.version) }.also { require(body.deleted.all { it.workspace_id == command.workspace }) }
            require(refs.size == outcome.entity_refs.size && refs.toSet() == outcome.entity_refs.toSet())
        }
    } else {
        require(outcome.state == "rejected" && outcome.original_http_status in 400..499 && outcome.group_sequence == null && outcome.generation_id == null && outcome.entity_refs.isEmpty())
        require(outcome.response_body == null || outcome.response_body is com.capital.accounting.api.Error)
    }
    return outcome
}
private fun positive(value: String) { require(value.matches(Regex("[1-9][0-9]*")) && BigInteger(value) <= BigInteger.valueOf(Long.MAX_VALUE)) }
