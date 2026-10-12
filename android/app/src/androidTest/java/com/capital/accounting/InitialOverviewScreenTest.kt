package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.ConnectionSettings
import com.capital.accounting.finance.FinancialDrafts
import kotlinx.coroutines.*
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
class InitialOverviewScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private var original: Transaction? = null
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = -1) val testName = TestName()
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue(InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("overview-${UUID.randomUUID()}@example.test", "synthetic overview password", "Overview UI", "bearer", "Europe/Nicosia"))))
            if (testName.methodName == "initialOverviewLoadsOwnedDataAndPreservesRawDraftVersion") {
                val account = create("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Overview cash", "cash", "KWD", "2026-10-06T08:00:00Z", "UTC", "100000"))).accounts.single()
                val category = create("categories", ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), "Overview category", null))).categories.single()
                val tag = create("tags", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "Overview tag"))).tags.single()
                val expense = create("transactions", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T08:10:00.123456Z", "UTC", "Overview expense", "Overview recipient", listOf(tag.id), "expense", account.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), category.id, "1000"))))).transactions.single()
                original = expense
                api.request("/workspaces/${auth.workspace.id}/transactions/${expense.id}", "PUT", ApiClient.json.encodeToString(ExpenseReplace(expense.occurred_at, expense.occurred_timezone, "Updated overview expense", expense.payee, expense.tag_ids, "expense", account.id, "1000", expense.allocations.map { AllocationInput(it.id, it.category_id, it.amount_minor) }, expense.version, null)), auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id)
            }
            val scope = FinancialDrafts(app).open(origin, auth.profile.id, auth.workspace.id)
            withContext(Dispatchers.Main) {
                scope.put("transaction.note", ApiClient.json.encodeToString("Overview retained raw draft"))
                scope.put("transaction.amount", ApiClient.json.encodeToString("invalid"))
                original?.let {
                    scope.put("transaction.original", ApiClient.json.encodeToString(it))
                    scope.put("transaction.editingId", ApiClient.json.encodeToString(it.id))
                    scope.put("transaction.editingVersion", ApiClient.json.encodeToString(it.version))
                }
            }
            withTimeout(10000) { while (scope.saving) delay(10) }
            check(!scope.failed)
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> { if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin)) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private suspend fun create(path: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", "POST", body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
    private fun waitFor(text: String) { compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(text: String) {
        compose.waitUntil(30000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithText(text)
        if (text == "Добавить") node.performClick() else node.performScrollTo().performClick()
    }
    private fun section(label: String) { compose.onNodeWithContentDescription("Раздел $label").performClick() }
    @Test fun initialOverviewLoadsOwnedDataAndPreservesRawDraftVersion() {
        waitFor("Изменить счет Overview cash")
        compose.onNodeWithContentDescription("Раздел Счета").assertIsSelected()
        compose.onNodeWithText("Итого: 99,000 KWD").assertExists()
        section("Еще")
        waitFor("Часовой пояс профиля: Europe/Nicosia")
        compose.onNodeWithText("Профиль: ${auth.profile.email}").assertExists()
        compose.onNodeWithText("Категория: Overview category").assertExists()
        compose.onNodeWithText("Тег: Overview tag").assertExists()
        section("Операции")
        waitFor("Детали операции Updated overview expense")
        val before = runBlocking { api.transactions(auth.credential(origin)) }
        val balances = runBlocking { api.accounts(auth.credential(origin)) }
        click("Открыть черновик операции")
        waitFor("Версия операции на сервере: 2; версия черновика: 1")
        compose.onNodeWithText("Примечание операции").assertTextContains("Overview retained raw draft")
        compose.onNodeWithText("Сумма операции").assertTextContains("invalid")
        assertEquals(before, runBlocking { api.transactions(auth.credential(origin)) })
        assertEquals(balances, runBlocking { api.accounts(auth.credential(origin)) })
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
    @Test fun emptyWorkspaceRoutesFinancialCreationToFirstAccountWithoutSendingOrClearingRawDraft() {
        waitFor("Создайте первый счет")
        compose.onNodeWithContentDescription("Раздел Счета").assertIsSelected()
        listOf("Создать расход", "Создать доход", "Создать перевод", "Создать возврат", "Создать корректировку").forEach { action ->
            click("Добавить")
            waitFor("Сначала создайте активный счет для операций.")
            click(action)
            waitFor("Редактор счета")
            compose.onNodeWithText("Сумма операции").assertDoesNotExist()
            click("Вернуться к счетам")
            waitFor("Создайте первый счет")
        }
        listOf("Расход", "Доход", "Новый перевод").forEach { action ->
            section("Операции"); click(action); waitFor("Редактор счета"); click("Вернуться к счетам")
        }
        section("Операции"); click("Открыть черновик операции")
        compose.onNodeWithText("Примечание операции").assertTextContains("Overview retained raw draft")
        compose.onNodeWithText("Сумма операции").assertTextContains("invalid")
        assertTrue(runBlocking { api.accounts(auth.credential(origin)) }.isEmpty())
        assertTrue(runBlocking { api.transactions(auth.credential(origin)) }.isEmpty())
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
}
