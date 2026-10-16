package com.capital.accounting

import com.capital.accounting.api.*
import org.junit.Assert.*
import org.junit.Test

class SourcePageTest {
    @Test fun oldServerResponseStillDecodes() {
        val page=decodeResponse<SourceEventList>("""{"items":[],"has_more":true}""")
        assertTrue(page.has_more);assertNull(page.next_before_id)
    }
    @Test fun cursorAndTerminalPageDecode() {
        val id="12345678-1234-4234-8234-123456789abc"
        assertEquals(id,decodeResponse<SourceEventList>("""{"items":[],"has_more":true,"next_before_id":"$id"}""").next_before_id)
        assertNull(decodeResponse<SourceEventList>("""{"items":[],"has_more":false,"next_before_id":null}""").next_before_id)
    }
    @Test fun unexpectedCursorTypeIsRejected() {
        assertThrows(Exception::class.java) {decodeResponse<SourceEventList>("""{"items":[],"has_more":true,"next_before_id":{}}""")}
    }
}
