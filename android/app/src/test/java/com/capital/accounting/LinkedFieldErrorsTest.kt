package com.capital.accounting

import com.capital.accounting.finance.*
import org.junit.Assert.*
import org.junit.Test

class LinkedFieldErrorsTest {
    private val date = "2026-10-06T12:00:00"
    @Test fun refundAllowsUnusedPartsAndRequiresOneFilledPositiveWithinQuota() {
        val parts = listOf(RefundDraft("a", "one", ""), RefundDraft("b", "two", ""))
        val quota = mapOf("a" to "100", "b" to "200")
        assertEquals(setOf("refund.one"), refundFieldErrors("EUR", parts, quota, date, "UTC", "", "").keys)
        assertTrue(refundFieldErrors("EUR", listOf(parts[0].copy(amount = "1"), parts[1]), quota, date, "UTC", "", "").isEmpty())
        assertEquals(listOf("refund.one", "refund.two"), refundFieldErrors("EUR", listOf(parts[0].copy(amount = "1,01"), parts[1].copy(amount = "0")), quota, date, "UTC", "", "").keys.toList())
    }
    @Test fun refundChecksAggregateLimitAndUnknownOriginal() {
        val parts = listOf(RefundDraft("a", "one", "9000000000000000"), RefundDraft("b", "two", "1"))
        assertEquals(setOf("refund.one", "refund.two"), refundFieldErrors("JPY", parts, mapOf("a" to "9000000000000000", "b" to "1"), date, "UTC", "", "").keys)
        assertEquals(setOf("refund.one"), refundFieldErrors("JPY", listOf(parts[0].copy(amount = "1")), emptyMap(), date, "UTC", "", "").keys)
    }
    @Test fun adjustmentAllowsNegativeBalanceButRejectsEqualOrExcessiveDelta() {
        assertTrue(adjustmentFieldErrors("-1", "0", "EUR", "Reason", "UTC", "", true).isEmpty())
        assertEquals(setOf("target"), adjustmentFieldErrors("0,00", "0", "EUR", "Reason", "UTC", "", true).keys)
        assertEquals(setOf("target"), adjustmentFieldErrors("9000000000000000", "-1", "JPY", "Reason", "UTC", "", true).keys)
    }
    @Test fun adjustmentCollectsVisibleErrorsAndEditIgnoresHiddenFields() {
        assertEquals(listOf("target", "reason", "zone", "note"), adjustmentFieldErrors("bad", "0", "EUR", "", "Unknown/Zone", "x".repeat(2001), true).keys.toList())
        assertTrue(adjustmentFieldErrors("bad", "bad", "bad", "", "Unknown/Zone", "x".repeat(2000), false).isEmpty())
    }
}
