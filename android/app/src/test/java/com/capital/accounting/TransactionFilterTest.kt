package com.capital.accounting

import com.capital.accounting.api.TransactionFilter
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class TransactionFilterTest {
    @Test fun searchAndCursorAreEncodedAsValuesAndPreservedAcrossPages() {
        val filter = TransactionFilter(kind = "expense", search = "Оплата &tag_id=foreign + 50%")
        val query = filter.query(1, "opaque+/=&cursor=foreign")
        val values = query.split('&').associate { pair -> pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), "UTF-8") }
        assertEquals(setOf("limit", "kind", "q", "cursor"), values.keys)
        assertEquals(filter.search, values["q"])
        assertEquals("opaque+/=&cursor=foreign", values["cursor"])
        assertEquals("expense", values["kind"])
        assertEquals("1", values["limit"])
    }
    @Test fun preciseHalfOpenPeriodRejectsInvalidCalendarAndReversedOrEqualBounds() {
        assertTrue(TransactionFilter(from = "2026-10-06T08:00:00.000001Z", to = "2026-10-06T08:00:00.000002Z").active)
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(from = "2026-10-06T08:00:00.1Z", to = "2026-10-06T08:00:00.01Z") }
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(from = "2026-10-06T08:00:00Z", to = "2026-10-06T08:00:00.000000Z") }
        assertThrows(Exception::class.java) { TransactionFilter(from = "2026-02-30T08:00:00Z") }
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(from = "2026-10-06T08:00:00+03:00") }
    }
    @Test fun invalidResourceIdsKindsStatusesAndLengthsAreRejectedBeforeRequest() {
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(accountId = "other/transactions") }
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(kind = "unknown") }
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(status = "deleted") }
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(search = "a".repeat(201)) }
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter().query(0) }
    }
}
