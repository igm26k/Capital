package com.capital.accounting.finance

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun openingLocal(instant: String, zone: String): String {
    require(zone in TimeZone.getAvailableIDs())
    require(instant.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")))
    val format = "yyyy-MM-dd'T'HH:mm:ss"
    val parser = SimpleDateFormat(format, Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val date = requireNotNull(parser.parse(instant.take(19)))
    return SimpleDateFormat(format, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone(zone) }.format(date)
}

fun openingInstant(local: String, zone: String): String {
    require(zone in TimeZone.getAvailableIDs()) { "Проверьте часовой пояс." }
    require(local.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"))) { "Проверьте дату." }
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone(zone) }
    val date = requireNotNull(parser.parse(local))
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
}
