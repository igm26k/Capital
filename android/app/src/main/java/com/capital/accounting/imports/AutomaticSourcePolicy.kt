package com.capital.accounting.imports

/** Consent is recorded at capture time; enabling a profile never sends old manual observations. */
fun automaticItem(inbox:NotificationInbox,item:NotificationInboxItem):Boolean {
    if(!inbox.enabled||!item.automatic||item.state!="review"||item.serverEventId!=null||item.profileIds.size!=1||item.alternatives.isNotEmpty()||item.revisions.isNotEmpty())return false
    val candidate=item.candidate?:return false
    if(candidate.notificationKeyHash?.matches(Regex("[0-9a-f]{64}"))!=true)return false
    val profile=inbox.configuredProfiles.singleOrNull {it.id==candidate.profileId}?:return false
    return profile.enabled&&profile.autoCreatePending&&profile.id==item.profileIds.single()&&candidate.packageName==profile.packageName&&candidate.accountId==profile.accountId&&candidate.currency==profile.currency&&candidate.kind==profile.kind&&candidate.parserVersion==profile.version
}
