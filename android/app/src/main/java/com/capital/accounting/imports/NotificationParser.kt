package com.capital.accounting.imports

import com.capital.accounting.finance.Money
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** User-defined, anchored grammar. No bank format is guessed and no arbitrary regex executes. */
@Serializable
data class NotificationProfile(
    val id: String, val packageName: String, val template: String,
    val currency: String, val kind: String, val accountId: String,
    val version: Int = 1, val expectedCardLastFour:String? = null, val enabled:Boolean = true,
)
@Serializable
data class NotificationCandidate(
    val eventId: String, val profileId: String, val parserVersion: Int,
    val packageName: String, val accountId: String, val kind: String,
    val amountMinor: String, val currency: String, val merchant: String,
    val cardLastFour: String?, val observedAtMillis: Long,
)

data class NotificationEnvelope(
    val packageName: String, val notificationKey: String, val postedAtMillis: Long,
    val text: String, val groupSummary: Boolean = false,
)

object NotificationParser {
    private val token = Regex("\\{([a-zA-Z]+)\\}")
    private val allowed = setOf("amount", "currency", "merchant", "card")
    private val secret = Regex("(?iu)(?:\\botp\\b|one[ -]time|verification code|security code|код(?:ом)?[ :]*(?:подтверждения|входа|из смс)|одноразов|пароль)")
    private val longDigits = Regex("[0-9](?:[ -]?[0-9]){11,}")
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun validate(profile: NotificationProfile) {
        require(profile.id.length in 1..64 && profile.version == 1)
        require(profile.packageName.length <= 200 && packagePattern.matches(profile.packageName))
        require(profile.accountId.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
        require(profile.currency in Money.scales && profile.kind in setOf("income", "expense"))
        require(profile.template.length in 3..1000)
        val matches = token.findAll(profile.template).toList()
        val names = matches.map { it.groupValues[1] }
        require(names.toSet().size == names.size && names.all { it in allowed } && "amount" in names)
        require(matches.zipWithNext().all { (a, b) -> b.range.first > a.range.last + 1 })
        // Braces cannot hide misspelled tokens or inject regex operators.
        require(token.replace(profile.template, "").none { it == '{' || it == '}' })
        require(!secret.containsMatchIn(profile.template) && !longDigits.containsMatchIn(profile.template))
        require(profile.expectedCardLastFour==null || (profile.expectedCardLastFour.matches(Regex("[0-9]{4}")) && "card" in names))
    }

    fun parseProfiles(profiles:List<NotificationProfile>,event:NotificationEnvelope):List<NotificationCandidate> {
        require(profiles.size<=20 && profiles.map {it.id}.distinct().size==profiles.size)
        return profiles.filter {it.enabled&&it.packageName==event.packageName}.sortedBy {it.id}.mapNotNull {parse(it,event)}
    }

    fun parse(profile: NotificationProfile, event: NotificationEnvelope): NotificationCandidate? {
        validate(profile)
        if (!profile.enabled || event.packageName != profile.packageName || event.groupSummary || event.postedAtMillis <= 0 ||
            event.notificationKey.length !in 1..1024 || event.text.length !in 1..8192 ||
            secret.containsMatchIn(event.text) || longDigits.containsMatchIn(event.text)) return null
        val tokens = token.findAll(profile.template).toList()
        val pattern = buildString {
            append("\\A")
            var position = 0
            for (match in tokens) {
                append(Regex.escape(profile.template.substring(position, match.range.first)))
                append(when (match.groupValues[1]) {
                    "amount" -> "([0-9][0-9 .,\\u00a0\\u202f]{0,31})"
                    "currency" -> "([A-Z]{3})"
                    "card" -> "([0-9]{4})"
                    else -> "([^\\r\\n]{1,120}?)"
                })
                position = match.range.last + 1
            }
            append(Regex.escape(profile.template.substring(position)))
            append("\\z")
        }
        val match = Regex(pattern).matchEntire(event.text) ?: return null
        val fields = tokens.mapIndexed { index, field -> field.groupValues[1] to match.groupValues[index + 1] }.toMap()
        if(profile.expectedCardLastFour!=null && fields["card"]!=profile.expectedCardLastFour)return null
        val currency = fields["currency"] ?: profile.currency
        if (currency != profile.currency) return null
        val value = fields.getValue("amount").replace('\u00a0', ' ').replace('\u202f', ' ')
        // Spaces are accepted only as well-formed thousands groups, never as arbitrary digit joins.
        if (!value.matches(Regex("(?:[0-9]+|[0-9]{1,3}(?: [0-9]{3})+)(?:[.,][0-9]+)?"))) return null
        val minor = runCatching { Money.minor(value.replace(" ", ""), currency) }.getOrNull() ?: return null
        if (minor == "0") return null
        val merchant = fields["merchant"]?.trim() ?: ""
        if (merchant.any { it.isISOControl() } || longDigits.containsMatchIn(merchant)) return null
        // SBN keys may embed private tags. Only their scoped digest is persisted.
        val identity = listOf(event.packageName, event.notificationKey, event.postedAtMillis.toString()).joinToString("\u0000")
        val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return NotificationCandidate(id, profile.id, profile.version, profile.packageName, profile.accountId,
            profile.kind, minor, currency, merchant, fields["card"], event.postedAtMillis)
    }
}
