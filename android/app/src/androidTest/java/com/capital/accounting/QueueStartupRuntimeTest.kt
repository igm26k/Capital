package com.capital.accounting

import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.capital.accounting.api.*
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QueueStartupRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val proof get() = File(app.filesDir, "queue-worker-runtime.json")
    private fun hash(body: String) = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Host startup driver required", InstrumentationRegistry.getArguments().getString("queue_startup_host") == "true")
            val data = JSONObject(proof.readText()); assertEquals("prepared", data.getString("stage")); assertNotEquals(data.getInt("pid"), Process.myPid())
            app.database.settings().save(ConnectionSettings(origin = "https://localhost:8444"))
            val scope = MirrorScope("https://localhost:8444", data.getString("owner"), data.getString("workspace"))
            val jobs = WorkManager.getInstance(app).getWorkInfosByTag("capital-queue-${scope.key}").get(5, TimeUnit.SECONDS)
            assertTrue(jobs.isEmpty())
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun restoreSchedulesRoomQueueWithoutExistingJob() {
        compose.waitUntil(60000) { compose.onAllNodesWithText("Синхронизация завершена.").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(60000) { compose.onAllNodesWithText("Локальные изменения ожидают синхронизации: 2.").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Локальные изменения ожидают синхронизации: 2.").assertDoesNotExist()
        runBlocking {
            val data = JSONObject(proof.readText()); val scope = MirrorScope("https://localhost:8444", data.getString("owner"), data.getString("workspace"))
            val commands = app.database.outbox().commands(scope.key)
            assertEquals(data.getJSONArray("actions").length(), commands.size)
            for ((index, command) in commands.withIndex()) {
                assertEquals(data.getJSONArray("actions").getString(index), command.actionId); assertEquals(data.getJSONArray("hashes").getString(index), hash(command.body))
                assertEquals("applied", command.state); assertTrue(app.database.outbox().overlays(command.position).isEmpty())
            }
            assertEquals("2", SnapshotStore(app.database).read(scope)!!.head.sequence)
            assertEquals(2, SnapshotStore(app.database).read(scope)!!.items.size)
            data.put("stage", "startup_verified").put("pid", Process.myPid()).put("missing_job_recovered", true).put("command_preserved", true).put("pull_covered", true)
            proof.writeText(data.toString())
            val saved = (app.credentialVault.load("https://localhost:8444") as VaultRead.Available).session
            ApiClient(saved.origin).request("/auth/logout", "POST", session = saved); app.credentialVault.clear()
        }
    }
}
