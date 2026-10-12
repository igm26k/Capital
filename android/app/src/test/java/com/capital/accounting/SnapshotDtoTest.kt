package com.capital.accounting

import com.capital.accounting.api.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SnapshotDtoTest {
    private val id = "12345678-1234-4123-8123-123456789abc"
    private fun item(): SnapshotItem = SnapshotItemTag("tag", Tag(id, id, "1", "2026-10-01T12:00:00Z", "2026-10-01T12:00:00Z", null, "Snapshot tag", null))
    @Test fun typedUnionRoundTripsAndSequenceRemainsExactString() {
        val page = SnapshotPage(id, id, "9223372036854775807", listOf(item()), null, id)
        val encoded = ApiClient.json.encodeToString(page)
        val decoded = decodeResponse<SnapshotPage>(encoded)
        assertEquals(page, decoded)
        assertTrue(decoded.items.single() is SnapshotItemTag)
        assertFalse(encoded.contains("SnapshotItemTag"))
        assertFalse(page.toString().contains("Snapshot tag"))
    }
    @Test fun unknownTypeWrongPayloadAndExtraFieldsFailWithoutBodyDiagnostics() {
        val raw = ApiClient.json.encodeToString(item())
        for (invalid in listOf(raw.replace("\"tag\"", "\"unknown\""), raw.replace("\"tag\"", "\"account\""), raw.replaceFirst("{", "{\"unexpected\":\"sensitive\","))) {
            try { decodeResponse<SnapshotItem>(invalid); fail("Invalid union accepted") }
            catch (e: IOException) { assertEquals("Invalid API response", e.message) }
        }
    }
}
