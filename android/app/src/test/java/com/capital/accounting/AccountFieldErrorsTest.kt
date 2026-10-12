package com.capital.accounting

import com.capital.accounting.finance.accountFieldErrors
import com.capital.accounting.finance.nameFieldError
import org.junit.Assert.*
import org.junit.Test

class AccountFieldErrorsTest {
    @Test fun collectsIndependentErrorsInVisibleFieldOrder() {
        val errors = accountFieldErrors(" ", "invalid", "2026-02-30T12:00:00", "Unknown/Zone", "KWD", true)
        assertEquals(listOf("name", "balance", "opened", "zone"), errors.keys.toList())
    }
    @Test fun acceptsNegativeOpeningAndLeapDateButRejectsCurrencyPrecisionAndRange() {
        assertTrue(accountFieldErrors("Account", "-12,345", "2024-02-29T12:00:00", "UTC", "KWD", true).isEmpty())
        assertEquals(setOf("balance"), accountFieldErrors("Account", "1,1", "2024-02-29T12:00:00", "UTC", "JPY", true).keys)
        assertEquals(setOf("balance"), accountFieldErrors("Account", "9000000000000001", "2024-02-29T12:00:00", "UTC", "JPY", true).keys)
    }
    @Test fun editingIgnoresHiddenOpeningFieldsAndNamesEnforceTheirOwnBounds() {
        assertTrue(accountFieldErrors("Account", "invalid", "invalid", "invalid", "invalid", false).isEmpty())
        assertNull(nameFieldError("x".repeat(100), 100))
        assertNotNull(nameFieldError("x".repeat(101), 100))
        assertNull(nameFieldError("x".repeat(50), 50))
        assertNotNull(nameFieldError("x".repeat(51), 50))
    }
}
