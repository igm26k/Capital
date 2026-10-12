package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.ConnectionSettings
import com.capital.accounting.data.MirrorScope
import com.capital.accounting.data.SnapshotStore
import com.capital.accounting.data.finance
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
class InitialOverviewRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val origin get() = args.getString("api_origin")!!
    private val proof get() = File(app.filesDir, "overview-outage-proof.json")
    private val control get() = File(app.filesDir, "overview-host-state.txt")
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private lateinit var expense: Transaction
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue(args.getString("overview_host") == "true" && args.getString("api_origin") != null)
            proof.delete(); control.delete()
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("overview-outage-${UUID.randomUUID()}@example.test", "synthetic overview outage password", "Overview runtime", "bearer", "UTC"))))
            val account = create("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Overview runtime cash", "cash", "KWD", "2026-10-06T08:00:00Z", "UTC", "100000"))).accounts.single()
            expense = create("transactions", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T08:10:00.123456Z", "UTC", "Overview runtime expense", "Runtime recipient", emptyList(), "expense", account.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000"))))).transactions.single()
            val scope = FinancialDrafts(app).open(origin, auth.profile.id, auth.workspace.id)
            withContext(Dispatchers.Main) {
                scope.put("transaction.note", ApiClient.json.encodeToString("Overview runtime retained raw"))
                scope.put("transaction.amount", ApiClient.json.encodeToString("invalid"))
                scope.put("transaction.original", ApiClient.json.encodeToString(expense))
                scope.put("transaction.editingId", ApiClient.json.encodeToString(expense.id))
                scope.put("transaction.editingVersion", ApiClient.json.encodeToString(expense.version))
            }
            withTimeout(10000) { while (scope.saving) delay(10) }
            check(!scope.failed)
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> { if (::auth.isInitialized) runCatching { api.request("/auth/logout", "POST", session = auth.credential(origin)) }; Unit }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private suspend fun create(path: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", "POST", body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
    private fun publish(stage: String) {
        val value = JSONObject().put("stage", stage).put("workspace_id", auth.workspace.id).put("expense_id", expense.id)
        if (stage == "verified") listOf("cache_retained", "raw_version_retained", "readonly_retry", "no_commands", "no_extra_transactions").forEach { value.put(it, true) }
        proof.writeText(value.toString())
    }
    private fun waitHost(stage: String) { compose.waitUntil(120000) { control.exists() && control.readText().trim() == stage } }
    private fun waitFor(text: String) { compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(text: String) {
        compose.waitUntil(30000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun roomMirror(version: String) = runBlocking {
        val mirror = SnapshotStore(app.database).read(MirrorScope(origin, auth.profile.id, auth.workspace.id))!!
        assertEquals("99000", mirror.finance().accounts.single().posted_balance_minor)
        assertEquals(version, mirror.finance().transactions.single { it.id == expense.id }.version)
    }
    private fun rawDraft() = runBlocking {
        val scope = app.financialDrafts.open(origin, auth.profile.id, auth.workspace.id)
        assertEquals("1", ApiClient.json.decodeFromString<String>(scope.read("transaction.editingVersion")!!))
        assertEquals("Overview runtime retained raw", ApiClient.json.decodeFromString<String>(scope.read("transaction.note")!!))
        assertEquals("invalid", ApiClient.json.decodeFromString<String>(scope.read("transaction.amount")!!))
        assertNull(app.database.commands().get(origin, auth.profile.id, auth.workspace.id))
    }
    @Test fun readonlyOverviewOutageRetainsCacheAndExplicitRetryDoesNotAdoptDraftVersion() {
        waitFor("Изменить счет Overview runtime cash")
        compose.onNodeWithText("Итого: 99,000 KWD").assertExists()
        roomMirror("1"); publish("ready"); waitHost("offline")
        click("Обновить финансовые данные")
        waitFor("Не удалось загрузить финансовые данные. Повторите загрузку. Ранее загруженные данные могут быть устаревшими.")
        compose.onNodeWithText("Изменить счет Overview runtime cash").assertExists()
        compose.onNodeWithText("Итого: 99,000 KWD").assertExists()
        compose.onNodeWithText("Создайте первый счет").assertDoesNotExist()
        rawDraft(); roomMirror("1"); publish("offline_verified"); waitHost("online")
        val session = auth.credential(origin)
        runBlocking { api.request("/workspaces/${auth.workspace.id}/transactions/${expense.id}", "PUT", ApiClient.json.encodeToString(ExpenseReplace(expense.occurred_at, expense.occurred_timezone, "Overview runtime expense v2", expense.payee, expense.tag_ids, "expense", expense.entries.single().account_id, "1000", expense.allocations.map { AllocationInput(it.id, it.category_id, it.amount_minor) }, expense.version, null)), session, UUID.randomUUID().toString(), auth.workspace.sync_generation_id) }
        val expected = runBlocking { api.transactions(session) }
        val balances = runBlocking { api.accounts(session) }
        click("Повторить загрузку финансовых данных")
        compose.onNodeWithContentDescription("Раздел Операции").performClick()
        waitFor("Детали операции Overview runtime expense v2")
        compose.onNodeWithText("Повторить загрузку финансовых данных").assertDoesNotExist()
        rawDraft()
        click("Открыть черновик операции")
        waitFor("Версия операции на сервере: 2; версия черновика: 1")
        compose.onNodeWithText("Примечание операции").assertTextContains("Overview runtime retained raw")
        assertEquals(expected, runBlocking { api.transactions(session) })
        assertEquals(balances, runBlocking { api.accounts(session) })
        assertEquals(2, expected.size)
        assertEquals("2", expected.single { it.id == expense.id }.version)
        roomMirror("2"); publish("verified")
    }
}
