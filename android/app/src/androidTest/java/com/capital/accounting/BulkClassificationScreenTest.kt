package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.ConnectionSettings
import com.capital.accounting.finance.CommandRunner
import com.capital.accounting.finance.FinancialDrafts
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BulkClassificationScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private lateinit var account: Account
    private lateinit var expense: Transaction
    private lateinit var income: Transaction
    private lateinit var category: Category
    private lateinit var tag: Tag
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue(InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("bulk-${UUID.randomUUID()}@example.test", "synthetic bulk password", "Bulk UI", "bearer", "UTC"))))
            account = mutate("accounts", "POST", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Bulk cash", "cash", "EUR", "2026-10-06T08:00:00Z", "UTC", "100000"))).accounts.single()
            category = mutate("categories", "POST", ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), "Bulk category", null))).categories.single()
            tag = mutate("tags", "POST", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "Bulk tag"))).tags.single()
            expense = mutate("transactions", "POST", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T09:00:00.123456Z", "Europe/Nicosia", "Bulk expense", "Recipient", emptyList(), "expense", account.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000"))))).transactions.single()
            income = mutate("transactions", "POST", ApiClient.json.encodeToString(IncomeCreate(UUID.randomUUID().toString(), "2026-10-06T09:01:00Z", "UTC", "Bulk income", "Sender", emptyList(), "income", account.id, "3000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "3000"))))).transactions.single()
            mutate("transactions", "POST", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T09:02:00Z", "UTC", "Bulk split", "", emptyList(), "expense", account.id, "2000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000"), AllocationInput(UUID.randomUUID().toString(), null, "1000")))))
            val refunded = mutate("transactions", "POST", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T09:03:00Z", "UTC", "Bulk refunded", "", emptyList(), "expense", account.id, "100", listOf(AllocationInput(UUID.randomUUID().toString(), null, "100"))))).transactions.single()
            mutate("transactions", "POST", ApiClient.json.encodeToString(RefundCreate(UUID.randomUUID().toString(), "2026-10-06T09:04:00Z", "UTC", "Bulk refund", "", emptyList(), "refund", account.id, "50", refunded.id, refunded.version, null, listOf(RefundAllocationInput(UUID.randomUUID().toString(), refunded.allocations.single().id, "50")))))
            val archived = mutate("accounts", "POST", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Bulk archived", "cash", "EUR", "2026-10-06T08:00:00Z", "UTC", "5000"))).accounts.single()
            mutate("transactions", "POST", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T09:05:00Z", "UTC", "Bulk archived expense", "", emptyList(), "expense", archived.id, "500", listOf(AllocationInput(UUID.randomUUID().toString(), null, "500")))))
            mutate("accounts/${archived.id}", "PUT", ApiClient.json.encodeToString(AccountUpdate(decodeResponse<AccountList>(api.request("/workspaces/${auth.workspace.id}/accounts", session = auth.credential(origin))).items.single { it.id == archived.id }.version, archived.name, archived.type, true)))
            val scope = FinancialDrafts(app).open(origin, auth.profile.id, auth.workspace.id)
            withContext(Dispatchers.Main) {
                scope.put("transaction.note", ApiClient.json.encodeToString("Bulk retained raw draft"))
                scope.put("transaction.amount", ApiClient.json.encodeToString("invalid"))
                scope.put("transaction.editingId", ApiClient.json.encodeToString(expense.id))
                scope.put("transaction.editingVersion", ApiClient.json.encodeToString("1"))
                scope.put("transaction.original", ApiClient.json.encodeToString(expense))
            }
            withTimeout(10000) { while (scope.saving) delay(10) }
            check(!scope.failed)
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> { if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin)) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private suspend fun mutate(path: String, method: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", method, body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
    private fun history() = runBlocking { decodeResponse<TransactionList>(api.request("/workspaces/${auth.workspace.id}/transactions", session = auth.credential(origin))).items }
    private fun accounts() = runBlocking { decodeResponse<AccountList>(api.request("/workspaces/${auth.workspace.id}/accounts", session = auth.credential(origin))).items }
    private fun command() = runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) }
    private fun click(text: String) {
        compose.waitUntil(30000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun waitFor(text: String) { compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun load() {
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета"); waitFor("Для операции: Bulk cash · EUR")
        click("Обновить категории и теги")
        click("Обновить историю"); waitFor("Детали операции Bulk expense")
    }
    private fun select() {
        compose.onNodeWithContentDescription("Классифицировать: Bulk expense").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Классифицировать: Bulk income").performScrollTo().performClick()
        waitFor("Выбрано для классификации: 2")
        click("Категория пакета: Bulk category")
        compose.onNodeWithContentDescription("Массовый тег Bulk tag").performScrollTo().performClick()
    }
    private fun apply() {
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Применить классификацию к выбранным операциям") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click("Применить классификацию к выбранным операциям")
    }
    private fun assertRawDraft() = runBlocking {
        val cached = app.financialDrafts.open(origin, auth.profile.id, auth.workspace.id)
        withTimeout(10000) { while (cached.saving) delay(10) }
        assertFalse(cached.failed)
        val scope = FinancialDrafts(app).open(origin, auth.profile.id, auth.workspace.id)
        assertEquals("Bulk retained raw draft", ApiClient.json.decodeFromString<String>(scope.read("transaction.note")!!))
        assertEquals("invalid", ApiClient.json.decodeFromString<String>(scope.read("transaction.amount")!!))
        assertEquals("1", ApiClient.json.decodeFromString<String>(scope.read("transaction.editingVersion")!!))
        assertEquals(emptyList<com.capital.accounting.finance.BulkSelection>(), ApiClient.json.decodeFromString<List<com.capital.accounting.finance.BulkSelection>>(scope.read("bulk.selection")!!))
    }
    private fun assertFinancials(before: List<Transaction>, balance: List<Account>) {
        val current = history().associateBy { it.id }
        assertEquals(before.size, current.size)
        before.forEach { previous ->
            val now = current.getValue(previous.id)
            assertEquals(previous.entries, now.entries)
            assertEquals(previous.occurred_at, now.occurred_at)
            assertEquals(previous.occurred_timezone, now.occurred_timezone)
            assertEquals(previous.note, now.note)
            assertEquals(previous.payee, now.payee)
            assertEquals(previous.parent_transaction_id, now.parent_transaction_id)
            assertEquals(previous.allocations.map { it.copy(category_id = null) }, now.allocations.map { it.copy(category_id = null) })
        }
        assertEquals(balance, accounts())
    }
    @Test fun batchSurvivesRecreationAndPreservesFinancialsAndRawDraft() {
        load(); select()
        compose.onNodeWithContentDescription("Классифицировать: Bulk split").assertDoesNotExist()
        compose.onNodeWithContentDescription("Классифицировать: Bulk refunded").assertDoesNotExist()
        compose.onNodeWithContentDescription("Классифицировать: Bulk archived expense").assertDoesNotExist()
        click("Выбрать доступные операции на странице")
        waitFor("Выбрано для классификации: 2")
        val before = history(); val balance = accounts()
        compose.activityRule.scenario.recreate()
        waitFor("Выбрано для классификации: 2")
        apply(); waitFor("Классификация выбранных операций сохранена")
        waitFor("Выбрано для классификации: 0")
        assertNull(command())
        history().filter { it.id in listOf(expense.id, income.id) }.forEach {
            assertEquals("2", it.version); assertEquals(category.id, it.allocations.single().category_id); assertEquals(listOf(tag.id), it.tag_ids)
        }
        assertFinancials(before, balance)
        compose.waitForIdle(); assertRawDraft()
        click("Детали операции Bulk expense")
        waitFor("Часть детали 1: 10,00 EUR · Bulk category")
        compose.onNodeWithText("Тег детали: Bulk tag").assertExists()
    }
    @Test fun staleBatchRejectsAtomicallyAndRequiresExplicitVersionUpdate() {
        load(); select()
        runBlocking { mutate("transactions/${income.id}", "PUT", ApiClient.json.encodeToString(IncomeReplace(income.occurred_at, income.occurred_timezone, income.note, income.payee, income.tag_ids, "income", account.id, "3000", income.allocations.map { AllocationInput(it.id, it.category_id, it.amount_minor) }, income.version))) }
        val before = history(); val balance = accounts()
        apply(); waitFor("Состояние: rejected")
        val rejected = command()!!
        assertEquals(listOf("1", "1"), decodeResponse<BulkClassification>(rejected.body).items.map { it.expected_version })
        assertEquals(before, history()); assertEquals(balance, accounts())
        click("Обновить данные для новой команды")
        waitFor("Выбрано для классификации: 2")
        compose.onNodeWithText("Применить классификацию к выбранным операциям").assertIsNotEnabled()
        click("Обновить версии выбранных операций")
        apply(); waitFor("Классификация выбранных операций сохранена"); waitFor("Выбрано для классификации: 0")
        assertEquals("2", history().single { it.id == expense.id }.version)
        assertEquals("3", history().single { it.id == income.id }.version)
        assertFinancials(before, balance); compose.waitForIdle(); assertRawDraft()
    }
    @Test fun archivedClassificationRequiresExplicitClearingBeforeRetry() {
        load(); select()
        runBlocking {
            mutate("categories/${category.id}", "PUT", ApiClient.json.encodeToString(CategoryUpdate(category.version, category.name, true, null)))
            mutate("tags/${tag.id}", "PUT", ApiClient.json.encodeToString(TagUpdate(tag.version, tag.name, true)))
        }
        val before = history(); val balance = accounts()
        apply(); waitFor("Состояние: rejected")
        assertEquals(category.id, decodeResponse<BulkClassification>(command()!!.body).category_id)
        assertEquals(listOf(tag.id), decodeResponse<BulkClassification>(command()!!.body).tag_ids)
        assertEquals(before, history()); assertEquals(balance, accounts())
        click("Обновить данные для новой команды")
        waitFor("Выбранная категория недоступна. Выберите другую или уберите категорию пакета.")
        waitFor("Некоторые выбранные теги недоступны. Уберите теги пакета и выберите актуальные.")
        compose.onNodeWithText("Применить классификацию к выбранным операциям").assertIsNotEnabled()
        click("Пакет без тегов")
        compose.onNodeWithText("Применить классификацию к выбранным операциям").assertIsNotEnabled()
        click("Пакет без категории")
        apply(); waitFor("Классификация выбранных операций сохранена"); waitFor("Выбрано для классификации: 0")
        history().filter { it.id in listOf(expense.id, income.id) }.forEach {
            assertEquals("2", it.version); assertNull(it.allocations.single().category_id); assertTrue(it.tag_ids.isEmpty())
        }
        assertFinancials(before, balance); compose.waitForIdle(); assertRawDraft()
    }
    @Test fun lostResponseReplaysSameDurableBatchWithoutSecondVersionIncrement() {
        load()
        val before = history(); val balance = accounts()
        val body = ApiClient.json.encodeToString(BulkClassification(listOf(BulkItem(expense.id, "1"), BulkItem(income.id, "1")), category.id, listOf(tag.id)))
        val saved = runBlocking { CommandRunner(app.database.commands()).prepare(auth.credential(origin), "transactions/classification", "POST", body) }
        runBlocking { api.request(saved.path, saved.method, saved.body, auth.credential(origin), saved.commandId, saved.generationId) }
        compose.activityRule.scenario.recreate()
        click("Обновить счета")
        waitFor("Сохраненная команда: ${saved.commandId}")
        waitFor("Выбрано для классификации: 2")
        assertEquals(body, command()!!.body)
        click("Повторить сохраненную команду")
        waitFor("Классификация выбранных операций сохранена"); waitFor("Выбрано для классификации: 0")
        assertNull(command())
        assertEquals(listOf("2", "2"), history().filter { it.id in listOf(expense.id, income.id) }.map { it.version })
        assertFinancials(before, balance); compose.waitForIdle(); assertRawDraft()
    }
}
