package com.capital.accounting

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.capital.accounting.api.*
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.data.*
import com.capital.accounting.sync.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QueueWorkerRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val proof get() = File(app.filesDir, "queue-worker-runtime.json")
    private fun origin(): String {
        assumeTrue("Host worker driver required", InstrumentationRegistry.getArguments().getString("queue_worker_host") == "true")
        return requireNotNull(InstrumentationRegistry.getArguments().getString("api_origin"))
    }
    private fun hash(body: String) = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
    private suspend fun info(id: UUID) = withContext(Dispatchers.IO) { requireNotNull(WorkManager.getInstance(app).getWorkInfoById(id).get(5, TimeUnit.SECONDS)) }
    @Test fun prepare() = runBlocking<Unit> {
        val origin = origin(); val api = ApiClient(origin)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("worker-runtime-${UUID.randomUUID()}@example.test", "synthetic runtime password", "Worker runtime", "bearer", "UTC"))))
        val saved = auth.credential(origin); app.credentialVault.save(saved); SnapshotBootstrap(app.database).load(saved)
        val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId); val date = "2026-10-13T12:00:00Z"
        repeat(2) { ordinal ->
            val tag = Tag(UUID.randomUUID().toString(), saved.workspaceId, "1", date, date, null, "Cold worker $ordinal", null)
            OutboxStore(app.database).enqueue(saved, "tags", "POST", ApiClient.json.encodeToString(TagCreate(tag.id, tag.name)), emptyList(), emptyList(), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag))))
        }
        val commands = app.database.outbox().commands(scope.key)
        proof.writeText(JSONObject().put("stage", "prepared").put("pid", Process.myPid()).put("workspace", saved.workspaceId)
            .put("owner", saved.ownerId).put("generation", saved.generationId).put("actions", JSONArray(commands.map { it.actionId }))
            .put("hashes", JSONArray(commands.map { hash(it.body) })).toString())
    }
    @Test fun outage() = runBlocking<Unit> {
        val origin = origin(); val data = JSONObject(proof.readText()); val saved = (app.credentialVault.load(origin) as VaultRead.Available).session
        val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId); val work = QueueScheduler(app).schedule(saved)
        val retried = withTimeout(60000) {
            while (true) {
                val value = info(work)
                if (value.state == WorkInfo.State.ENQUEUED && value.runAttemptCount >= 1) return@withTimeout value
                assertFalse(value.state.isFinished); delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable retry state")
        }
        assertTrue(retried.nextScheduleTimeMillis > System.currentTimeMillis())
        val commands = app.database.outbox().commands(scope.key)
        assertTrue(commands.all { it.state == "queued" }); assertEquals("0", SnapshotStore(app.database).read(scope)!!.head.sequence)
        assertEquals(data.getJSONArray("hashes").toString(), JSONArray(commands.map { hash(it.body) }).toString())
        data.put("stage", "retrying").put("pid", Process.myPid()).put("work", work.toString()).put("attempts", retried.runAttemptCount).put("backoff", true)
        proof.writeText(data.toString())
    }
    @Test fun recover() = runBlocking<Unit> {
        val origin = origin(); val data = JSONObject(proof.readText()); assertNotEquals(data.getInt("pid"), Process.myPid())
        val saved = (app.credentialVault.load(origin) as VaultRead.Available).session
        val work = UUID.fromString(data.getString("work"))
        // Do not schedule a replacement: the persisted original WorkSpec must resume itself.
        val result = withTimeout(90000) {
            while (true) {
                val value = info(work)
                if (value.state.isFinished) return@withTimeout value
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable recovery state")
        }
        assertEquals(WorkInfo.State.SUCCEEDED, result.state); assertEquals("IDLE", result.outputData.getString("status"))
        val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId); val commands = app.database.outbox().commands(scope.key)
        assertEquals(data.getJSONArray("actions").toString(), JSONArray(commands.map { it.actionId }).toString())
        assertEquals(data.getJSONArray("hashes").toString(), JSONArray(commands.map { hash(it.body) }).toString())
        assertTrue(commands.all { it.state == "applied" && it.generation == saved.generationId && it.issuerSession == saved.sessionId && app.database.outbox().overlays(it.position).isEmpty() })
        val mirror = SnapshotStore(app.database).read(scope)!!; assertEquals("2", mirror.head.sequence); assertEquals(2, mirror.items.size)
        data.put("stage", "verified").put("pid", Process.myPid()).put("original_work", true).put("command_preserved", true).put("pull_covered", true)
        proof.writeText(data.toString())
        ApiClient(origin).request("/auth/logout", "POST", session = saved); app.credentialVault.clear()
    }
}
