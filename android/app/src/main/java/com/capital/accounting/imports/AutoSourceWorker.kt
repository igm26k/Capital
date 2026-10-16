package com.capital.accounting.imports

import android.content.Context
import androidx.work.*
import com.capital.accounting.CapitalApplication
import com.capital.accounting.api.*
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.data.IdentityStore
import com.capital.accounting.data.MirrorScope
import com.capital.accounting.finance.PlanningStore
import com.capital.accounting.sync.QueueCoordinator
import com.capital.accounting.sync.QueueDrainStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Only automatically consented admissions are retried. Manual decisions use their own channel. */
class AutoSourceWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    private fun finished(status:String)=Result.success(workDataOf("status" to status))
    override suspend fun doWork():Result {
        val origin=inputData.getString("origin")?:return finished("INVALID_SCOPE")
        val owner=inputData.getString("owner")?:return finished("INVALID_SCOPE")
        val workspace=inputData.getString("workspace")?:return finished("INVALID_SCOPE")
        val generation=inputData.getString("generation")?:return finished("INVALID_SCOPE")
        val issuer=inputData.getString("session")?:return finished("INVALID_SCOPE")
        val binding=NotificationBinding(origin,owner,workspace,generation,issuer)
        val app=applicationContext as CapitalApplication
        try {
            MirrorScope(origin,owner,workspace)
            val saved=(app.credentialVault.load(origin) as? VaultRead.Available)?.session?:return finished("AUTH_REQUIRED")
            if(saved.notificationBinding()!=binding)return finished("SCOPE_CHANGED")
            if(IdentityStore(app.database).read(saved)==null)return finished("AUTH_REQUIRED")
            app.notificationInbox.restoreActive {it==binding&&IdentityStore(app.database).read(saved)!=null}
            val initial=app.notificationInbox.active
            if(initial?.binding!=binding||!initial.enabled)return finished("PAUSED")
            val store=PlanningStore(app,"source-auto")
            val original=store.pending(saved)
            if(original?.rejected==true)return finished("REVIEW_REQUIRED")
            if(original==null&&initial.items.none {automaticItem(initial,it)||it.automatic&&it.serverState=="queued"})return finished("IDLE")
            // Resolve already-authorized local financial commands before looking for server duplicates.
            when(QueueCoordinator(app.database).drain(saved,maximum=100).status) {
                QueueDrainStatus.BLOCKED->return finished("FINANCIAL_QUEUE_BLOCKED")
                QueueDrainStatus.MORE->return Result.retry()
                QueueDrainStatus.IDLE->Unit
            }
            suspend fun settle():Result {
                val remaining=app.notificationInbox.read(binding).items.filter {it.automatic&&it.serverState=="queued"&&it.serverEventId!=null}.take(20)
                var queued=false
                for(item in remaining){
                    val value=decodeResponse<SourceEvent>(ApiClient(origin).request("/workspaces/$workspace/source-events/${item.serverEventId}",session=saved))
                    require(value.workspace_id==workspace&&value.id==item.serverEventId)
                    app.notificationInbox.uploaded(binding,item.eventId,value.id,value.state)
                    queued=queued||value.state=="queued"
                }
                if(queued||app.notificationInbox.read(binding).items.any {it.automatic&&it.serverState=="queued"})return Result.retry()
                if(remaining.isNotEmpty())QueueCoordinator(app.database).drain(saved,maximum=100)
                return finished("IDLE")
            }
            repeat(20) {
                val current=app.notificationInbox.active
                if(current?.binding!=binding||!current.enabled)return finished("PAUSED")
                val inbox=app.notificationInbox.read(binding)
                val pending=store.pending(saved)
                if(pending?.rejected==true)return finished("REVIEW_REQUIRED")
                val item=if(pending==null)inbox.items.firstOrNull {automaticItem(inbox,it)} else {
                    val input=decodeResponse<SourceEventCreate>(pending.body)
                    inbox.items.firstOrNull {it.eventId==input.external_id&&automaticItem(inbox,it)}
                }
                if(item==null)return if(pending==null)settle() else finished("REVIEW_REQUIRED")
                if(pending==null)store.enqueue(saved,"/workspaces/$workspace/source-events/auto","POST",ApiClient.json.encodeToString(sourceEnvelope(binding,requireNotNull(item.candidate))))
                val result=decodeResponse<SourceEvent>(store.send(saved))
                app.notificationInbox.uploaded(binding,item.eventId,result.id,result.state)
            }
            return Result.retry()
        }catch(e:CancellationException){throw e}
        catch(e:ApiFailure){
            if(e.status==401){runCatching {app.notificationInbox.disable(binding)};app.notificationInbox.pauseCapture()}
            return if(e.status in setOf(408,425,429)||e.status>=500)Result.retry() else finished("REVIEW_REQUIRED")
        }catch(_:IOException){return Result.retry()}
        catch(_:IllegalArgumentException){return finished("BLOCKED")}
        catch(_:Exception){return Result.retry()}
    }
}

class AutoSourceScheduler(context:Context) {
    private val manager=WorkManager.getInstance(context.applicationContext)
    fun schedule(binding:NotificationBinding) {
        val request=OneTimeWorkRequestBuilder<AutoSourceWorker>()
            .setInputData(workDataOf("origin" to binding.origin,"owner" to binding.ownerId,"workspace" to binding.workspaceId,"session" to binding.sessionId,"generation" to binding.generationId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .addTag("capital-source-auto-${binding.key}").build()
        manager.enqueueUniqueWork("capital-source-auto-${binding.key}",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
}
