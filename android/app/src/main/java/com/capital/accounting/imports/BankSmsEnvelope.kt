package com.capital.accounting.imports

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

fun normalizeSender(value:String):String=Normalizer.normalize(value,Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
private fun bankDigest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
fun smsPackage(sender:String)="android.sms.s"+bankDigest(normalizeSender(sender))
/** No raw body or sender is retained in event identity. Timestamp is the original SMS timestamp. */
fun smsEnvelope(sender:String,observedAt:Long,text:String):NotificationEnvelope {
    require(sender.isNotBlank()&&sender.length<=100&&sender.none {it.isISOControl()}&&observedAt>0&&text.length in 1..8192)
    val canonical=text.replace("\r\n","\n").replace('\r','\n').trim()
    val identity=bankDigest(listOf(normalizeSender(sender),observedAt.toString(),canonical).joinToString("\u0000"))
    return NotificationEnvelope(smsPackage(sender),"sms:$identity",observedAt,canonical,channel="sms",sender=sender)
}
fun bankMessageEnvelope(profile:NotificationProfile,observedAt:Long,text:String):NotificationEnvelope =
    if(profile.channel=="sms")smsEnvelope(profile.sender,observedAt,text)
    else NotificationEnvelope(profile.packageName,"manual:"+bankDigest(listOf(profile.packageName,observedAt.toString(),text.replace("\r\n","\n").trim()).joinToString("\u0000")),observedAt,text.replace("\r\n","\n").trim())

/** Templates explicitly select title/body; no guessed concatenation or ambiguous amount extraction. */
fun parsePushParts(profiles:List<NotificationProfile>,packageName:String,key:String,time:Long,title:String,body:String):List<NotificationCandidate> {
    require(profiles.size<=20)
    return profiles.filter {it.enabled&&it.channel=="push"&&it.packageName==packageName}.sortedBy {it.id}.mapNotNull {profile->
        val text=when(profile.textSource){"title"->title;"title_body"->if(title.isEmpty())body else if(body.isEmpty())title else "$title\n$body";else->body}
        if(text.length !in 1..8192)null else NotificationParser.parse(profile,NotificationEnvelope(packageName,key,time,text))
    }
}
