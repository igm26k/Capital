package com.capital.accounting

import com.capital.accounting.finance.openingLocal
import com.capital.accounting.finance.openingInstant
import org.junit.Assert.*
import org.junit.Test

class OpeningTimeTest {
    @Test fun restoredOpeningUsesOriginalZoneAndDst() {
        assertEquals("2026-10-06T08:00:00.000Z", openingInstant("2026-10-06T11:00:00", "Europe/Nicosia"))
        assertEquals("2026-10-06T11:00:00", openingLocal("2026-10-06T08:00:00.000Z", "Europe/Nicosia"))
        assertEquals("2026-01-06T10:00:00", openingLocal("2026-01-06T08:00:00Z", "Europe/Nicosia"))
        assertEquals("2026-10-06T08:00:00", openingLocal("2026-10-06T08:00:00.123456Z", "UTC"))
    }
    @Test fun invalidDatesAndUnknownZonesAreRejected() {
        assertThrows(java.text.ParseException::class.java) { openingInstant("2026-02-30T11:00:00", "UTC") }
        assertThrows(IllegalArgumentException::class.java) { openingInstant("invalid", "UTC") }
        assertThrows(IllegalArgumentException::class.java) { openingLocal("2026-10-06T08:00:00Z", "unknown-zone") }
        assertThrows(java.text.ParseException::class.java) { openingLocal("2026-02-30T08:00:00Z", "UTC") }
        assertThrows(IllegalArgumentException::class.java) { openingLocal("invalid", "UTC") }
    }
}
