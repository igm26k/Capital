package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.data.*
import com.capital.accounting.finance.FinancialDrafts
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OfflineMirrorRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val origin get() = args.getString("api_origin")!!
    private val proof get() = File(app.filesDir, "offline-mirror-runtime.json")
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue(args.getString("offline_host") == "true")
            if (args.getString("offline_prepare") != "true") return@runBlocking
            proof.delete(); File(app.filesDir, "offline-host-online.txt").delete(); val api = ApiClient(origin)
            val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-mirror-${UUID.randomUUID()}@example.test", "Synthetic offline mirror password", "Offline mirror", "bearer", "UTC"))))
            val saved = auth.credential(origin); val root = "/workspaces/${auth.workspace.id}"
            suspend fun command(path: String, body: String): MutationResult = decodeResponse(api.request("$root/$path", "POST", body, saved, UUID.randomUUID().toString(), saved.generationId))
            val account = command("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Offline cash", "cash", "KWD", "2026-10-01T12:00:00Z", "UTC", "100000"))).accounts.single()
            val expense = command("transactions", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T12:00:00Z", "UTC", "Offline mirror expense", "", emptyList(), "expense", account.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000"))))).transactions.single()
            val scope = FinancialDrafts(app).open(origin, auth.profile.id, auth.workspace.id)
            withContext(Dispatchers.Main) {
                scope.put("transaction.note", ApiClient.json.encodeToString("Offline retained raw"))
                scope.put("transaction.amount", ApiClient.json.encodeToString("invalid"))
                scope.put("transaction.original", ApiClient.json.encodeToString(expense))
                scope.put("transaction.editingId", ApiClient.json.encodeToString(expense.id))
                scope.put("transaction.editingVersion", ApiClient.json.encodeToString("1"))
            }
            withTimeout(10000) { while (scope.saving) delay(10) }; check(!scope.failed)
            app.database.settings().save(ConnectionSettings(origin = origin)); app.credentialVault.save(saved)
            proof.writeText(JSONObject().put("workspace", saved.workspaceId).put("owner", saved.ownerId).put("session", saved.sessionId).put("expense", expense.id).toString())
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) { compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun write(stage: String) { val value = JSONObject(proof.readText()).put("stage", stage).put("pid", android.os.Process.myPid()); proof.writeText(value.toString()) }
    private fun raw() = runBlocking {
        val p = JSONObject(proof.readText()); val scope = app.financialDrafts.open(origin, p.getString("owner"), p.getString("workspace"))
        assertEquals("1", ApiClient.json.decodeFromString<String>(scope.read("transaction.editingVersion")!!))
        assertEquals("Offline retained raw", ApiClient.json.decodeFromString<String>(scope.read("transaction.note")!!))
        assertEquals("invalid", ApiClient.json.decodeFromString<String>(scope.read("transaction.amount")!!))
        assertNull(app.database.commands().get(origin, p.getString("owner"), p.getString("workspace")))
    }
    @Test fun prepare() {
        waitFor("Изменить счет Offline cash"); compose.onNodeWithText("Итого: 99,000 KWD").assertExists(); raw(); write("prepared")
    }
    @Test fun offline() {
        val previous = JSONObject(proof.readText()); assertNotEquals(previous.getInt("pid"), android.os.Process.myPid())
        waitFor("Нет соединения. Показаны сохраненные данные; они могут быть устаревшими. Доходы, расходы, переводы и возвраты, изменения счетов, категорий и тегов сохраняются на устройстве.")
        compose.onNodeWithText("Изменить счет Offline cash").assertExists(); compose.onNodeWithText("Итого: 99,000 KWD").assertExists()
        compose.onNodeWithText("Добавить счет").assertIsEnabled()
        compose.onNodeWithText("Выйти").assertIsEnabled()
        compose.onNodeWithContentDescription("Раздел Операции").performClick(); waitFor("Детали операции Offline mirror expense")
        raw(); val p = JSONObject(proof.readText()).put("cold_read", true); proof.writeText(p.toString()); write("offline")
    }
    @Test fun online() = runBlocking<Unit> {
        waitFor("Нет соединения. Показаны сохраненные данные; они могут быть устаревшими. Доходы, расходы, переводы и возвраты, изменения счетов, категорий и тегов сохраняются на устройстве.")
        write("reconnect_ready")
        withTimeout(40000) { while (!File(app.filesDir, "offline-host-online.txt").exists()) delay(100) }
        compose.onNodeWithText("Проверить соединение").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Добавить счет") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val saved = (app.credentialVault.load(origin) as VaultRead.Available).session; val api = ApiClient(origin)
        val p = JSONObject(proof.readText())
        val expense = api.transactions(saved).single { it.id == p.getString("expense") }
        api.request("/workspaces/${saved.workspaceId}/transactions/${expense.id}", "PUT", ApiClient.json.encodeToString(ExpenseReplace(expense.occurred_at, expense.occurred_timezone, "Offline mirror expense v2", expense.payee, expense.tag_ids, "expense", expense.entries.single().account_id, "1000", expense.allocations.map { AllocationInput(it.id, it.category_id, it.amount_minor) }, expense.version, null)), saved, UUID.randomUUID().toString(), saved.generationId)
        compose.onNodeWithText("Обновить финансовые данные").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Добавить счет") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Раздел Операции").performClick(); waitFor("Детали операции Offline mirror expense v2")
        raw(); p.put("online_recovered", true).put("raw_preserved", true); proof.writeText(p.toString()); write("online")
    }
    @Test fun denied() = runBlocking<Unit> {
        waitFor("Сессия завершена. Войдите снова.")
        compose.onNodeWithText("Изменить счет Offline cash").assertDoesNotExist()
        compose.onNodeWithText("Нет соединения. Показаны сохраненные данные; они могут быть устаревшими. Доходы, расходы, переводы и возвраты, изменения счетов, категорий и тегов сохраняются на устройстве.").assertDoesNotExist()
        assertTrue(app.credentialVault.load(origin) is VaultRead.Empty)
        raw(); val p = JSONObject(proof.readText()).put("denied_hidden", true).put("no_commands", true); proof.writeText(p.toString()); write("verified")
    }
}
