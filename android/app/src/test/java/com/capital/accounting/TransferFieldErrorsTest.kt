package com.capital.accounting

import com.capital.accounting.finance.*
import org.junit.Assert.*
import org.junit.Test

class TransferFieldErrorsTest {
    private fun errors(source: String = "1", target: String = "2", sourceCurrency: String = "EUR", targetCurrency: String = "KWD", feeEnabled: Boolean = false, feeAmount: String = "bad", feeNote: String = "", parts: List<AllocationDraft> = listOf(AllocationDraft("a"))) =
        transferFieldErrors(source, target, sourceCurrency, targetCurrency, "2026-10-06T12:00:00", "UTC", "", "", feeEnabled, "JPY", feeAmount, feeNote, parts)
    @Test fun sameCurrencyIgnoresHiddenTargetAndDisabledFee() {
        assertTrue(errors(target = "bad", targetCurrency = "EUR", feeNote = "x".repeat(2001)).isEmpty())
        assertEquals(setOf("sourceAmount"), errors(source = "0", target = "bad", targetCurrency = "EUR").keys)
    }
    @Test fun currenciesAndFeeValidateIndependentlyInFormOrder() {
        assertEquals(listOf("sourceAmount", "targetAmount", "feeAmount", "feeNote", "fee.part.a", "fee.part.b"),
            errors(source = "1,001", target = "-1", feeEnabled = true, feeAmount = "1,1", feeNote = "x".repeat(2001), parts = listOf(AllocationDraft("a", null, "bad"), AllocationDraft("b", null, "0"))).keys.toList())
    }
    @Test fun feeSplitMustMatchExactlyInItsOwnCurrency() {
        val parts = listOf(AllocationDraft("a", null, "1"), AllocationDraft("b", null, "2"))
        assertEquals(setOf("fee.part.a", "fee.part.b"), errors(feeEnabled = true, feeAmount = "4", parts = parts).keys)
        assertTrue(errors(feeEnabled = true, feeAmount = "3", parts = parts).isEmpty())
    }
}
