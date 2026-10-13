package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.*
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.sync.SnapshotBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProjectionScreenTest {
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    private lateinit var auth: BearerAuth
    private lateinit var command: OutboxCommand
    private lateinit var tag: Tag
    @get:Rule(order = -1) val name = TestName()
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue(InstrumentationRegistry.getArguments().getString("api_origin") != null)
            val api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("projection-${UUID.randomUUID()}@example.test", "synthetic projection password", "Projection UI", "bearer", "UTC"))))
            val saved = auth.credential(origin)
            tag = decodeResponse<MutationResult>(api.request("/workspaces/${saved.workspaceId}/tags", "POST", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "Server projection tag")), saved, UUID.randomUUID().toString(), saved.generationId)).tags.single()
            SnapshotBootstrap(app.database).load(saved)
            if (name.methodName !in setOf("queuedProjectionSurvivesRecreationAndCancelRestoresConfirmedTag", "revokedSessionWorkerHidesCacheAndPreservesQueuedCommand")) enqueue()
            if (name.methodName == "rejectedProjectionKeepsConfirmedTagAndBlocksWrites") {
                val sending = OutboxLifecycle(app.database).claim(saved)!!
                OutboxLifecycle(app.database).failed(sending, "rejected", ApiFailure(409, "version_conflict", UUID.randomUUID().toString()))
            }
            app.database.settings().save(ConnectionSettings(origin = origin)); app.credentialVault.save(saved)
        }
        override fun after() = runBlocking<Unit> {
            if (::auth.isInitialized) try { ApiClient(origin).request("/auth/logout", "POST", session = auth.credential(origin)) }
            catch (e: ApiFailure) { if (e.status != 401) throw e }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private suspend fun enqueue() {
        command = OutboxStore(app.database).enqueue(auth.credential(origin), "tags/${tag.id}", "PUT", ApiClient.json.encodeToString(TagUpdate(tag.version, "Local projection tag", false)), listOf(OutboxBase("tag", tag.id, tag.version)), emptyList(), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag.copy(name = "Local projection tag")))))
    }
    private fun queueAfterRestore() {
        compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("Раздел Еще").fetchSemanticsNodes().isNotEmpty() }
        more(); wait("Тег: Server projection tag")
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Изменить тег Server projection tag") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { enqueue() }
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Обновить финансовые данные") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Обновить финансовые данные").performScrollTo().performClick()
        wait("Локальные изменения ожидают синхронизации: 1.")
    }
    private fun wait(text: String) { compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun more() { compose.onNodeWithContentDescription("Раздел Еще").performClick() }
    @Test fun queuedProjectionSurvivesRecreationAndCancelRestoresConfirmedTag() {
        queueAfterRestore()
        wait("Локальные изменения ожидают синхронизации: 1."); more(); wait("Тег: Local projection tag")
        compose.onNodeWithText("Изменить тег Local projection tag").assertIsNotEnabled()
        compose.activityRule.scenario.recreate(); wait("Локальные изменения ожидают синхронизации: 1."); wait("Тег: Local projection tag")
        runBlocking {
            val scope = MirrorScope(origin, auth.profile.id, auth.workspace.id)
            assertEquals(tag, SnapshotStore(app.database).read(scope)!!.items.filterIsInstance<SnapshotItemTag>().single().data)
            assertEquals(command, app.database.outbox().commands(scope.key).single())
            OutboxStore(app.database).cancelQueued(scope, command.actionId)
        }
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Обновить финансовые данные") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Обновить финансовые данные").performScrollTo().performClick(); wait("Тег: Server projection tag")
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Изменить тег Server projection tag") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Локальные изменения ожидают синхронизации: 1.").assertDoesNotExist()
        runBlocking { assertEquals(tag, SnapshotStore(app.database).read(MirrorScope(origin, auth.profile.id, auth.workspace.id))!!.items.filterIsInstance<SnapshotItemTag>().single().data) }
    }
    @Test fun startupWorkerSyncConfirmsLocalTagAndRefreshesControls() {
        wait("Синхронизация завершена."); more(); wait("Тег: Local projection tag")
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Изменить тег Local projection tag") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Локальные изменения ожидают синхронизации: 1.").assertDoesNotExist()
        runBlocking {
            val scope = MirrorScope(origin, auth.profile.id, auth.workspace.id)
            assertEquals(command.copy(state = "applied"), app.database.outbox().commands(scope.key).single())
            assertTrue(app.database.outbox().overlays(command.position).isEmpty())
            val confirmed = SnapshotStore(app.database).read(scope)!!
            assertEquals("2", confirmed.head.sequence); assertEquals("2", confirmed.items.filterIsInstance<SnapshotItemTag>().single().data.version)
            assertEquals("Local projection tag", confirmed.items.filterIsInstance<SnapshotItemTag>().single().data.name)
        }
        compose.activityRule.scenario.recreate(); wait("Тег: Local projection tag")
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Изменить тег Local projection tag") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { assertEquals("2", SnapshotStore(app.database).read(MirrorScope(origin, auth.profile.id, auth.workspace.id))!!.head.sequence) }
    }
    @Test fun revokedSessionWorkerHidesCacheAndPreservesQueuedCommand() {
        queueAfterRestore()
        wait("Локальные изменения ожидают синхронизации: 1."); more(); wait("Тег: Local projection tag")
        runBlocking { ApiClient(origin).request("/auth/logout", "POST", session = auth.credential(origin)) }
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Синхронизировать изменения") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Синхронизировать изменения").performScrollTo().performClick(); wait("Сессия завершена. Войдите снова.")
        compose.onNodeWithText("Тег: Local projection tag").assertDoesNotExist()
        compose.onNodeWithText("Войти").assertExists()
        runBlocking {
            assertTrue(app.credentialVault.load(origin) is VaultRead.Empty)
            assertEquals(command, app.database.outbox().commands(MirrorScope(origin, auth.profile.id, auth.workspace.id).key).single())
            assertEquals(1, app.database.outbox().overlays(command.position).size)
        }
    }
    @Test fun rejectedProjectionKeepsConfirmedTagAndBlocksWrites() {
        wait("Сохраненные изменения требуют проверки: 1."); more(); wait("Тег: Server projection tag")
        compose.onNodeWithText("Тег: Local projection tag").assertDoesNotExist()
        compose.onNodeWithText("Изменить тег Server projection tag").assertIsNotEnabled()
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Синхронизировать изменения") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Синхронизировать изменения").performScrollTo().performClick(); wait("Сохраненные изменения требуют проверки.")
        runBlocking {
            val scope = MirrorScope(origin, auth.profile.id, auth.workspace.id)
            assertEquals(command.copy(state = "rejected"), app.database.outbox().commands(scope.key).single())
            assertEquals(1, app.database.outbox().overlays(command.position).size)
        }
    }
}
