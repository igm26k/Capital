package com.capital.accounting

import android.os.Process
import androidx.room.withTransaction
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

private const val offlineTagBanner = "Нет соединения. Показаны сохраненные данные; они могут быть устаревшими. Доходы, расходы, переводы и возвраты, изменения счетов, категорий и тегов сохраняются на устройстве."
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
    private fun wait(text: String, timeout: Long = 60000) {
        try { compose.waitUntil(timeout) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
        catch (e: ComposeTimeoutException) { throw AssertionError("Expected UI label: $text", e) }
    }
    private fun click(text: String) {
        compose.waitUntil(30000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun refundCreate(label: String, note: String, amount: String) {
        click(label); wait("Сумма части возврата 1")
        compose.onNodeWithText("Сумма части возврата 1").performTextInput(amount)
        compose.onNodeWithText("Дата возврата (YYYY-MM-DDTHH:MM:SS)").performTextClearance(); compose.onNodeWithText("Дата возврата (YYYY-MM-DDTHH:MM:SS)").performTextInput("2026-10-13T12:15:00")
        compose.onNodeWithText("Часовой пояс возврата").performTextClearance(); compose.onNodeWithText("Часовой пояс возврата").performTextInput("UTC")
        compose.onNodeWithText("Примечание возврата").performTextInput(note)
        click("Сохранить возврат")
        compose.waitUntil(60000) { compose.onAllNodesWithText("Сумма части возврата 1").fetchSemanticsNodes().isEmpty() }
    }
    private fun refundEdit(note: String, amount: String? = null, newNote: String? = null) {
        click("Изменить возврат $note"); wait("Сумма части возврата 1")
        amount?.let { compose.onNodeWithText("Сумма части возврата 1").performTextClearance(); compose.onNodeWithText("Сумма части возврата 1").performTextInput(it) }
        newNote?.let { compose.onNodeWithText("Примечание возврата").performTextClearance(); compose.onNodeWithText("Примечание возврата").performTextInput(it) }
        click("Сохранить изменения возврата")
        compose.waitUntil(60000) { compose.onAllNodesWithText("Сумма части возврата 1").fetchSemanticsNodes().isEmpty() }
    }
    private fun refundDelete(note: String) {
        click("Удалить возврат $note"); click("Подтвердить удаление возврата")
        compose.waitUntil(60000) { compose.onAllNodesWithText("Удалить возврат $note").fetchSemanticsNodes().isEmpty() }
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
        compose.onNodeWithContentDescription("Раздел Счета").performClick(); click("Добавить счет")
        wait("Название счета"); compose.onNodeWithText("Название счета").performTextInput("Offline cash")
        click("KWD"); compose.onNodeWithText("Начальный остаток").performTextClearance(); compose.onNodeWithText("Начальный остаток").performTextInput("123,456")
        compose.onNodeWithText("Дата открытия (YYYY-MM-DDTHH:MM:SS)").performTextClearance(); compose.onNodeWithText("Дата открытия (YYYY-MM-DDTHH:MM:SS)").performTextInput("2026-10-13T12:00:00")
        compose.onNodeWithText("Часовой пояс открытия").performTextClearance(); compose.onNodeWithText("Часовой пояс открытия").performTextInput("UTC")
        click("Создать счет"); wait("Offline cash"); wait("123,456 KWD")
        click("Изменить счет Offline cash"); wait("Новое название счета")
        compose.onNodeWithText("Новое название счета").performTextClearance(); compose.onNodeWithText("Новое название счета").performTextInput("Renamed offline cash")
        click("Карта"); click("Сохранить счет"); wait("Renamed offline cash")
        click("Изменить счет Renamed offline cash"); wait("Счет в архиве")
        compose.onNode(isToggleable()).performClick(); click("Сохранить счет"); click("Все счета"); wait("Renamed offline cash · В архиве")
        click("Изменить счет Renamed offline cash"); wait("Счет в архиве")
        compose.onNode(isToggleable()).performClick(); click("Сохранить счет"); wait("Renamed offline cash")
        compose.onNodeWithContentDescription("Раздел Операции").performClick()
        for ((kind, amount) in listOf("Расход" to "12,345", "Доход" to "3,333")) {
            click(kind); wait("Сумма операции")
            compose.onNodeWithText("Сумма операции").performTextInput(amount)
            compose.onNodeWithText("Дата операции (YYYY-MM-DDTHH:MM:SS)").performTextClearance()
            compose.onNodeWithText("Дата операции (YYYY-MM-DDTHH:MM:SS)").performTextInput("2026-10-13T12:05:00")
            compose.onNodeWithText("Часовой пояс операции").performTextClearance()
            compose.onNodeWithText("Часовой пояс операции").performTextInput("UTC")
            click("Категория части 1: Offline parent")
            compose.onNodeWithContentDescription("Выбрать тег Offline second tag").performScrollTo().performClick()
            if (kind == "Расход") {
                click("Добавить часть операции")
                compose.onNodeWithText("Сумма части 1").performTextInput("2,000")
                compose.onNodeWithText("Сумма части 2").performTextInput("10,345")
            }
            compose.onNodeWithText("Примечание операции").performTextInput(if (kind == "Расход") "Offline expense" else "Offline income")
            click("Сохранить операцию")
            compose.waitUntil(60000) { compose.onAllNodesWithText("Сумма операции").fetchSemanticsNodes().isEmpty() }
        }
        click("Изменить операцию Offline expense"); wait("Сумма операции")
        compose.onNodeWithText("Сумма операции").performTextClearance(); compose.onNodeWithText("Сумма операции").performTextInput("15,000")
        compose.onNodeWithText("Сумма части 2").performTextClearance(); compose.onNodeWithText("Сумма части 2").performTextInput("13,000")
        compose.onNodeWithText("Примечание операции").performTextClearance(); compose.onNodeWithText("Примечание операции").performTextInput("Edited offline expense")
        click("Сохранить изменения операции")
        compose.waitUntil(60000) { compose.onAllNodesWithText("Сумма операции").fetchSemanticsNodes().isEmpty() }
        click("Изменить операцию Offline income"); wait("Сумма операции")
        compose.onNodeWithText("Сумма операции").performTextClearance(); compose.onNodeWithText("Сумма операции").performTextInput("4,444")
        compose.onNodeWithText("Примечание операции").performTextClearance(); compose.onNodeWithText("Примечание операции").performTextInput("Edited offline income")
        click("Сохранить изменения операции")
        compose.waitUntil(60000) { compose.onAllNodesWithText("Сумма операции").fetchSemanticsNodes().isEmpty() }
        runBlocking {
            val saved = (app.credentialVault.load("https://localhost:8444") as VaultRead.Available).session
            val projected = LocalProjectionStore(app.database).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))!!.visible
            assertEquals("112900", projected.accounts.single().posted_balance_minor)
        }
        refundCreate("Создать возврат расхода Edited offline expense", "Offline refund", "1,000")
        refundEdit("Offline refund", newNote = "Offline refund metadata")
        refundEdit("Offline refund metadata", amount = "1,500")
        refundDelete("Offline refund metadata")
        for (note in listOf("Edited offline expense", "Edited offline income")) {
            click("Удалить операцию $note"); click("Подтвердить удаление операции")
            compose.waitUntil(60000) { compose.onAllNodesWithText("Удалить операцию $note").fetchSemanticsNodes().isEmpty() }
        }
        compose.onNodeWithContentDescription("Раздел Счета").performClick(); click("Добавить счет")
        wait("Название счета"); compose.onNodeWithText("Название счета").performTextInput("Offline target")
        click("KWD"); compose.onNodeWithText("Начальный остаток").performTextClearance(); compose.onNodeWithText("Начальный остаток").performTextInput("10,000")
        compose.onNodeWithText("Дата открытия (YYYY-MM-DDTHH:MM:SS)").performTextClearance(); compose.onNodeWithText("Дата открытия (YYYY-MM-DDTHH:MM:SS)").performTextInput("2026-10-13T12:00:00")
        compose.onNodeWithText("Часовой пояс открытия").performTextClearance(); compose.onNodeWithText("Часовой пояс открытия").performTextInput("UTC")
        click("Создать счет"); wait("Offline target")
        compose.onNodeWithContentDescription("Раздел Операции").performClick(); click("Новый перевод"); wait("Сумма списания перевода")
        click("Списать со счета: Renamed offline cash · KWD"); click("Зачислить на счет: Offline target · KWD")
        compose.onNodeWithText("Сумма списания перевода").performTextInput("12,345")
        compose.onNodeWithText("Дата перевода (YYYY-MM-DDTHH:MM:SS)").performTextClearance(); compose.onNodeWithText("Дата перевода (YYYY-MM-DDTHH:MM:SS)").performTextInput("2026-10-13T12:10:00")
        compose.onNodeWithText("Часовой пояс перевода").performTextClearance(); compose.onNodeWithText("Часовой пояс перевода").performTextInput("UTC")
        compose.onNodeWithText("Примечание перевода").performTextInput("Offline transfer")
        click("Добавить комиссию перевода"); click("Счет комиссии: Renamed offline cash · KWD")
        compose.onNodeWithText("Сумма комиссии перевода").performTextInput("0,111")
        compose.onNodeWithText("Примечание комиссии перевода").performTextInput("Offline transfer fee")
        click("Категория комиссии 1: Offline parent")
        compose.onNodeWithContentDescription("Тег комиссии Offline second tag").performScrollTo().performClick()
        click("Сохранить перевод")
        compose.waitUntil(60000) { compose.onAllNodesWithText("Сумма списания перевода").fetchSemanticsNodes().isEmpty() }
        refundCreate("Создать возврат комиссии Offline transfer fee", "Offline fee refund", "0,100")
        refundEdit("Offline fee refund", amount = "0,050")
        refundDelete("Offline fee refund")
        click("Изменить перевод Offline transfer"); wait("Сумма списания перевода")
        compose.onNodeWithText("Сумма списания перевода").performTextClearance(); compose.onNodeWithText("Сумма списания перевода").performTextInput("20,000")
        compose.onNodeWithText("Сумма комиссии перевода").performTextClearance(); compose.onNodeWithText("Сумма комиссии перевода").performTextInput("0,222")
        click("Сохранить изменения перевода")
        compose.waitUntil(60000) { compose.onAllNodesWithText("Сумма списания перевода").fetchSemanticsNodes().isEmpty() }
        click("Изменить перевод Offline transfer"); wait("Сумма списания перевода"); click("Убрать комиссию перевода"); click("Сохранить изменения перевода")
        compose.waitUntil(60000) { compose.onAllNodesWithText("Сумма списания перевода").fetchSemanticsNodes().isEmpty() }
        click("Изменить перевод Offline transfer"); wait("Сумма списания перевода"); click("Добавить комиссию перевода")
        click("Счет комиссии: Renamed offline cash · KWD"); compose.onNodeWithText("Сумма комиссии перевода").performTextInput("0,111")
        click("Сохранить изменения перевода")
        compose.waitUntil(60000) { compose.onAllNodesWithText("Сумма списания перевода").fetchSemanticsNodes().isEmpty() }
        click("Удалить перевод Offline transfer"); click("Подтвердить удаление перевода")
        compose.waitUntil(60000) { compose.onAllNodesWithText("Удалить перевод Offline transfer").fetchSemanticsNodes().isEmpty() }
        wait("Локальные изменения ожидают синхронизации: 31.")
        runBlocking {
            val data = JSONObject(proof.readText()); val scope = MirrorScope("https://localhost:8444", data.getString("owner"), data.getString("workspace"))
            val commands = app.database.outbox().commands(scope.key); assertEquals(31, commands.size); assertTrue(commands.all { it.state == "queued" })
            val visible = LocalProjectionStore(app.database).read(scope)!!
            assertEquals(mapOf("Renamed offline cash" to "123456", "Offline target" to "10000"), visible.visible.accounts.associate { it.name to it.posted_balance_minor })
            assertTrue(visible.visible.transactions.all { it.kind == "opening" })
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
        val queuedScope = MirrorScope("https://localhost:8444", before.getString("owner"), before.getString("workspace"))
        compose.waitUntil(480000) {
            runBlocking { app.database.withTransaction {
                val commands = app.database.outbox().commands(queuedScope.key)
                commands.size == 31 && commands.all { it.state == "applied" && app.database.outbox().overlays(it.position).isEmpty() } &&
                    app.database.snapshots().head(queuedScope.key)?.sequence == "32"
            } }
        }
        compose.waitUntil(60000) { compose.onAllNodesWithText("Локальные изменения ожидают синхронизации: 31.").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Раздел Еще").performClick(); wait("Тег: Edited offline first · В архиве"); wait("Тег: Offline second tag"); wait("Тег: Archived offline tag · В архиве"); wait("Категория: Offline parent / Archived child · В архиве")
        runBlocking {
            val scope = MirrorScope("https://localhost:8444", before.getString("owner"), before.getString("workspace")); val commands = app.database.outbox().commands(scope.key)
            assertEquals(31, commands.size)
            for ((index, command) in commands.withIndex()) {
                assertEquals(before.getJSONArray("actions").getString(index), command.actionId); assertEquals(before.getJSONArray("hashes").getString(index), commandHash(command.body))
                assertEquals("applied", command.state); assertTrue(app.database.outbox().overlays(command.position).isEmpty())
            }
            val mirror = SnapshotStore(app.database).read(scope)!!; assertEquals("32", mirror.head.sequence)
            assertEquals("2", mirror.items.filterIsInstance<SnapshotItemTag>().first { it.data.id == before.getString("tag") }.data.version)
            val account = mirror.items.filterIsInstance<SnapshotItemAccount>().single { it.data.name == "Renamed offline cash" }.data
            assertEquals("123456", account.posted_balance_minor); assertEquals("21", account.version); assertEquals("18", account.balance_version); assertNull(account.archived_at)
            assertEquals("10000", mirror.finance().accounts.single { it.name == "Offline target" }.posted_balance_minor)
            assertTrue(mirror.finance().transactions.all { it.kind == "opening" })
            val creation = commands.single { it.path.endsWith("/transactions") && JSONObject(it.body).getString("kind") == "transfer" }
            val result = decodeResponse<MutationResult>(app.database.receipts().get(creation.position)!!.payload)
            val transfer = result.transactions.single { it.kind == "transfer" }
            val originalTransfer = decodeResponse<TransferCreate>(commands.single { it.path.endsWith("/transactions") && JSONObject(it.body).getString("kind") == "transfer" }.body)
            assertEquals(originalTransfer.id, transfer.id); assertEquals(originalTransfer.fee!!.id, transfer.fee_transaction_id)
            assertEquals(Rate("1", "1"), transfer.rate)
            val fee = result.transactions.single { it.id == transfer.fee_transaction_id }
            assertEquals(transfer.id, fee.parent_transaction_id); assertEquals("-111", fee.entries.single().amount_minor)
            assertEquals(2, mirror.items.filterIsInstance<SnapshotItemTransaction>().count { it.data.kind == "opening" })
            before.put("stage", "verified").put("pid", Process.myPid()).put("cold_recovered", true).put("command_preserved", true).put("pull_covered", true)
            proof.writeText(before.toString())
            val saved = (app.credentialVault.load(scope.origin) as VaultRead.Available).session
            ApiClient(scope.origin).request("/auth/logout", "POST", session = saved); app.credentialVault.clear()
        }
    }
}
