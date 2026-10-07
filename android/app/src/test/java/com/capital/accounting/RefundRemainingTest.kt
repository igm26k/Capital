package com.capital.accounting

import com.capital.accounting.api.Allocation
import com.capital.accounting.finance.refundRemaining
import org.junit.Assert.*
import org.junit.Test

class RefundRemainingTest {
    @Test fun editingReleasesOwnReservationButKeepsCompetingRefunds() {
        val originals = listOf(Allocation("a", null, "1000", null, "200"), Allocation("b", null, "2000", null, "700"))
        val own = listOf(Allocation("refund-a", null, "300", "a", "0"), Allocation("refund-b", null, "200", "b", "0"))
        assertEquals(mapOf("a" to "200", "b" to "700"), refundRemaining(originals))
        assertEquals(mapOf("a" to "500", "b" to "900"), refundRemaining(originals, own))
    }
    @Test fun inconsistentReservationsOrOriginalReferencesAreRejected() {
        val originals = listOf(Allocation("a", null, "1000", null, "200"))
        assertThrows(IllegalArgumentException::class.java) { refundRemaining(originals, listOf(Allocation("r", null, "900", "a", "0"))) }
        assertThrows(IllegalArgumentException::class.java) { refundRemaining(originals, listOf(Allocation("r", null, "100", "missing", "0"))) }
        assertThrows(IllegalArgumentException::class.java) { refundRemaining(originals, listOf(Allocation("r", null, "100", "a", "0"), Allocation("s", null, "100", "a", "0"))) }
    }
}
