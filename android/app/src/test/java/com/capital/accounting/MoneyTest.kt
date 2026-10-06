package com.capital.accounting

import com.capital.accounting.finance.Money
import org.junit.Assert.*
import org.junit.Test

class MoneyTest {
    @Test fun exactScalesSignsAndLimits() {
        assertEquals("1234", Money.minor("12,34", "EUR"))
        assertEquals("-1234", Money.minor("-12.34", "EUR"))
        assertEquals("7", Money.minor("7", "JPY"))
        assertEquals("7001", Money.minor("7.001", "KWD"))
        assertEquals("0", Money.minor("-0.00", "USD"))
        assertEquals("9000000000000000", Money.minor("90000000000000.00", "EUR"))
        assertEquals("90 000 000 000 000,00 EUR".replace(" ", ""), Money.display("9000000000000000", "EUR"))
        assertEquals("−0,01 EUR", Money.display("-1", "EUR"))
        assertEquals("0,001 KWD", Money.display("1", "KWD"))
        assertEquals("7 JPY", Money.display("7", "JPY"))
    }
    @Test fun invalidPrecisionSyntaxAndOverflowAreRejected() {
        for ((value, currency) in listOf("1.001" to "EUR", "1.0" to "JPY", "1e2" to "EUR", "+1" to "EUR", " 1" to "EUR", "1," to "EUR", "1,2.3" to "EUR", "NaN" to "EUR", "90000000000000.01" to "EUR", "-90000000000000.01" to "EUR", "1" to "XXX")) {
            assertThrows(IllegalArgumentException::class.java) { Money.minor(value, currency) }
        }
    }
}
