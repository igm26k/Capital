package com.capital.accounting.imports

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.capital.accounting.CapitalApplication
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.data.IdentityStore
import kotlinx.coroutines.*

/** New SMS only; no inbox query, no raw message storage, no network request in the receiver. */
class BankSmsReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION || ContextCompat.checkSelfPermission(context,Manifest.permission.RECEIVE_SMS)!=PackageManager.PERMISSION_GRANTED)return
        val application=context.applicationContext as CapitalApplication
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeout(8_000) {
                val store=application.notificationInbox
                if(store.active==null)store.restoreActive {binding->
                    val saved=(application.credentialVault.load(binding.origin) as? VaultRead.Available)?.session
                    saved!=null&&saved.notificationBinding()==binding&&IdentityStore(application.database).read(saved)!=null
                }
                val inbox=store.active?:return@withTimeout
                if(!inbox.enabled)return@withTimeout
                val profiles=inbox.configuredProfiles.filter {it.enabled&&it.channel=="sms"}
                if(profiles.isEmpty())return@withTimeout
                val parts=Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if(parts.isEmpty()||parts.size>64)return@withTimeout
                val sender=parts.first().originatingAddress?:return@withTimeout
                val selected=profiles.filter {normalizeSender(it.sender)==normalizeSender(sender)}
                if(selected.isEmpty()||parts.any {normalizeSender(it.originatingAddress?:"")!=normalizeSender(sender)})return@withTimeout
                val text=buildString {for(part in parts){val body=part.messageBody?:return@withTimeout;if(length+body.length>8192)return@withTimeout;append(body)}}
                val saved=(application.credentialVault.load(inbox.binding.origin) as? VaultRead.Available)?.session?:return@withTimeout
                if(saved.notificationBinding()!=inbox.binding||IdentityStore(application.database).read(saved)==null)return@withTimeout
                val candidates=NotificationParser.parseProfiles(selected,smsEnvelope(sender,parts.first().timestampMillis,text))
                store.capture(inbox.binding,candidates)
                if(candidates.isNotEmpty()&&selected.any {it.autoCreatePending})AutoSourceScheduler(application).schedule(inbox.binding)
            }} catch(e:CancellationException){throw e} catch(_:Exception){application.notificationInbox.captureErrors.incrementAndGet()}
            finally {pending.finish()}
        }
    }
}
