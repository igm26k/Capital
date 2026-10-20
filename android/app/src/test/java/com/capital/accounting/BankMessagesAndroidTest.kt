package com.capital.accounting

import com.capital.accounting.api.ApiClient
import com.capital.accounting.imports.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BankMessagesAndroidTest {
    private val binding=NotificationBinding("https://example.invalid","owner","workspace","gen","session")
    private val rule=NotificationProfile("bank-rule",smsPackage("BANK"),"Оплата {amount} {currency} в {merchant}, карта *{card}","EUR","expense","00000000-0000-4000-8000-000000000001",expectedCardLastFour="1234",bankName="Мой банк",channel="sms",sender="BANK",decimalSeparator=",")
    private fun event(text:String="Оплата 1 234,56 EUR в Кофе, карта *1234",time:Long=1000)=smsEnvelope("bank",time,text)
    @Test fun smsAndPushUseTheSameWebGrammarWithExactAmounts() {
        assertEquals("123456",NotificationParser.parse(rule,event())?.amountMinor)
        assertEquals("123456",NotificationParser.parse(rule,event("Оплата 1.234,56 € в Кофе, карта *1234"))?.amountMinor)
        val push=rule.copy(channel="push",packageName="example.bank",sender="",decimalSeparator=".")
        assertEquals("123456",NotificationParser.parse(push,NotificationEnvelope("example.bank","push",1000,"Оплата 1,234.56 EUR в Кофе, карта *1234"))?.amountMinor)
        assertEquals("Кофе",NotificationParser.parse(rule,event())?.merchant)
    }
    @Test fun smsSenderChannelCardAndCurrencyMustMatch() {
        assertTrue(NotificationParser.parseProfiles(listOf(rule),event().copy(sender="OTHER")).isEmpty())
        assertTrue(NotificationParser.parseProfiles(listOf(rule),event().copy(channel="push")).isEmpty())
        assertTrue(NotificationParser.parseProfiles(listOf(rule.copy(enabled=false)),event()).isEmpty())
        for(text in listOf("Оплата 1 USD в Кофе, карта *1234","Оплата 1 EUR в Кофе, карта *9999","Оплата 1 23,45 EUR в Кофе, карта *1234","Оплата 1,234 EUR в Кофе, карта *1234","Оплата 1 EUR в OTP 123456, карта *1234"))assertNull(NotificationParser.parse(rule,event(text)))
        assertTrue(runCatching{NotificationParser.validate(rule.copy(packageName="example.other"))}.isFailure)
        assertTrue(runCatching{NotificationParser.validate(rule.copy(template="Оплата {amount} USD",expectedCardLastFour=null))}.isFailure)
    }
    @Test fun smsReplayUsesOriginalTimeSenderAndContentNotTheAmount() {
        val original=NotificationParser.parse(rule,event())!!
        assertEquals(original,NotificationParser.parse(rule,event()))
        assertEquals(original,NotificationParser.parse(rule,smsEnvelope(" BANK ",1000,"Оплата 1 234,56 EUR в Кофе, карта *1234")))
        assertNotEquals(original.eventId,NotificationParser.parse(rule,event(time=2000))?.eventId)
        assertNotEquals(original.eventId,NotificationParser.parse(rule,event("Оплата 1 234,56 EUR в Магазин, карта *1234"))?.eventId)
        assertFalse(ApiClient.json.encodeToString(original).contains("Оплата"))
        assertFalse(ApiClient.json.encodeToString(original).contains("BANK"))
    }
    @Test fun manualReviewDoesNotResumeCaptureOrCreateAutomaticCommands() {
        val automatic=rule.copy(autoCreatePending=true)
        val candidate=NotificationParser.parse(automatic,event())!!
        val stopped=NotificationInbox(binding).configure(automatic)
        val reviewed=stopped.review(candidate)
        assertFalse(reviewed.enabled);assertFalse(reviewed.items.single().automatic)
        assertEquals("review",reviewed.items.single().state)
        assertFalse(automaticItem(reviewed.copy(enabled=true),reviewed.items.single()))
        assertEquals(reviewed,reviewed.review(candidate))
        val dismissed=reviewed.dismiss(candidate.eventId)
        assertEquals(dismissed,dismissed.review(candidate))
    }
    @Test fun deletingARuleKeepsReceiptsAndDoesNotReviveAnExcludedMessage() {
        val candidate=NotificationParser.parse(rule,event())!!
        val inbox=NotificationInbox(binding).configure(rule).copy(enabled=true).accept(candidate).dismiss(candidate.eventId)
        val removed=inbox.removeProfile(rule.id)
        assertFalse(removed.enabled);assertTrue(removed.configuredProfiles.isEmpty());assertEquals(inbox.items,removed.items)
        val replacement=rule.copy(id="replacement")
        val replay=NotificationParser.parse(replacement,event())!!
        val restored=removed.configure(replacement).copy(enabled=true)
        assertEquals(restored,restored.accept(replay))
    }
    @Test fun bankRenameIsCosmeticButUsedAccountAndFormatRemainProtected() {
        val captured=NotificationInbox(binding).configure(rule).copy(enabled=true).accept(NotificationParser.parse(rule,event())!!)
        val renamed=captured.configure(rule.copy(bankName="Новое имя"))
        assertEquals(captured.items,renamed.items)
        assertTrue(runCatching {captured.configure(rule.copy(decimalSeparator="."))}.isFailure)
        val unused=rule.copy(id="unused",sender="SECOND",packageName=smsPackage("SECOND"))
        assertEquals(".",captured.configure(unused).configure(unused.copy(decimalSeparator=".")).configuredProfiles.single {it.id==unused.id}.decimalSeparator)
    }
    @Test fun pushTitleSelectionIsExplicitAndDoesNotMixWithSms() {
        val push=rule.copy(packageName="example.bank",channel="push",sender="",textSource="title")
        val text="Оплата 12,34 EUR в Кофе, карта *1234"
        assertEquals("1234",parsePushParts(listOf(push),"example.bank","key",1000,text,"Другая информация").single().amountMinor)
        assertTrue(parsePushParts(listOf(push.copy(textSource="body")),"example.bank","key",1000,text,"Другая информация").isEmpty())
        val combined=push.copy(textSource="title_body",template="Покупка\n{amount} {currency}",expectedCardLastFour=null)
        assertEquals("1234",parsePushParts(listOf(combined),"example.bank","key",1000,"Покупка","12,34 EUR").single().amountMinor)
        assertTrue(parsePushParts(listOf(rule),rule.packageName,"key",1000,text,text).isEmpty())
    }
    @Test fun oldProfilesKeepTheirSingleSeparatorBehavior() {
        val legacy=NotificationProfile("legacy","example.bank","Paid {amount} {currency}","USD","expense",rule.accountId)
        assertEquals("push",legacy.channel);assertEquals("",legacy.decimalSeparator)
        assertEquals("1234",NotificationParser.parse(legacy,NotificationEnvelope("example.bank","key",1000,"Paid 12.34 USD"))?.amountMinor)
        assertNull(NotificationParser.parse(legacy,NotificationEnvelope("example.bank","key",1000,"Paid 1,234.56 USD")))
        val json=ApiClient.json.encodeToString(legacy)
        assertFalse(json.contains("bankName"));assertFalse(json.contains("decimalSeparator"))
        assertEquals(legacy,ApiClient.json.decodeFromString<NotificationProfile>(json))
    }
}
