package com.capital.accounting.imports

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class NotificationBinding(val origin: String, val ownerId: String, val workspaceId: String, val generationId: String, val sessionId: String) {
    val configurationKey:String get()=MessageDigest.getInstance("SHA-256").digest(listOf(origin,ownerId,workspaceId,generationId).joinToString("\u0000").toByteArray()).joinToString("") {"%02x".format(it)}
    val key: String get() = MessageDigest.getInstance("SHA-256").digest(listOf(origin,ownerId,workspaceId,generationId,sessionId).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
    override fun toString() = "NotificationBinding([redacted])"
}
@Serializable
data class NotificationInboxItem(val eventId: String, val fingerprint: String, val candidate: NotificationCandidate?, val state: String = "review", val serverEventId:String? = null, val alternatives:List<NotificationCandidate> = emptyList(),val profileIds:List<String> = emptyList(),val revisions:List<NotificationCandidate> = emptyList(),val automatic:Boolean=false,val serverState:String?=null)
@Serializable
data class NotificationInbox(
    val binding: NotificationBinding, val enabled: Boolean = false,
    val profile: NotificationProfile? = null, val items: List<NotificationInboxItem> = emptyList(),
    val rejected: Long = 0, val format: Int = 1, val profiles:List<NotificationProfile> = emptyList(),val skipped:Long=0,
) {
    val configuredProfiles:List<NotificationProfile> get()=profiles.ifEmpty {listOfNotNull(profile)}
    fun configure(value:NotificationProfile):NotificationInbox {
        NotificationParser.validate(value)
        val prior=configuredProfiles.find {it.id==value.id}
        require(prior==null || prior.copy(enabled=value.enabled,autoCreatePending=value.autoCreatePending,bankName=value.bankName)==value || items.none {it.referencesProfile(value.id)})
        val configured=(configuredProfiles.filterNot {it.id==value.id}+value).sortedBy {it.id}
        require(configured.size<=20)
        return copy(profile=value,profiles=configured)
    }
    fun removeProfile(id:String):NotificationInbox {
        require(configuredProfiles.any {it.id==id})
        val retained=configuredProfiles.filterNot {it.id==id}
        return copy(profile=retained.firstOrNull(),profiles=retained,enabled=enabled&&retained.any {it.enabled})
    }
    fun review(candidate:NotificationCandidate):NotificationInbox =
        copy(enabled=true).accept(candidate).manualOnly(candidate.eventId).copy(enabled=enabled)
    fun accept(candidate:NotificationCandidate):NotificationInbox=acceptMatches(listOf(candidate))
    fun acceptMatches(matches:List<NotificationCandidate>):NotificationInbox {
        require(format==1 && enabled && matches.isNotEmpty() && matches.size<=20 && matches.map {it.profileId}.distinct().size==matches.size)
        val ordered=matches.sortedBy {it.profileId}
        require(ordered.map {it.eventId}.distinct().size==1)
        for(candidate in ordered) {
            val definition=requireNotNull(configuredProfiles.find {it.id==candidate.profileId})
            require(definition.enabled && candidate.parserVersion==definition.version && candidate.accountId==definition.accountId && candidate.currency==definition.currency && candidate.packageName==definition.packageName && candidate.kind==definition.kind)
        }
        val encoded=if(ordered.size==1)Json.encodeToString(ordered.single().copy(notificationKeyHash=null)) else Json.encodeToString(ordered.map {it.copy(notificationKeyHash=null)})
        val sha=MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") {"%02x".format(it)}
        val id=ordered.first().eventId
        val previous=items.find {it.eventId==id}
        if(previous!=null) {
            if(previous.fingerprint==sha || previous.state=="dismissed")return this
            return copy(items=items.map {if(it.eventId==id)it.copy(state="conflict",automatic=false,revisions=(it.revisions+ordered).distinct().takeLast(20)) else it})
        }
        val removable=items.firstOrNull {it.serverEventId!=null&&it.state in setOf("uploaded","dismissed")}
        val retained=if(items.size<1000)items else if(removable!=null)items.filterNot {it.eventId==removable.eventId} else return copy(rejected=(rejected+1).coerceAtMost(1_000_000))
        val item=NotificationInboxItem(id,sha,ordered.singleOrNull(),if(ordered.size==1)"review" else "ambiguous",alternatives=if(ordered.size>1)ordered else emptyList(),profileIds=ordered.map {it.profileId},automatic=ordered.size==1&&configuredProfiles.single {it.id==ordered.single().profileId}.autoCreatePending)
        return copy(items=retained+item)
    }
    fun choose(id:String,profileId:String):NotificationInbox {
        val item=items.single {it.eventId==id}
        require(item.state=="ambiguous" && item.serverEventId==null)
        val candidate=item.alternatives.single {it.profileId==profileId}
        return copy(items=items.map {if(it.eventId==id)it.copy(candidate=candidate,state="review") else it})
    }
    fun manualOnly(id:String):NotificationInbox=copy(items=items.map {if(it.eventId==id)it.copy(automatic=false) else it})
    fun skipped():NotificationInbox=copy(skipped=(skipped+1).coerceAtMost(1_000_000))
    fun uploaded(id:String,serverId:String,serverState:String?=null):NotificationInbox {
        require(serverId.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
        require(items.any {it.eventId==id})
        return copy(items=items.map {if(it.eventId==id)it.copy(state=if(it.state in setOf("conflict","dismissed"))it.state else "uploaded",serverEventId=serverId,serverState=serverState) else it})
    }
    fun dismiss(id: String): NotificationInbox {
        require(items.any { it.eventId == id })
        return copy(items=items.map { if (it.eventId == id) it.copy(candidate=null,state="dismissed",alternatives=emptyList(),revisions=emptyList(),profileIds=it.profileIds.ifEmpty {listOfNotNull(it.candidate?.profileId)+it.alternatives.map {c->c.profileId}}) else it })
    }
    override fun toString() = "NotificationInbox([redacted])"
}

fun NotificationInboxItem.referencesProfile(id:String):Boolean=id in profileIds || candidate?.profileId==id || alternatives.any {it.profileId==id} || revisions.any {it.profileId==id}
