package com.capital.accounting.imports

import com.capital.accounting.api.ApiClient
import com.capital.accounting.api.SourceEventCreate
import com.capital.accounting.finance.Money
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Stable across login/device timezone changes; never encodes credentials or the raw notification. */
fun sourceEnvelope(binding:NotificationBinding,candidate:NotificationCandidate):SourceEventCreate {
    require(candidate.eventId.matches(Regex("[0-9a-f]{64}")) && candidate.amountMinor.matches(Regex("[1-9][0-9]*")))
    Money.display(candidate.amountMinor,candidate.currency)
    val namespace=ApiClient.json.encodeToString(listOf(binding.origin,binding.ownerId,binding.workspaceId,candidate.packageName,candidate.profileId,candidate.accountId))
    val connection=UUID.nameUUIDFromBytes(namespace.toByteArray(Charsets.UTF_8)).toString()
    val record=UUID.nameUUIDFromBytes("$connection:${candidate.eventId}".toByteArray(Charsets.UTF_8)).toString()
    val observed=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.ROOT).apply {timeZone=TimeZone.getTimeZone("UTC")}.format(Date(candidate.observedAtMillis))
    return SourceEventCreate(record,connection,candidate.eventId,candidate.accountId,candidate.packageName,candidate.profileId,candidate.parserVersion,candidate.kind,candidate.amountMinor,candidate.currency,observed,"UTC",candidate.merchant,candidate.cardLastFour,candidate.notificationKeyHash)
}
