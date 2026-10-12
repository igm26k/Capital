package com.capital.accounting

import com.capital.accounting.finance.historyFieldErrors
import org.junit.Assert.*
import org.junit.Test

class HistoryFieldErrorsTest {
    @Test fun collectsIndependentErrorsInVisibleOrder() {
        assertEquals(listOf("search", "from", "to", "limit"), historyFieldErrors("x".repeat(201), "2026-02-30T12:00:00Z", "bad", "101").keys.toList())
    }
    @Test fun periodUsesExactMicrosecondsAndAllowsOpenBounds() {
        val start = "2026-10-06T08:00:00.000001Z"
        val end = "2026-10-06T08:00:00.000002Z"
        assertTrue(historyFieldErrors("", start, end, "1").isEmpty())
        assertEquals(listOf("from", "to"), historyFieldErrors("", end, start, "100").keys.toList())
        assertEquals(setOf("from", "to"), historyFieldErrors("", start, start, "100").keys)
        assertTrue(historyFieldErrors("x".repeat(200), "", end, "100").isEmpty())
        assertTrue(historyFieldErrors("", start, "", "1").isEmpty())
    }
    @Test fun invalidDatesAndPageLimitsRemainLocal() {
        for (limit in listOf("0", "101", "1.5", "999999999999999999", "bad")) assertEquals(setOf("limit"), historyFieldErrors("", "", "", limit).keys)
        for (date in listOf("2026-02-30T00:00:00Z", "2026-10-06T08:00:00+03:00", "2026-10-06T08:00:00.1234567Z")) assertEquals(setOf("from"), historyFieldErrors("", date, "", "100").keys)
    }
}
