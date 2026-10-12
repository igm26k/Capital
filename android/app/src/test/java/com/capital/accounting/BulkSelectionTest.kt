package com.capital.accounting

import com.capital.accounting.finance.BulkSelection
import com.capital.accounting.finance.validateBulkSelection
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class BulkSelectionTest {
    @Test fun acceptsExactlyOneHundredDistinctSnapshotsAndMaximumVersion() {
        validateBulkSelection(List(100) { BulkSelection(UUID.randomUUID().toString(), Long.MAX_VALUE.toString(), "Operation") })
    }
    @Test fun rejectsOversizedDuplicateAndInvalidSnapshots() {
        val item = BulkSelection(UUID.randomUUID().toString(), "1", "Operation")
        listOf(List(101) { item.copy(id = UUID.randomUUID().toString()) }, listOf(item, item),
            listOf(item.copy(version = "0")), listOf(item.copy(version = "9223372036854775808")),
            listOf(item.copy(id = "invalid")), listOf(item.copy(label = "x".repeat(4097)))).forEach { items ->
            assertThrows(IllegalArgumentException::class.java) { validateBulkSelection(items) }
        }
    }
}
