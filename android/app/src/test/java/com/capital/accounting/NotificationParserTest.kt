package com.capital.accounting

import com.capital.accounting.imports.*
import org.junit.Assert.*
import org.junit.Test

class NotificationParserTest {
    private val profile = NotificationProfile("personal", "example.bank", "Покупка {amount} {currency} в {merchant}. Карта *{card}", "RUB", "expense", "00000000-0000-0000-0000-000000000001")
    private fun event(text: String = "Покупка 1 234,56 RUB в SHOP. Карта *1234", key: String = "private-key", time: Long = 1000) = NotificationEnvelope("example.bank",key,time,text)
    @Test fun exactMoneyAndMaskedCard() {
        val result = requireNotNull(NotificationParser.parse(profile,event()))
        assertEquals("123456",result.amountMinor); assertEquals("1234",result.cardLastFour); assertEquals("SHOP",result.merchant)
        assertEquals(64,result.eventId.length); assertFalse(result.toString().contains("private-key"))
    }
    @Test fun currenciesRespectScalesAndRange() {
        for ((currency, amount, minor) in listOf(Triple("JPY","12","12"), Triple("KWD","0.001","1"),Triple("USD","12.01","1201"))) {
            val p = profile.copy(currency=currency,template="Received {amount} {currency}",kind="income")
            assertEquals(minor,NotificationParser.parse(p,event("Received $amount $currency"))?.amountMinor)
        }
        assertNull(NotificationParser.parse(profile,event("Покупка 0 RUB в SHOP. Карта *1234")))
        assertNull(NotificationParser.parse(profile,event("Покупка 90000000000000.01 RUB в SHOP. Карта *1234")))
    }
    @Test fun twoIdenticalPurchasesStaySeparateButReplayStable() {
        assertEquals(NotificationParser.parse(profile,event()),NotificationParser.parse(profile,event()))
        assertNotEquals(NotificationParser.parse(profile,event())?.eventId,NotificationParser.parse(profile,event(key="second"))?.eventId)
        assertNotEquals(NotificationParser.parse(profile,event())?.eventId,NotificationParser.parse(profile,event(time=1001))?.eventId)
    }
    @Test fun onlyExplicitPackageAndIndividualNotification() {
        assertNull(NotificationParser.parse(profile,event().copy(packageName="other.bank")))
        assertNull(NotificationParser.parse(profile,event().copy(groupSummary=true)))
        assertNull(NotificationParser.parse(profile,event().copy(text="")))
    }
    @Test fun rejectWrongCurrencyPrecisionAndMalformedGrouping() {
        for (text in listOf("1 23,45 RUB","1,234.56 RUB","12.345 RUB","-1 RUB","1 EUR","1 234 56 RUB"))
            assertNull(text,NotificationParser.parse(profile,event("Покупка $text в SHOP. Карта *1234")))
    }
    @Test fun rejectOtpPanAndOversize() {
        for (merchant in listOf("Код подтверждения 123456","OTP 123456","4111 1111 1111 1111","a".repeat(9000)))
            assertNull(NotificationParser.parse(profile,event("Покупка 1 RUB в $merchant. Карта *1234")))
    }
    @Test fun grammarIsAnchoredAndLiteralsAreEscaped() {
        assertNull(NotificationParser.parse(profile,event("prefix "+event().text)))
        val p=profile.copy(template="(paid) {amount} [RUB]")
        assertEquals("100",NotificationParser.parse(p,event("(paid) 1 [RUB]"))?.amountMinor)
        assertNull(NotificationParser.parse(p,event("paid 1 R")))
    }
    @Test fun validateFailsClosedForAmbiguousOrUnknownTokens() {
        for (template in listOf("{amount}{currency}","{amount} {amount}","{money}","{amount} {unknown}","{amount} {", "OTP {amount}")) {
            assertTrue(template,runCatching { NotificationParser.validate(profile.copy(template=template)) }.isFailure)
        }
    }
    @Test fun unicodeGroupingAcceptedButUnboundMerchantNotCaptured() {
        val p=profile.copy(template="Списание {amount}")
        assertEquals("123456",NotificationParser.parse(p,event("Списание 1\u202f234,56"))?.amountMinor)
        assertEquals("",NotificationParser.parse(p,event("Списание 1"))?.merchant)
    }
}
