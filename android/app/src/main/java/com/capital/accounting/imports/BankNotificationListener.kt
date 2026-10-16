package com.capital.accounting.imports

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.capital.accounting.CapitalApplication
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.data.IdentityStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Opt-in local capture only. Never sends money commands or uploads raw notifications. */
class BankNotificationListener : NotificationListenerService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val incoming=Channel<Pair<NotificationBinding,List<NotificationCandidate>>>(64)
    private val app get()=application as CapitalApplication
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for((binding,candidate) in incoming) {
                try {
                    val vault=app.credentialVault.load(binding.origin)
                    val saved=(vault as? VaultRead.Available)?.session ?: continue
                    if(saved.notificationBinding()!=binding || IdentityStore(app.database).read(saved)==null)continue
                    app.notificationInbox.capture(binding,candidate)
                } catch(e:CancellationException) { throw e } catch(_:Exception) { app.notificationInbox.captureErrors.incrementAndGet() }
            }
        }
    }
    override fun onListenerConnected() { scope.launch { runCatching { app.notificationInbox.restoreActive { binding ->
        val saved=(app.credentialVault.load(binding.origin) as? VaultRead.Available)?.session
        saved!=null && saved.notificationBinding()==binding && IdentityStore(app.database).read(saved)!=null
    } } } }
    override fun onNotificationPosted(sbn:StatusBarNotification) {
        val inbox=app.notificationInbox.active ?: return
        val profiles=inbox.configuredProfiles.filter {it.enabled&&it.packageName==sbn.packageName}
        if(!inbox.enabled || profiles.isEmpty() || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)return
        // Read no extras from unselected packages; no bulk/history scan or notification dismissal.
        val text=sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT) ?: return
        if(text.length !in 1..8192)return
        val candidates=runCatching { NotificationParser.parseProfiles(profiles,NotificationEnvelope(sbn.packageName,sbn.key,sbn.postTime,text.toString())) }.getOrNull() ?: return
        if(candidates.isEmpty())return
        if(incoming.trySend(inbox.binding to candidates).isFailure)app.notificationInbox.captureErrors.incrementAndGet()
    }
    override fun onDestroy() { incoming.close();scope.cancel();super.onDestroy() }
}
