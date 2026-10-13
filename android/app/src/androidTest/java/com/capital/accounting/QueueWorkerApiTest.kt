package com.capital.accounting

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import java.io.File
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*
import com.capital.accounting.sync.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QueueWorkerApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun realScheduledWorkerDrainsAndChangedCredentialNeverSendsOldScope() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>(); val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("worker-${id()}@example.test", "synthetic worker password", "Queue worker", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val manager = WorkManager.getInstance(app)
        suspend fun finished(work: UUID): WorkInfo = withTimeout(60000) {
            while (true) {
                val info = withContext(Dispatchers.IO) { manager.getWorkInfoById(work).get(5, TimeUnit.SECONDS) }
                if (info != null && info.state.isFinished) return@withTimeout info
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable worker state")
        }
        try {
            app.credentialVault.save(saved); SnapshotBootstrap(app.database).load(saved)
            val date = "2026-10-13T12:00:00Z"
            suspend fun enqueue(label: String): OutboxCommand {
                val tag = Tag(id(), saved.workspaceId, "1", date, date, null, label, null)
                return OutboxStore(app.database).enqueue(saved, "tags", "POST", ApiClient.json.encodeToString(TagCreate(tag.id, label)), emptyList(), emptyList(), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag))))
            }
            repeat(24) { enqueue("Worker $it") }
            val first = QueueScheduler(app).schedule(saved); val second = QueueScheduler(app).schedule(saved)
            for (work in listOf(first, second)) {
                val result = finished(work); assertEquals(WorkInfo.State.SUCCEEDED, result.state); assertEquals("IDLE", result.outputData.getString("status")); assertTrue("Healthy queue must not enter retry backoff", result.runAttemptCount <= 1)
            }
            val workDb = File(app.noBackupFilesDir, "androidx.work.workdb").takeIf { it.exists() } ?: app.getDatabasePath("androidx.work.workdb")
            SQLiteDatabase.openDatabase(workDb.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                sqlite.rawQuery("SELECT input FROM workspec WHERE id=?", arrayOf(first.toString())).use { cursor ->
                    assertTrue(cursor.moveToFirst()); val data = Data.fromByteArray(cursor.getBlob(0)).keyValueMap
                    assertEquals(setOf("origin", "owner", "workspace", "session", "generation"), data.keys)
                    assertFalse(data.values.any { it == saved.token })
                }
            }
            assertEquals("24", SnapshotStore(app.database).read(scope)!!.head.sequence)
            assertEquals(24, SnapshotStore(app.database).read(scope)!!.items.size)
            assertTrue(app.database.outbox().commands(scope.key).all { it.state == "applied" && app.database.outbox().overlays(it.position).isEmpty() })
            val pending = enqueue("Must not send")
            // The captured worker identity differs from the live vault; no HTTP mutation is permitted.
            val wrong = StoredSession(origin, saved.token, saved.ownerId, saved.workspaceId, saved.generationId, id())
            val blocked = finished(QueueScheduler(app).schedule(wrong))
            assertEquals("SCOPE_CHANGED", blocked.outputData.getString("status"))
            assertEquals(pending, app.database.outbox().commands(scope.key).last())
            assertEquals("24", MirrorSynchronizer(app.database).refresh(saved).head.sequence)
            app.credentialVault.clear()
            val missing = finished(QueueScheduler(app).schedule(saved)); assertEquals("AUTH_REQUIRED", missing.outputData.getString("status"))
            assertEquals(pending, app.database.outbox().commands(scope.key).last())
        } finally {
            manager.cancelAllWorkByTag("capital-queue-${scope.key}").result.get(5, TimeUnit.SECONDS)
            api.request("/auth/logout", "POST", session = saved); app.credentialVault.clear()
        }
    }
}
