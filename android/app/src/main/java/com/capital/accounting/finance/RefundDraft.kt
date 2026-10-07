package com.capital.accounting.finance

import com.capital.accounting.api.Allocation
import com.capital.accounting.api.RefundAllocationInput
import java.math.BigInteger
import java.util.UUID

data class RefundDraft(val originalId: String, val id: String = UUID.randomUUID().toString(), val amount: String = "")

fun refundInputs(currency: String, parts: List<RefundDraft>, remaining: Map<String, String>): Pair<String, List<RefundAllocationInput>> {
    require(parts.size in 1..100 && parts.map { it.id }.toSet().size == parts.size && parts.map { it.originalId }.toSet().size == parts.size)
    val allocations = parts.filter { it.amount.isNotEmpty() }.map { part ->
        val minor = Money.minor(part.amount, currency)
        val available = BigInteger(requireNotNull(remaining[part.originalId]))
        require(BigInteger(minor).signum() > 0 && BigInteger(minor) <= available) { "Сумма части возврата должна быть положительной и не превышать доступную." }
        RefundAllocationInput(part.id, part.originalId, minor)
    }
    require(allocations.isNotEmpty()) { "Введите сумму хотя бы одной части возврата." }
    val total = allocations.fold(BigInteger.ZERO) { sum, allocation -> sum + BigInteger(allocation.amount_minor) }.toString()
    // Apply the same aggregate monetary bound as the server, without rounding.
    require(Money.minor(Money.display(total, currency).removeSuffix(" $currency"), currency) == total)
    return total to allocations
}

/** Editing releases only this refund's reservation; other refunds remain reserved. */
fun refundRemaining(originals: List<Allocation>, current: List<Allocation> = emptyList()): Map<String, String> {
    require(originals.map { it.id }.toSet().size == originals.size)
    require(current.map { it.original_allocation_id }.toSet().size == current.size)
    val own = current.associate { requireNotNull(it.original_allocation_id) to BigInteger(it.amount_minor) }
    require(own.keys.all { key -> originals.any { it.id == key } } && own.values.all { it.signum() > 0 })
    return originals.associate { original ->
        val available = BigInteger(original.remaining_refundable_minor) + (own[original.id] ?: BigInteger.ZERO)
        require(available.signum() >= 0 && available <= BigInteger(original.amount_minor))
        original.id to available.toString()
    }
}
