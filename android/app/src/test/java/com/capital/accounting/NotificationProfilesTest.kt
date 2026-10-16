package com.capital.accounting

import com.capital.accounting.imports.*
import com.capital.accounting.api.ApiClient
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class NotificationProfilesTest {
    private val binding=NotificationBinding("https://example.invalid","owner","workspace","gen","session")
    private val first=NotificationProfile("card-a","example.bank","Paid {amount} {currency} at {merchant}, *{card}","USD","expense","00000000-0000-0000-0000-000000000001")
    private val second=first.copy(id="card-b",accountId="00000000-0000-0000-0000-000000000002")
    private fun event(amount:String="1")=NotificationEnvelope("example.bank","stable-key",1000,"Paid $amount USD at SHOP, *1234")
    @Test fun overlappingProfilesRequireAnExplicitChoice() {
        val configured=NotificationInbox(binding).configure(first).configure(second).copy(enabled=true)
        val matches=NotificationParser.parseProfiles(configured.configuredProfiles,event())
        assertEquals(2,matches.size)
        val captured=configured.acceptMatches(matches)
        assertEquals("ambiguous",captured.items.single().state);assertNull(captured.items.single().candidate)
        val selected=captured.choose(matches.first().eventId,second.id)
        assertEquals(second.accountId,selected.items.single().candidate?.accountId)
        assertEquals(selected,selected.acceptMatches(matches))
        assertTrue(runCatching {selected.choose(matches.first().eventId,first.id)}.isFailure)
    }
    @Test fun cardFilterSelectsOneAccountAndDisabledProfilesReadNothing() {
        val profiles=listOf(first.copy(expectedCardLastFour="1234"),second.copy(expectedCardLastFour="5678"))
        assertEquals(first.id,NotificationParser.parseProfiles(profiles,event()).single().profileId)
        assertTrue(NotificationParser.parseProfiles(profiles.map {it.copy(enabled=false)},event()).isEmpty())
        assertTrue(runCatching {NotificationParser.validate(first.copy(template="Paid {amount}",expectedCardLastFour="1234"))}.isFailure)
        assertTrue(runCatching {NotificationParser.validate(first.copy(template="Paid {amount} card 4111111111111111"))}.isFailure)
    }
    @Test fun configuringAnotherBankPreservesOriginalReceiptsAndBindings() {
        val candidate=NotificationParser.parse(first,event())!!
        val captured=NotificationInbox(binding).configure(first).copy(enabled=true).accept(candidate)
        val configured=captured.configure(second.copy(packageName="another.bank"))
        assertEquals(captured.items,configured.items);assertEquals(2,configured.configuredProfiles.size)
        assertTrue(runCatching {configured.configure(first.copy(accountId=second.accountId))}.isFailure)
        assertFalse(configured.configure(first.copy(enabled=false)).configuredProfiles.first {it.id==first.id}.enabled)
    }
    @Test fun dismissalRemovesAlternativesAndRevisionsButKeepsReplayProof() {
        val configured=NotificationInbox(binding).configure(first).configure(second).copy(enabled=true)
        val initial=NotificationParser.parseProfiles(configured.configuredProfiles,event())
        val changed=NotificationParser.parseProfiles(configured.configuredProfiles,event("2"))
        val conflict=configured.acceptMatches(initial).acceptMatches(changed)
        assertEquals(2,conflict.items.single().revisions.size)
        val dismissed=conflict.dismiss(initial.first().eventId)
        assertTrue(dismissed.items.single().alternatives.isEmpty());assertTrue(dismissed.items.single().revisions.isEmpty())
        assertEquals(2,dismissed.items.single().profileIds.size);assertEquals(dismissed,dismissed.acceptMatches(changed))
        assertFalse(ApiClient.json.encodeToString(dismissed).contains("SHOP"))
    }
    @Test fun oldSingleProfileFilesRemainReadable() {
        val old=NotificationInbox(binding,true,first)
        val decoded=ApiClient.json.decodeFromString<NotificationInbox>(ApiClient.json.encodeToString(old))
        assertEquals(listOf(first),decoded.configuredProfiles)
        assertEquals(1,decoded.accept(NotificationParser.parse(first,event())!!).items.size)
    }
    @Test fun configurationScopeSurvivesLoginButNotAnotherOwnerOrRecovery() {
        assertEquals(binding.configurationKey,binding.copy(sessionId="new").configurationKey)
        assertNotEquals(binding.configurationKey,binding.copy(ownerId="other").configurationKey)
        assertNotEquals(binding.configurationKey,binding.copy(generationId="restored").configurationKey)
    }
    @Test fun savingAProfileNeverResumesStoppedCapture() {
        val configured=NotificationInbox(binding).configure(first).configure(second)
        assertFalse(configured.enabled)
        val stopped=configured.copy(enabled=false).configure(second.copy(enabled=false))
        assertFalse(stopped.enabled);assertTrue(stopped.configuredProfiles.first {it.id==first.id}.enabled)
    }
    @Test fun matchOrderIsStableAcrossProfileEditorSelection() {
        val one=NotificationParser.parseProfiles(listOf(first,second),event())
        val two=NotificationParser.parseProfiles(listOf(second,first),event())
        assertEquals(one,two)
        val inbox=NotificationInbox(binding).configure(first).configure(second).copy(enabled=true).acceptMatches(one)
        assertEquals(inbox,inbox.acceptMatches(two))
    }
}
