package com.capital.accounting.data

import com.capital.accounting.api.Account
import com.capital.accounting.finance.Money
import java.math.BigInteger
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun ledgerPositive(value: String, currency: String): BigInteger {
    require(value.matches(Regex("[1-9][0-9]*"))); Money.display(value, currency); return BigInteger(value)
}
internal fun ledgerBump(value: String): String = (BigInteger(value) + BigInteger.ONE).also { require(it <= BigInteger.valueOf(Long.MAX_VALUE)) }.toString()
internal fun ledgerInstant(value: String): BigInteger {
    require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")))
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val position = ParsePosition(0); val date = requireNotNull(parser.parse(value.take(19), position)); require(position.index == 19)
    return BigInteger.valueOf(date.time).multiply(BigInteger.valueOf(1000)) + BigInteger(value.drop(19).removeSuffix("Z").removePrefix(".").padEnd(6, '0'))
}

internal fun ledgerNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
internal fun ledgerAccountDelta(account: Account, delta: BigInteger, now: String): Account {
    val posted = (BigInteger(account.posted_balance_minor) + delta).toString()
    val projected = (BigInteger(posted) + BigInteger(account.pending_delta_minor)).toString()
    Money.display(posted, account.currency); Money.display(projected, account.currency)
    return account.copy(version = ledgerBump(account.version), balance_version = ledgerBump(account.balance_version), updated_at = now,
        posted_balance_minor = posted, projected_balance_minor = projected)
}
