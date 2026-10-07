package com.capital.accounting

import com.capital.accounting.api.Rate
import com.capital.accounting.finance.transferRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferRateTest {
    @Test fun exactReducedRateUsesMajorUnitsAcrossScales() {
        assertEquals(Rate("1", "3"), transferRate("300", "100", "EUR", "USD"))
        assertEquals(Rate("1", "1"), transferRate("10000", "100", "EUR", "JPY"))
        assertEquals(Rate("1", "1"), transferRate("100", "100000", "JPY", "KWD"))
        assertEquals(Rate("8999999999999999", "9000000000000000000"), transferRate("9000000000000000", "8999999999999999", "JPY", "KWD"))
    }
    @Test fun nonPositiveOrUnequalSameCurrencyAmountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { transferRate("0", "1", "EUR", "USD") }
        assertThrows(IllegalArgumentException::class.java) { transferRate("1", "-1", "EUR", "USD") }
        assertThrows(IllegalArgumentException::class.java) { transferRate("1", "2", "KWD", "KWD") }
        assertEquals(Rate("1", "1"), transferRate("12345", "12345", "KWD", "KWD"))
    }
}
