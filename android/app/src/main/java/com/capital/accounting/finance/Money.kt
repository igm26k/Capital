package com.capital.accounting.finance

import java.math.BigInteger

object Money {
    val scales = linkedMapOf("EUR" to 2, "USD" to 2, "GBP" to 2, "RUB" to 2, "JPY" to 0, "KWD" to 3)
    private val maximum = BigInteger("9000000000000000")
    fun minor(value: String, currency: String): String {
        val scale = requireNotNull(scales[currency]) { "Валюта недоступна." }
        require(value.matches(Regex("-?[0-9]+([.,][0-9]+)?"))) { "Введите сумму цифрами, например 12,34." }
        val parts = value.removePrefix("-").split('.', ',')
        val fraction = parts.getOrElse(1) { "" }
        require(fraction.length <= scale) { "Для $currency допустимо знаков после запятой: $scale." }
        val amount = BigInteger(parts[0]).multiply(BigInteger.TEN.pow(scale)).add(BigInteger(fraction.padEnd(scale, '0').ifEmpty { "0" }))
        require(amount <= maximum) { "Сумма выходит за допустимый диапазон." }
        return (if (value.startsWith('-')) amount.negate() else amount).toString()
    }
    fun display(minor: String, currency: String): String {
        val scale = requireNotNull(scales[currency]) { "Валюта недоступна." }
        require(minor.matches(Regex("-?(0|[1-9][0-9]*)")))
        val amount = BigInteger(minor)
        require(amount.abs() <= maximum)
        val digits = amount.abs().toString().padStart(scale + 1, '0')
        return (if (amount.signum() < 0) "−" else "") +
            (if (scale == 0) digits else digits.dropLast(scale) + "," + digits.takeLast(scale)) + " $currency"
    }
}
