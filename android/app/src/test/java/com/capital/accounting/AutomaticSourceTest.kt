package com.capital.accounting

import com.capital.accounting.imports.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class AutomaticSourceTest {
    private val binding=NotificationBinding("https://example.invalid","owner","workspace","generation","session")
    private val profile=NotificationProfile("personal","example.bank","Paid {amount} {currency} at {merchant}","EUR","expense","00000000-0000-0000-0000-000000000001",autoCreatePending=true)
    private val candidate=requireNotNull(NotificationParser.parse(profile,NotificationEnvelope("example.bank","opaque",1000,"Paid 1 EUR at SHOP")))
    private fun inbox()=NotificationInbox(binding,true,profile).accept(candidate)
    @Test fun automaticConsentIsOffForLegacyProfiles() {
        val old="""{"id":"p","packageName":"example.bank","template":"Paid {amount}","currency":"EUR","kind":"expense","accountId":"account"}"""
        assertFalse(Json.decodeFromString<NotificationProfile>(old).autoCreatePending)
    }
    @Test fun consentCannotPromoteAnOldManualCapture() {
        val manual=NotificationInbox(binding,true,profile.copy(autoCreatePending=false)).accept(candidate)
        val enabled=manual.configure(profile).accept(candidate)
        assertFalse(enabled.items.single().automatic);assertFalse(automaticItem(enabled,enabled.items.single()))
        val fresh=enabled.accept(candidate.copy(eventId="b".repeat(64)))
        assertTrue(automaticItem(fresh,fresh.items.last()))
    }
    @Test fun globalAndProfileSwitchesStopAutomaticSending() {
        val captured=inbox();val item=captured.items.single()
        assertTrue(automaticItem(captured,item))
        assertFalse(automaticItem(captured.copy(enabled=false),item))
        assertFalse(automaticItem(captured.configure(profile.copy(enabled=false)),item))
        assertFalse(automaticItem(captured.configure(profile.copy(autoCreatePending=false)),item))
    }
    @Test fun ambiguityRequiresManualDeliveryEvenAfterChoosingAnAccount() {
        val other=profile.copy(id="other",accountId="00000000-0000-0000-0000-000000000002")
        val configured=NotificationInbox(binding,true,profile).configure(other)
        val captured=configured.acceptMatches(listOf(candidate,candidate.copy(profileId=other.id,accountId=other.accountId)))
        val chosen=captured.choose(candidate.eventId,profile.id)
        assertFalse(chosen.items.single().automatic);assertFalse(automaticItem(chosen,chosen.items.single()))
    }
    @Test fun stableSlotSurvivesChangedPostingTimeAndFacts() {
        val changed=requireNotNull(NotificationParser.parse(profile,NotificationEnvelope("example.bank","opaque",2000,"Paid 2 EUR at UPDATED")))
        assertEquals(candidate.notificationKeyHash,changed.notificationKeyHash);assertNotEquals(candidate.eventId,changed.eventId)
        assertFalse(automaticItem(inbox(),inbox().items.single().copy(candidate=candidate.copy(notificationKeyHash=null))))
    }
    @Test fun rejectedDeliveryCanBeLeftForManualReviewWithoutAutomaticReissue() {
        val manual=inbox().manualOnly(candidate.eventId).accept(candidate)
        assertEquals(candidate,manual.items.single().candidate);assertEquals("review",manual.items.single().state)
        assertFalse(automaticItem(manual,manual.items.single()))
    }
    @Test fun changedFactsStopAutomaticRetry() {
        val changed=inbox().accept(candidate.copy(amountMinor="200"))
        assertEquals("conflict",changed.items.single().state);assertFalse(automaticItem(changed,changed.items.single()))
    }
    @Test fun lateReceiptNeverRevivesADismissedItem() {
        val settled=inbox().dismiss(candidate.eventId).uploaded(candidate.eventId,"00000000-0000-0000-0000-000000000002","queued")
        assertEquals("dismissed",settled.items.single().state);assertNull(settled.items.single().candidate)
        assertEquals("queued",settled.items.single().serverState);assertFalse(automaticItem(settled,settled.items.single()))
    }
    @Test fun onlyServerBackedReceiptsMayBeEvictedFromAFullInbox() {
        val items=(0 until 1000).map {i->NotificationInboxItem(i.toString(16).padStart(64,'0'),"digest",candidate.copy(eventId=i.toString(16).padStart(64,'0')))}
        val full=NotificationInbox(binding,true,profile,items)
        assertEquals(1L,full.accept(candidate).rejected)
        val backed=full.uploaded(items.first().eventId,"00000000-0000-0000-0000-000000000002","applied")
        val accepted=backed.accept(candidate)
        assertEquals(1000,accepted.items.size);assertEquals(0L,accepted.rejected)
        assertFalse(accepted.items.any {it.eventId==items.first().eventId});assertTrue(accepted.items.any {it.eventId==candidate.eventId})
    }
    @Test fun unparsedNotificationCountersStoreNoNotificationContent() {
        val skipped=inbox().skipped().skipped()
        assertEquals(2L,skipped.skipped);assertEquals(inbox().items,skipped.items)
        assertEquals(1_000_000L,skipped.copy(skipped=1_000_000).skipped().skipped)
    }
}
