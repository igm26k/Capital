package com.capital.accounting

import com.capital.accounting.api.ApiFailure
import org.junit.Assert.*
import org.junit.Test

class ApiFailureTest {
    @Test fun onlyCanonicalRequestIdentifiersReachPresentation() {
        val id = "12345678-1234-4123-8123-123456789abc"
        assertEquals(id, ApiFailure(400, "invalid", id).requestId)
        assertEquals(" Идентификатор обращения: $id", ApiFailure(400, "invalid", id).requestReference)
        for (value in listOf(null, "", "secret body", "Bearer sensitive", id + "\ntrace", "x".repeat(500))) {
            assertNull(ApiFailure(500, "error", value).requestId)
            assertEquals("", ApiFailure(500, "error", value).requestReference)
        }
        assertEquals("HTTP 400", ApiFailure(400, "invalid", id).message)
    }
}
