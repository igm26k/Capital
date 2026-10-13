package com.capital.accounting.sync

import android.content.Context
import androidx.work.*
import com.capital.accounting.CapitalApplication
import com.capital.accounting.api.ApiFailure
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.data.MirrorScope
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** WorkManager persists identifiers only; each attempt obtains the current Keystore credential. */
class QueueWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val origin = inputData.getString("origin") ?: return finished("INVALID_SCOPE")
        val owner = inputData.getString("owner") ?: return finished("INVALID_SCOPE")
        val workspace = inputData.getString("workspace") ?: return finished("INVALID_SCOPE")
        val app = applicationContext as CapitalApplication
        try {
            MirrorScope(origin, owner, workspace)
            val saved = (app.credentialVault.load(origin) as? VaultRead.Available)?.session ?: return finished("AUTH_REQUIRED")
            if (saved.ownerId != owner || saved.workspaceId != workspace || saved.sessionId != inputData.getString("session") || saved.generationId != inputData.getString("generation"))
                return finished("SCOPE_CHANGED")
            if (saved.logoutPending || saved.revokeSessionId != null) return finished("AUTH_REQUIRED")
            return when (QueueCoordinator(app.database).drain(saved, maximum = 100).status) {
                QueueDrainStatus.IDLE -> finished("IDLE")
                QueueDrainStatus.BLOCKED -> finished("BLOCKED")
                QueueDrainStatus.MORE -> Result.retry()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: ApiFailure) {
            return when {
                e.status in setOf(408, 425, 429) || e.status >= 500 -> Result.retry()
                e.status in setOf(401, 403, 404) -> finished("AUTH_REQUIRED")
                else -> finished("BLOCKED")
            }
        } catch (_: IOException) { return Result.retry() }
        catch (_: IllegalArgumentException) { return finished("BLOCKED") }
        catch (_: Exception) { return Result.retry() }
    }
    private fun finished(status: String) = Result.success(workDataOf("status" to status))
}

class QueueScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context.applicationContext)
    fun schedule(saved: StoredSession): java.util.UUID {
        require(!saved.logoutPending && saved.revokeSessionId == null)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val request = OneTimeWorkRequestBuilder<QueueWorker>()
            .setInputData(workDataOf("origin" to saved.origin, "owner" to saved.ownerId, "workspace" to saved.workspaceId,
                "session" to saved.sessionId, "generation" to saved.generationId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("capital-queue-${scope.key}")
            .build()
        // A trigger arriving while another drain finishes is appended, rather than silently lost.
        manager.enqueueUniqueWork("capital-queue-${scope.key}", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        return request.id
    }
}
