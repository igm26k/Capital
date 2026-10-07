package com.capital.accounting

import com.capital.accounting.finance.RefundDraft
import com.capital.accounting.finance.refundInputs
import org.junit.Assert.*
import org.junit.Test

class RefundDraftTest {
    @Test fun exactPartialAmountsKeepOriginalReferencesAndOmitBlankParts() {
        val parts = listOf(RefundDraft("original-a", "part-a", "5.001"), RefundDraft("original-b", "part-b", "7.344"), RefundDraft("original-c", "part-c"))
        val (total, allocations) = refundInputs("KWD", parts, mapOf("original-a" to "5001", "original-b" to "8000", "original-c" to "0"))
        assertEquals("12345", total)
        assertEquals(listOf("original-a", "original-b"), allocations.map { it.original_allocation_id })
        assertEquals(listOf("part-a", "part-b"), allocations.map { it.id })
        assertEquals(listOf("5001", "7344"), allocations.map { it.amount_minor })
    }
    @Test fun OverQuotaNonPositiveDuplicateOrMissingOriginalPartsAreRejected() {
        fun check(parts: List<RefundDraft>, remaining: Map<String, String> = mapOf("original" to "1000")) = assertThrows(IllegalArgumentException::class.java) { refundInputs("KWD", parts, remaining) }
        check(listOf(RefundDraft("original", "part", "1.001")))
        check(listOf(RefundDraft("original", "part", "0")))
        check(listOf(RefundDraft("original", "part", "-1")))
        check(listOf(RefundDraft("original", "part")))
        check(listOf(RefundDraft("original", "part", "0.1"), RefundDraft("original", "second", "0.1")))
        check(listOf(RefundDraft("original", "part", "0.1"), RefundDraft("other", "part", "0.1")), mapOf("original" to "1000", "other" to "1000"))
        check(listOf(RefundDraft("missing", "part", "0.1")))
    }
}
