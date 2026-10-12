package com.capital.accounting.finance

import com.capital.accounting.api.AllocationInput
import java.math.BigInteger
import java.util.UUID

@kotlinx.serialization.Serializable
data class AllocationDraft(val id: String = UUID.randomUUID().toString(), val categoryId: String? = null, val amount: String = "")

fun allocationInputs(total: String, currency: String, parts: List<AllocationDraft>): List<AllocationInput> {
    require(parts.size in 1..100 && parts.map { it.id }.toSet().size == parts.size) { "Проверьте части операции." }
    val result = parts.map { part ->
        val minor = if (parts.size == 1) total else Money.minor(part.amount, currency)
        require(BigInteger(minor).signum() > 0) { "Сумма каждой части должна быть больше нуля." }
        AllocationInput(part.id, part.categoryId, minor)
    }
    require(result.fold(BigInteger.ZERO) { sum, item -> sum + BigInteger(item.amount_minor) } == BigInteger(total)) { "Сумма частей должна точно совпадать с суммой операции." }
    return result
}


data class AllocationSummary(val distributed: String, val remaining: String)

/** Each amount respects the record bound; the preview sum can exceed one record's bound. */
fun allocationSummary(total: String, currency: String, parts: List<AllocationDraft>): AllocationSummary? = try {
    val minor = Money.minor(total, currency)
    val values = parts.map { Money.minor(it.amount, currency) }
    require(BigInteger(minor).signum() > 0 && values.all { BigInteger(it).signum() > 0 })
    AllocationSummary(Money.displayTotal(values, currency),
        Money.displayTotal(listOf(minor) + values.map { BigInteger(it).negate().toString() }, currency))
} catch (_: IllegalArgumentException) { null }

@androidx.compose.runtime.Composable
fun AllocationSummaryView(total: String, currency: String?, parts: List<AllocationDraft>, fee: Boolean = false) {
    if (parts.size > 1) {
        val summary = currency?.let { allocationSummary(total, it, parts) }
        if (summary == null) androidx.compose.material3.Text("Для расчета распределения исправьте общую сумму и суммы частей.")
        else {
            androidx.compose.material3.Text("${if (fee) "Распределено комиссии" else "Распределено"}: ${summary.distributed}")
            androidx.compose.material3.Text("${if (fee) "Осталось распределить комиссию" else "Осталось распределить"}: ${summary.remaining}")
        }
    }
}
