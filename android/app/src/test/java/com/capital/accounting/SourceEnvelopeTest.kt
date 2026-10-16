package com.capital.accounting

import com.capital.accounting.imports.*
import com.capital.accounting.api.ApiClient
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SourceEnvelopeTest {
    private val binding=NotificationBinding("https://example.invalid","owner","workspace","generation","session")
    private val profile=NotificationProfile("personal-v1","example.bank","Paid {amount} {currency} at {merchant}","KWD","expense","00000000-0000-0000-0000-000000000001")
    private val candidate=requireNotNull(NotificationParser.parse(profile,NotificationEnvelope("example.bank","private notification key",1000,"Paid 0.001 KWD at SHOP")))
    @Test fun identitySurvivesReloginAndGenerationRotation() {
        assertEquals(sourceEnvelope(binding,candidate),sourceEnvelope(binding.copy(sessionId="new",generationId="new"),candidate))
        assertNotEquals(sourceEnvelope(binding,candidate).connection_id,sourceEnvelope(binding.copy(ownerId="other"),candidate).connection_id)
        assertNotEquals(sourceEnvelope(binding,candidate).connection_id,sourceEnvelope(binding.copy(workspaceId="other"),candidate).connection_id)
    }
    @Test fun payloadIsExactStructuredUtcWithoutRawNotificationOrCredentials() {
        val result=sourceEnvelope(binding,candidate)
        assertEquals("1",result.amount_minor);assertEquals("1970-01-01T00:00:01.000Z",result.observed_at);assertEquals("UTC",result.observed_timezone)
        val encoded=ApiClient.json.encodeToString(result)
        assertFalse(encoded.contains("private notification key"));assertFalse(encoded.contains("Paid 0.001"));assertFalse(encoded.contains("session"));assertFalse(encoded.contains("token"))
    }
    @Test fun uploadReceiptSurvivesRestartWithoutRevivingOrReissuingObservation() {
        val inbox=NotificationInbox(binding,true,profile).accept(candidate).uploaded(candidate.eventId,"00000000-0000-0000-0000-000000000002")
        val restored=ApiClient.json.decodeFromString<NotificationInbox>(ApiClient.json.encodeToString(inbox))
        assertEquals(restored,restored.accept(candidate));assertEquals("uploaded",restored.items.single().state)
        assertEquals("00000000-0000-0000-0000-000000000002",restored.items.single().serverEventId)
    }
}
