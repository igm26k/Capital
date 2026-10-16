package com.capital.accounting.imports

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class NotificationBinding(val origin: String, val ownerId: String, val workspaceId: String, val generationId: String, val sessionId: String) {
    val key: String get() = MessageDigest.getInstance("SHA-256").digest(listOf(origin,ownerId,workspaceId,generationId,sessionId).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
    override fun toString() = "NotificationBinding([redacted])"
}
@Serializable
data class NotificationInboxItem(val eventId: String, val fingerprint: String, val candidate: NotificationCandidate?, val state: String = "review", val serverEventId:String? = null)
@Serializable
data class NotificationInbox(
    val binding: NotificationBinding, val enabled: Boolean = false,
    val profile: NotificationProfile? = null, val items: List<NotificationInboxItem> = emptyList(),
    val rejected: Long = 0, val format: Int = 1,
) {
    fun accept(candidate: NotificationCandidate): NotificationInbox {
        require(format == 1 && enabled && profile != null)
        require(candidate.profileId == profile.id && candidate.parserVersion == profile.version && candidate.accountId == profile.accountId && candidate.currency == profile.currency && candidate.packageName == profile.packageName && candidate.kind == profile.kind)
        val sha = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(candidate).toByteArray()).joinToString("") { "%02x".format(it) }
        val previous = items.find { it.eventId == candidate.eventId }
        // Updates never silently replace a captured financial suggestion. Dismissed receipts cannot revive.
        if (previous != null) return if (previous.fingerprint == sha || previous.state == "dismissed") this else copy(items = items.map { if (it.eventId == candidate.eventId) it.copy(state="conflict") else it })
        if (items.size >= 1000) return copy(rejected = (rejected + 1).coerceAtMost(1_000_000))
        return copy(items = items + NotificationInboxItem(candidate.eventId, sha, candidate))
    }
    fun uploaded(id:String,serverId:String):NotificationInbox {
        require(items.any {it.eventId==id})
        return copy(items=items.map {if(it.eventId==id)it.copy(state=if(it.state=="conflict")"conflict" else "uploaded",serverEventId=serverId) else it})
    }
    fun dismiss(id: String): NotificationInbox {
        require(items.any { it.eventId == id })
        return copy(items=items.map { if (it.eventId == id) it.copy(candidate=null,state="dismissed") else it })
    }
    override fun toString() = "NotificationInbox([redacted])"
}
