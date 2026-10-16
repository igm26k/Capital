package com.capital.accounting

import com.capital.accounting.imports.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NotificationInboxTest {
    private val binding=NotificationBinding("https://example.invalid","owner","workspace","generation","session")
    private val profile=NotificationProfile("test","example.bank","Paid {amount}","USD","expense","00000000-0000-0000-0000-000000000001")
    private val candidate=requireNotNull(NotificationParser.parse(profile,NotificationEnvelope("example.bank","key",1000,"Paid 1")))
    private fun inbox()=NotificationInbox(binding,true,profile)
    @Test fun replaySurvivesSerializationAndRestart() {
        val once=inbox().accept(candidate)
        val restored=Json.decodeFromString<NotificationInbox>(Json.encodeToString(once))
        assertEquals(once,restored.accept(candidate));assertEquals(1,restored.items.size)
    }
    @Test fun changedPayloadNeverOverwritesOriginal() {
        val conflict=inbox().accept(candidate).accept(candidate.copy(amountMinor="200"))
        assertEquals("conflict",conflict.items.single().state);assertEquals("100",conflict.items.single().candidate?.amountMinor)
        assertEquals("conflict",conflict.accept(candidate).items.single().state)
    }
    @Test fun dismissalErasesMerchantButRetainsReplayReceipt() {
        val original=candidate.copy(merchant="SENSITIVE SHOP")
        val dismissed=inbox().accept(original).dismiss(original.eventId)
        assertFalse(Json.encodeToString(dismissed).contains("SENSITIVE SHOP"))
        assertEquals(dismissed,dismissed.accept(original));assertEquals(dismissed,dismissed.accept(original.copy(amountMinor="200")))
    }
    @Test fun distinctIdentityPreservesIdenticalPurchases() {
        val result=inbox().accept(candidate).accept(candidate.copy(eventId="f".repeat(64)))
        assertEquals(2,result.items.size)
    }
    @Test fun capacityDoesNotEvictUnreviewedSuggestionsOrReceipts() {
        var result=inbox()
        repeat(1000) { result=result.accept(candidate.copy(eventId=it.toString())) }
        val full=result.accept(candidate.copy(eventId="overflow"))
        assertEquals(result.items,full.items);assertEquals(1L,full.rejected)
    }
    @Test fun disabledOrRemappedCaptureIsRejected() {
        assertTrue(runCatching { inbox().copy(enabled=false).accept(candidate) }.isFailure)
        assertTrue(runCatching { inbox().accept(candidate.copy(accountId="other")) }.isFailure)
        assertTrue(runCatching { inbox().accept(candidate.copy(currency="EUR")) }.isFailure)
    }
    @Test fun sessionGenerationAndOwnerAreSeparateScopes() {
        assertNotEquals(binding.key,binding.copy(sessionId="other").key)
        assertNotEquals(binding.key,binding.copy(generationId="other").key)
        assertNotEquals(binding.key,binding.copy(ownerId="other").key)
    }
}
