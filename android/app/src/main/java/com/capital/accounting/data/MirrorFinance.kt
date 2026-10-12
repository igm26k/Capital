package com.capital.accounting.data

import com.capital.accounting.api.*

data class MirrorFinance(val accounts: List<Account>, val transactions: List<Transaction>, val categories: List<Category>, val tags: List<Tag>) {
    override fun toString() = "MirrorFinance([redacted])"
}
fun ConfirmedMirror.finance(): MirrorFinance = MirrorFinance(
    items.filterIsInstance<SnapshotItemAccount>().map { it.data }.sortedBy { it.id },
    items.filterIsInstance<SnapshotItemTransaction>().map { it.data }.sortedWith(compareByDescending<Transaction> { normalizedInstant(it.occurred_at) }.thenByDescending { it.id }),
    items.filterIsInstance<SnapshotItemCategory>().map { it.data }.sortedBy { it.id },
    items.filterIsInstance<SnapshotItemTag>().map { it.data }.sortedBy { it.id },
)

private fun normalizedInstant(value: String): String = value.take(19) + value.drop(19).removeSuffix("Z").removePrefix(".").padEnd(6, '0')
