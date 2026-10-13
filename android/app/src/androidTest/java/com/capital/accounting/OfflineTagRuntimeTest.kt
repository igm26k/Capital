package com.capital.accounting

import android.os.Process
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

private const val offlineTagBanner = "Нет соединения. Показаны сохраненные данные; они могут быть устаревшими. Изменения категорий и тегов сохраняются на устройстве."
private fun commandHash(body: String) = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
@RunWith(AndroidJUnit4::class)
class OfflineTagPrepareTest {
    @Test fun prepare() = runBlocking<Unit> {
        assumeTrue("Host tag driver required", InstrumentationRegistry.getArguments().getString("offline_tag_host") == "true")
        val origin = requireNotNull(InstrumentationRegistry.getArguments().getString("api_origin")); val api = ApiClient(origin)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-tag-${UUID.randomUUID()}@example.test", "synthetic offline tag password", "Offline tags", "bearer", "UTC"))))
        val saved = auth.credential(origin)
        val tag = decodeResponse<MutationResult>(api.request("/workspaces/${saved.workspaceId}/tags", "POST", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "Existing offline tag")), saved, UUID.randomUUID().toString(), saved.generationId)).tags.single()
        app.database.settings().save(ConnectionSettings(origin = origin)); app.credentialVault.save(saved); IdentityStore(app.database).save(auth, origin)
        SnapshotBootstrap(app.database).load(saved)
        File(app.filesDir, "offline-tag-runtime.json").writeText(JSONObject().put("stage", "prepared").put("pid", Process.myPid())
            .put("owner", saved.ownerId).put("workspace", saved.workspaceId).put("generation", saved.generationId).put("tag", tag.id).toString())
    }
}
@RunWith(AndroidJUnit4::class)
class OfflineTagRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val proof get() = File(app.filesDir, "offline-tag-runtime.json")
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() { assumeTrue("Host tag driver required", InstrumentationRegistry.getArguments().getString("offline_tag_host") == "true") }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun wait(text: String) { compose.waitUntil(60000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(text: String) {
        compose.waitUntil(30000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    @Test fun offlineCreateEditArchive() {
        wait(offlineTagBanner); compose.onNodeWithContentDescription("Раздел Еще").performClick(); wait("Тег: Existing offline tag")
        for (name in listOf("Offline first tag", "Offline second tag")) {
            click("Новый тег"); wait("Редактор тега")
            compose.onNodeWithText("Название тега").performTextInput(name); click("Сохранить тег")
            wait("Тег: $name")
        }
        click("Изменить тег Offline first tag"); wait("Редактор тега")
        compose.onNodeWithText("Название тега").performTextClearance(); compose.onNodeWithText("Название тега").performTextInput("Edited offline first")
        click("Сохранить тег"); wait("Тег: Edited offline first")
        click("Изменить тег Edited offline first"); wait("Редактор тега"); click("Поместить в архив"); click("Сохранить тег")
        wait("Тег: Edited offline first · В архиве")
        click("Изменить тег Existing offline tag"); wait("Редактор тега")
        compose.onNodeWithText("Название тега").performTextClearance(); compose.onNodeWithText("Название тега").performTextInput("Archived offline tag")
        click("Поместить в архив"); click("Сохранить тег"); wait("Тег: Archived offline tag · В архиве")
        click("Новая категория"); wait("Редактор категории"); compose.onNodeWithText("Название категории").performTextInput("Offline parent")
        click("Сохранить категорию"); wait("Категория: Offline parent")
        click("Новая категория"); wait("Редактор категории"); compose.onNodeWithText("Название категории").performTextInput("Offline child")
        click("Родитель категории: Offline parent"); click("Сохранить категорию"); wait("Категория: Offline parent / Offline child")
        click("Изменить категорию Offline parent / Offline child"); wait("Редактор категории")
        compose.onNodeWithText("Название категории").performTextClearance(); compose.onNodeWithText("Название категории").performTextInput("Archived child")
        click("Поместить в архив"); click("Сохранить категорию"); wait("Категория: Offline parent / Archived child · В архиве")
        wait("Локальные изменения ожидают синхронизации: 8.")
        runBlocking {
            val data = JSONObject(proof.readText()); val scope = MirrorScope("https://localhost:8444", data.getString("owner"), data.getString("workspace"))
            val commands = app.database.outbox().commands(scope.key); assertEquals(8, commands.size); assertTrue(commands.all { it.state == "queued" })
            val original = SnapshotStore(app.database).read(scope)!!; assertEquals("1", original.head.sequence)
            assertEquals("Existing offline tag", original.items.filterIsInstance<SnapshotItemTag>().single().data.name)
            val changed = commands.first { it.path.endsWith("/tags/${data.getString("tag")}") }; assertEquals("1", decodeResponse<TagUpdate>(changed.body).expected_version)
            data.put("stage", "queued").put("pid", Process.myPid()).put("actions", JSONArray(commands.map { it.actionId }))
                .put("hashes", JSONArray(commands.map { commandHash(it.body) })).put("local_ui", true).put("confirmed_preserved", true)
            proof.writeText(data.toString())
        }
    }
    @Test fun onlineColdRecover() {
        val before = JSONObject(proof.readText()); assertNotEquals(before.getInt("pid"), Process.myPid())
        wait("Синхронизация завершена.")
        compose.waitUntil(60000) { compose.onAllNodesWithText("Локальные изменения ожидают синхронизации: 8.").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Раздел Еще").performClick(); wait("Тег: Edited offline first · В архиве"); wait("Тег: Offline second tag"); wait("Тег: Archived offline tag · В архиве"); wait("Категория: Offline parent / Archived child · В архиве")
        runBlocking {
            val scope = MirrorScope("https://localhost:8444", before.getString("owner"), before.getString("workspace")); val commands = app.database.outbox().commands(scope.key)
            assertEquals(8, commands.size)
            for ((index, command) in commands.withIndex()) {
                assertEquals(before.getJSONArray("actions").getString(index), command.actionId); assertEquals(before.getJSONArray("hashes").getString(index), commandHash(command.body))
                assertEquals("applied", command.state); assertTrue(app.database.outbox().overlays(command.position).isEmpty())
            }
            val mirror = SnapshotStore(app.database).read(scope)!!; assertEquals("9", mirror.head.sequence)
            assertEquals("2", mirror.items.filterIsInstance<SnapshotItemTag>().first { it.data.id == before.getString("tag") }.data.version)
            before.put("stage", "verified").put("pid", Process.myPid()).put("cold_recovered", true).put("command_preserved", true).put("pull_covered", true)
            proof.writeText(before.toString())
            val saved = (app.credentialVault.load(scope.origin) as VaultRead.Available).session
            ApiClient(scope.origin).request("/auth/logout", "POST", session = saved); app.credentialVault.clear()
        }
    }
}
