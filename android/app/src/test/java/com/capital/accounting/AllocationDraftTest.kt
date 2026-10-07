package com.capital.accounting

import com.capital.accounting.finance.*
import org.junit.Assert.*
import org.junit.Test

class AllocationDraftTest {
    @Test fun exactKwdPartsAndStableIdentifiers() {
        val parts = listOf(AllocationDraft(amount = "5.001"), AllocationDraft(amount = "7.344"))
        val result = allocationInputs("12345", "KWD", parts)
        assertEquals(listOf("5001", "7344"), result.map { it.amount_minor })
        assertEquals(parts.map { it.id }, result.map { it.id })
        assertEquals("12345", allocationInputs("12345", "KWD", listOf(parts[0])).single().amount_minor)
    }
    @Test fun mismatchNonpositiveAndDuplicatePartsAreRejected() {
        val part = AllocationDraft(amount = "1")
        assertThrows(IllegalArgumentException::class.java) { allocationInputs("201", "EUR", listOf(part, AllocationDraft(amount = "1"))) }
        assertThrows(IllegalArgumentException::class.java) { allocationInputs("100", "EUR", listOf(part, AllocationDraft(amount = "0"))) }
        assertThrows(IllegalArgumentException::class.java) { allocationInputs("200", "EUR", listOf(part, part)) }
    }
}
