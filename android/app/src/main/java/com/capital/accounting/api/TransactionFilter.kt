package com.capital.accounting.api

import java.math.BigInteger
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Immutable applied filters accompany every cursor request. Bounds are [from, to). */
data class TransactionFilter(
    val accountId: String? = null, val categoryId: String? = null, val tagId: String? = null,
    val from: String? = null, val to: String? = null, val kind: String? = null,
    val status: String? = null, val search: String = "",
) {
    init {
        listOfNotNull(accountId, categoryId, tagId).forEach { require(UUID.fromString(it).toString() == it) }
        require(kind == null || kind in kinds.keys)
        require(status == null || status in statuses.keys)
        require(search.length <= 200)
        val start = from?.let(::instantMicros)
        val end = to?.let(::instantMicros)
        require(start == null || end == null || start < end)
    }
    fun query(limit: Int, cursor: String? = null): String {
        require(limit in 1..100)
        val parameters = linkedMapOf("limit" to limit.toString())
        listOf("account_id" to accountId, "category_id" to categoryId, "tag_id" to tagId,
            "from" to from, "to" to to, "kind" to kind, "status" to status, "q" to search.takeIf { it.isNotEmpty() }, "cursor" to cursor)
            .forEach { (name, value) -> if (value != null) parameters[name] = value }
        return parameters.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    }
    val active get() = this != TransactionFilter()
    companion object {
        val kinds = linkedMapOf("opening" to "Начальный остаток", "expense" to "Расход", "income" to "Доход", "transfer" to "Перевод", "refund" to "Возврат", "adjustment" to "Корректировка")
        val statuses = linkedMapOf("posted" to "Проведена", "pending" to "Ожидает проведения")
    }
}

private fun instantMicros(value: String): BigInteger {
    require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")))
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val milliseconds = requireNotNull(format.parse(value.take(19))).time
    val fraction = if (value.length == 20) "" else value.substring(20, value.length - 1)
    return BigInteger.valueOf(milliseconds).multiply(BigInteger.valueOf(1000)) + BigInteger(fraction.padEnd(6, '0'))
}
