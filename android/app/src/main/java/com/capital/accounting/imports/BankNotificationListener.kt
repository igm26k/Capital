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
                    if(candidate.isNotEmpty()&&app.notificationInbox.active?.configuredProfiles?.any {it.enabled&&it.autoCreatePending}==true)AutoSourceScheduler(app).schedule(binding)
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
        val profiles=inbox.configuredProfiles.filter {it.enabled&&it.channel=="push"&&it.packageName==sbn.packageName}
        if(!inbox.enabled || profiles.isEmpty() || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)return
        // Read no extras from unselected packages; no bulk/history scan or notification dismissal.
        val candidates=try {
            val extras=sbn.notification.extras
            val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body=(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?:extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            parsePushParts(profiles,sbn.packageName,sbn.key,sbn.postTime,title,body)
        }catch(_:Exception){app.notificationInbox.captureErrors.incrementAndGet();emptyList()}
        if(incoming.trySend(inbox.binding to candidates).isFailure)app.notificationInbox.captureErrors.incrementAndGet()
    }
    override fun onDestroy() { incoming.close();scope.cancel();super.onDestroy() }
}
