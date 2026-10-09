package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.ConnectionSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private lateinit var cash: Account
    private lateinit var other: Account
    private lateinit var category: Category
    private lateinit var tag: Tag
    private lateinit var first: Transaction
    private lateinit var second: Transaction
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("history-ui-${UUID.randomUUID()}@example.test", "synthetic history password", "History UI", "bearer", "UTC"))))
            cash = create("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "History cash", "cash", "KWD", "2026-10-06T08:00:00Z", "UTC", "100000"))).accounts.single()
            other = create("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "History USD", "cash", "USD", "2026-10-06T08:00:00Z", "UTC", "3000"))).accounts.single()
            category = create("categories", ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), "History meals", null))).categories.single()
            tag = create("tags", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "History trip"))).tags.single()
            suspend fun expense(account: Account, note: String, instant: String, amount: String) = create("transactions", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), instant, "UTC", note, "History recipient", listOf(tag.id), "expense", account.id, amount, listOf(AllocationInput(UUID.randomUUID().toString(), category.id, amount))))).transactions.single()
            first = expense(cash, "Поиск & +% A", "2026-10-06T08:10:00.000001Z", "1000")
            second = expense(cash, "Поиск & +% B", "2026-10-06T08:10:00.000002Z", "2000")
            expense(other, "Поиск & +% USD", "2026-10-06T08:10:00.000002Z", "100")
            create("transactions", ApiClient.json.encodeToString(IncomeCreate(UUID.randomUUID().toString(), "2026-10-06T08:12:00Z", "UTC", "History salary", "", emptyList(), "income", cash.id, "500", listOf(AllocationInput(UUID.randomUUID().toString(), null, "500")))))
            val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> { if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin)) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private suspend fun create(path: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", "POST", body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun field(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextClearance()
        compose.onNodeWithText(label).performTextInput(value)
    }
    private fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun filter() = TransactionFilter(cash.id, category.id, tag.id, first.occurred_at, "2026-10-06T08:10:00.000003Z", "expense", "posted", "Поиск & +%")
    @Test fun realCombinedFilterPagesExactBoundsAndForeignResourceRejection() = runBlocking<Unit> {
        val page = api.transactionPage(auth.credential(origin), filter(), 1)
        assertEquals(listOf(second.id), page.items.map { it.id })
        val next = api.transactionPage(auth.credential(origin), filter(), 1, requireNotNull(page.next_cursor))
        assertEquals(listOf(first.id), next.items.map { it.id })
        assertNull(next.next_cursor)
        assertEquals(listOf(second.id, first.id), api.transactions(auth.credential(origin), 1, filter()).map { it.id })
        val exclusive = filter().copy(to = second.occurred_at)
        assertEquals(listOf(first.id), api.transactions(auth.credential(origin), 1, exclusive).map { it.id })
        assertEquals(0, api.transactions(auth.credential(origin), 1, filter().copy(status = "pending")).size)
        for (kind in TransactionFilter.kinds.keys) {
            val items = api.transactions(auth.credential(origin), 1, TransactionFilter(kind = kind))
            assertEquals(mapOf("opening" to 2, "expense" to 3, "income" to 1)[kind] ?: 0, items.size)
            assertTrue(items.all { it.kind == kind })
        }
        val foreign = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("history-foreign-${UUID.randomUUID()}@example.test", "synthetic foreign history password", "Foreign history", "bearer", "UTC"))))
        try {
            for (resource in listOf(TransactionFilter(accountId = cash.id), TransactionFilter(categoryId = category.id), TransactionFilter(tagId = tag.id))) {
                try { api.transactionPage(foreign.credential(origin), resource, 1); fail("Foreign filter resource must be hidden") }
                catch (failure: ApiFailure) { assertEquals(404, failure.status) }
            }
        } finally { api.request("/auth/logout", "POST", session = foreign.credential(origin)) }
    }

    @Test fun realUiFiltersPagingRecreationAndResetKeepFullEditorGraph() {
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета"); waitFor("Для операции: History cash · KWD")
        click("Обновить категории и теги"); waitFor("Тег: History trip")
        click("Обновить историю"); waitFor("Доход · 0,500 KWD · History salary")
        click("Фильтры истории")
        click("История: счет History cash")
        click("История: категория History meals")
        click("История: тег History trip")
        click("Тип истории: Расход")
        click("Статус истории: Проведена")
        field("Поиск по примечанию и получателю", "Поиск & +%")
        field("История от (UTC)", first.occurred_at)
        field("История до (UTC)", "2026-10-06T08:10:00.000003Z")
        field("Операций на странице (1–100)", "1")
        click("Применить фильтры истории")
        waitFor("Показано операций: 1")
        compose.onNodeWithText("Расход · −2,000 KWD · Поиск & +% B").assertExists()
        compose.onNodeWithText("Расход · −1,000 KWD · Поиск & +% A").assertDoesNotExist()
        compose.onNodeWithText("Доход · 0,500 KWD · History salary").assertDoesNotExist()
        compose.onNodeWithText("Создать возврат расхода Поиск & +% A").assertExists()
        click("Следующая страница истории")
        waitFor("Показано операций: 2")
        compose.onNodeWithText("Расход · −1,000 KWD · Поиск & +% A").assertExists()
        compose.onNodeWithText("Следующая страница истории").assertDoesNotExist()
        click("Изменить операцию Поиск & +% A")
        field("Примечание операции", "Поиск & +% Changed")
        click("Сохранить изменения операции")
        waitFor("Показано операций: 1")
        assertEquals("Поиск & +% Changed", runBlocking { api.transactions(auth.credential(origin)).single { it.id == first.id }.note })
        click("Следующая страница истории")
        waitFor("Показано операций: 2")
        compose.onNodeWithText("Расход · −1,000 KWD · Поиск & +% Changed").assertExists()
        compose.activityRule.scenario.recreate()
        waitFor("Показано операций: 2")
        click("Фильтры истории")
        compose.onNodeWithText("Поиск по примечанию и получателю").assertTextContains("Поиск & +%")
        field("История от (UTC)", "2026-10-06T08:10:00.000003Z")
        click("Применить фильтры истории")
        waitFor("Проверьте UTC-период, длину поиска и размер страницы. Начало должно быть раньше окончания.")
        compose.onNodeWithText("Показано операций: 2").assertExists()
        field("История от (UTC)", first.occurred_at)
        field("Поиск по примечанию и получателю", "No matching transaction")
        click("Применить фильтры истории")
        waitFor("По фильтрам операций нет. Измените или сбросьте фильтры.")
        click("Сбросить фильтры истории")
        waitFor("Доход · 0,500 KWD · History salary")
        compose.onNodeWithText("Расход · −1,000 KWD · Поиск & +% Changed").assertExists()
        compose.onNodeWithText("Расход · −1,00 USD · Поиск & +% USD").assertExists()
        assertEquals("97500", runBlocking { api.accounts(auth.credential(origin)).single { it.id == cash.id }.posted_balance_minor })
    }
}
