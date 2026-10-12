package com.capital.accounting

import com.capital.accounting.api.*
import com.capital.accounting.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MirrorFinanceTest {
    private fun id() = UUID.randomUUID().toString()
    private val workspace = id()
    private fun transaction(id: String, instant: String) = Transaction(id, workspace, "1", instant, instant, null, "expense", "posted", instant, "UTC", "Projection note", "", null, null, null, null,
        listOf(Entry(this.id(), this.id(), "EUR", "-100")), listOf(Allocation(this.id(), null, "100", null, "100")), emptyList())
    @Test fun historySortsExactUtcMicrosecondsAndDescendingIdTies() {
        val keys = listOf(id(), id(), id()).sorted()
        val zero = transaction(keys[2], "2026-10-06T12:00:00Z")
        val oneA = transaction(keys[0], "2026-10-06T12:00:00.000001Z")
        val oneB = transaction(keys[1], "2026-10-06T12:00:00.000001Z")
        val mirror = ConfirmedMirror(MirrorHead("scope", id(), "1", "opaque"), listOf(zero, oneA, oneB).map { SnapshotItemTransaction("transaction", it) })
        assertEquals(listOf(oneB, oneA, zero), mirror.finance().transactions)
        assertFalse(mirror.finance().toString().contains("Projection note"))
    }
}
