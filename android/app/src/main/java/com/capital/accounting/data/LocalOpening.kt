package com.capital.accounting.data

import com.capital.accounting.api.*
import com.capital.accounting.finance.Money
import kotlinx.serialization.encodeToString

/** A presentation-only opening has no server ID and never appears in the immutable HTTP body. */
internal fun validateLocalOpening(path: String, method: String, body: String, workspace: String, effect: OverlayEffect) {
    require(method == "POST" && path == "/workspaces/$workspace/accounts" && effect.entityType == "transaction")
    val input = decodeResponse<AccountCreate>(body)
    Money.display(input.opening_balance_minor, input.currency)
    val item = decodeResponse<SnapshotItemTransaction>(requireNotNull(effect.payload)); val tx = item.data
    require(tx.id == effect.entityId && tx.workspace_id == workspace && tx.kind == "opening" && tx.status == "posted" && tx.version == "1" && tx.deleted_at == null)
    require(normalizedOpeningInstant(tx.occurred_at) == normalizedOpeningInstant(input.opened_at) && tx.occurred_timezone == input.occurred_timezone && tx.note.isEmpty() && tx.payee.isEmpty() &&
        tx.reason == null && tx.parent_transaction_id == null && tx.fee_transaction_id == null && tx.rate == null && tx.allocations.isEmpty() && tx.tag_ids.isEmpty())
    if (input.opening_balance_minor == "0") require(tx.entries.isEmpty())
    else require(tx.entries.size == 1 && tx.entries.single().let { it.account_id == input.id && it.currency == input.currency && it.amount_minor == input.opening_balance_minor })
}
internal fun validateAccountOpeningReceipt(command: OutboxCommand, result: MutationResult) {
    require(command.method == "POST" && command.path == "/workspaces/${command.workspace}/accounts")
    val input = decodeResponse<AccountCreate>(command.body)
    val account = result.accounts.single(); val opening = result.transactions.single()
    require(result.categories.isEmpty() && result.tags.isEmpty() && result.deleted.isEmpty())
    require(account.id == input.id && account.currency == input.currency && account.posted_balance_minor == input.opening_balance_minor && account.pending_delta_minor == "0" && account.projected_balance_minor == input.opening_balance_minor)
    validateLocalOpening(command.path, command.method, command.body, command.workspace,
        OverlayEffect("transaction", opening.id, "opening", ApiClient.json.encodeToString(SnapshotItemTransaction("transaction", opening))))
}

private fun normalizedOpeningInstant(value: String): String {
    require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")))
    return value.take(19) + value.drop(19).removeSuffix("Z").removePrefix(".").padEnd(6, '0')
}
