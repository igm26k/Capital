package com.capital.accounting

import com.capital.accounting.finance.*
import org.junit.Assert.*
import org.junit.Test

class TransactionFieldErrorsTest {
    @Test fun independentErrorsFollowVisibleFieldsAndPreservePartIdentity() {
        val parts = listOf(AllocationDraft("a", null, "bad"), AllocationDraft("b", null, "0"))
        assertEquals(listOf("amount", "occurred", "zone", "payee", "note", "part.a", "part.b"),
            transactionFieldErrors("0", "2026-02-30T12:00:00", "Unknown/Zone", "x".repeat(201), "x".repeat(2001), "EUR", parts).keys.toList())
    }
    @Test fun splitRequiresExactSumAndUsesStableKeys() {
        val parts = listOf(AllocationDraft("a", null, "1,00"), AllocationDraft("b", null, "2,00"))
        assertEquals(setOf("part.a", "part.b"), transactionFieldErrors("4", "2024-02-29T12:00:00", "UTC", "", "", "EUR", parts).keys)
        assertTrue(transactionFieldErrors("3", "2024-02-29T12:00:00", "UTC", "x".repeat(200), "x".repeat(2000), "EUR", parts).isEmpty())
        assertTrue(transactionFieldErrors("3", "2024-02-29T12:00:00", "UTC", "", "", "EUR", listOf(parts.first())).isEmpty())
    }
    @Test fun eachAmountHonorsCurrencyPrecisionPositivityAndRange() {
        for (raw in listOf("-1", "0", "1,1", "9000000000000001")) {
            assertEquals(setOf("amount"), transactionFieldErrors(raw, "2026-10-06T12:00:00", "UTC", "", "", "JPY", listOf(AllocationDraft())).keys)
        }
        assertTrue(transactionFieldErrors("1,234", "2026-10-06T12:00:00", "UTC", "", "", "KWD", listOf(AllocationDraft())).isEmpty())
    }
}
