package com.capital.accounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OriginTest {
    @Test fun acceptsHttpsOriginAndNormalizesSlash() {
        assertEquals("https://localhost:8444", normalizedOrigin(" https://localhost:8444/ "))
    }
    @Test fun rejectsCredentialLeakAndInsecureOrNonOriginUrls() {
        listOf("http://localhost", "https://user:password@host", "https://host/api", "https://host?token=secret", "https://host#secret", "https://host:0", "https://host:65536").forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizedOrigin(it) }
        }
    }
}
