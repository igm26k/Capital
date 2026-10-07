package com.capital.accounting.finance

import com.capital.accounting.api.Rate
import java.math.BigInteger

/** Target major units per source major unit, including different currency scales. */
fun transferRate(source: String, target: String, sourceCurrency: String, targetCurrency: String): Rate {
    val sourceMinor = BigInteger(source)
    val targetMinor = BigInteger(target)
    require(sourceMinor.signum() > 0 && targetMinor.signum() > 0)
    require(sourceCurrency != targetCurrency || sourceMinor == targetMinor) { "В одной валюте суммы списания и зачисления должны совпадать." }
    val numerator = targetMinor * BigInteger.TEN.pow(requireNotNull(Money.scales[sourceCurrency]))
    val denominator = sourceMinor * BigInteger.TEN.pow(requireNotNull(Money.scales[targetCurrency]))
    val gcd = numerator.gcd(denominator)
    return Rate((numerator / gcd).toString(), (denominator / gcd).toString())
}
