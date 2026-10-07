package com.capital.accounting.finance

import com.capital.accounting.api.AllocationInput
import java.math.BigInteger
import java.util.UUID

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
