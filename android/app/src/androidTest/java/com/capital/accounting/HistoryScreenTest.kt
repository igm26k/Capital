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
    private fun click(text: String) = compose.operationNode(text).performScrollTo().performClick().also {
            if (text == "Отменить изменение операции" || text.startsWith("Изменить операцию ")) {
                if (compose.onAllNodesWithText("Сохранить измененный черновик?").fetchSemanticsNodes().isNotEmpty()) {
                    compose.waitUntil(10000) { compose.onAllNodes(hasText("Отбросить черновик и продолжить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                    compose.financialNode("Отбросить черновик и продолжить").performClick()
                }
            }
        }
    private fun field(label: String, value: String) {
        compose.operationField(label).performScrollTo().performTextClearance()
        compose.financialNode(label).performTextInput(value)
    }
    private fun waitFor(text: String) = run { compose.prepareFinancialSection(text); compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
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

    @Test fun accountShortcutReplacesFiltersAndKeepsUnsentOperationDraft() {
        fun directClick(text: String) {
            compose.waitUntil(30000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val node = compose.onNodeWithText(text)
            if (text == "Сохранить черновик и продолжить") node.performClick()
            else node.performScrollTo().performClick()
        }
        fun section(label: String) { compose.onNodeWithContentDescription("Раздел $label").performClick() }
        fun directField(label: String, value: String) { compose.onNodeWithText(label).performScrollTo().performTextReplacement(value) }
        waitFor("Вы вошли: ${auth.profile.email}")
        directClick("Обновить счета")
        directClick("Обновить историю")
        waitFor("Доход · 0,500 KWD · History salary")
        directClick("Открыть черновик операции")
        directField("Примечание операции", "Unsent account history draft")
        directClick("Вернуться к истории операций")
        directClick("Сохранить черновик и продолжить")
        directClick("Фильтры истории")
        directClick("История: счет History USD")
        directClick("Тип истории: Расход")
        directField("Поиск по примечанию и получателю", "Поиск & +%")
        directField("Операций на странице (1–100)", "1")
        directClick("Применить фильтры истории")
        waitFor("Показано операций: 1")
        compose.onNodeWithText("Расход · −1,00 USD · Поиск & +% USD").assertExists()

        section("Счета")
        directClick("Операции счета History cash")
        waitFor("История счета: History cash")
        waitFor("Показано операций: 4")
        compose.onNodeWithContentDescription("Раздел Операции").assertIsSelected()
        compose.onNodeWithText("Доход · 0,500 KWD · History salary").assertExists()
        compose.onNodeWithText("Расход · −1,000 KWD · Поиск & +% A").assertExists()
        compose.onNodeWithText("Расход · −2,000 KWD · Поиск & +% B").assertExists()
        compose.onNodeWithText("Расход · −1,00 USD · Поиск & +% USD").assertDoesNotExist()
        compose.onNodeWithText("Следующая страница истории").assertDoesNotExist()
        directClick("Фильтры истории")
        compose.onNodeWithText("Счет: History cash").assertExists()
        compose.onNodeWithText("Тип: Все типы").assertExists()
        compose.onNodeWithText("Поиск по примечанию и получателю").assertTextEquals("Поиск по примечанию и получателю", "")
        compose.onNodeWithText("Операций на странице (1–100)").assertTextContains("50")

        section("Счета")
        directClick("Операции счета History USD")
        waitFor("История счета: History USD")
        waitFor("Показано операций: 2")
        compose.onNodeWithText("Расход · −1,00 USD · Поиск & +% USD").assertExists()
        compose.onNodeWithText("Доход · 0,500 KWD · History salary").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        waitFor("История счета: History USD")
        compose.onNodeWithContentDescription("Раздел Операции").assertIsSelected()
        directClick("Сбросить фильтры истории")
        waitFor("Доход · 0,500 KWD · History salary")
        compose.onNodeWithText("Расход · −1,00 USD · Поиск & +% USD").assertExists()
        compose.onNodeWithText("История счета: History USD").assertDoesNotExist()
        directClick("Восстановить черновик операции 1")
        compose.onNodeWithText("Примечание операции").assertTextContains("Unsent account history draft")
        val session = auth.credential(origin)
        assertEquals(6, runBlocking { api.transactions(session) }.size)
        val accounts = runBlocking { api.accounts(session) }
        assertEquals("97500", accounts.single { it.id == cash.id }.posted_balance_minor)
        assertEquals("2900", accounts.single { it.id == other.id }.posted_balance_minor)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }

    @Test fun historySummariesAndActionsFollowFiltersWithoutLosingBulkSelectionOrEditorGraph() {
        fun directClick(text: String) {
            compose.waitUntil(30000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text).performScrollTo().performClick()
        }
        val session = auth.credential(origin)
        runBlocking {
            create("transactions", ApiClient.json.encodeToString(TransferCreate(UUID.randomUUID().toString(), "2026-10-06T08:13:00Z", "UTC", "History transfer", "History peer", emptyList(), "transfer", cash.id, other.id, "1000", "100", null, null)))
            create("transactions", ApiClient.json.encodeToString(RefundCreate(UUID.randomUUID().toString(), "2026-10-06T08:14:00Z", "UTC", "History refund", "History refund recipient", emptyList(), "refund", cash.id, "100", first.id, first.version, null, listOf(RefundAllocationInput(UUID.randomUUID().toString(), first.allocations.single().id, "100")))))
            val current = api.accounts(session).single { it.id == cash.id }
            create("accounts/${cash.id}/adjustments", ApiClient.json.encodeToString(AdjustmentCreate(UUID.randomUUID().toString(), current.balance_version, "96000", "History reconciliation", "History adjustment", "UTC")))
        }
        val before = runBlocking { api.transactions(session) }
        val balances = runBlocking { api.accounts(session) }
        waitFor("Вы вошли: ${auth.profile.email}")
        directClick("Обновить счета")
        directClick("Обновить категории и теги")
        directClick("Обновить историю")
        waitFor("Изменить перевод History transfer")
        compose.onNodeWithText("Изменить возврат History refund").assertExists()
        compose.onNodeWithText("Изменить корректировку History adjustment").assertExists()
        directClick("Фильтры истории")
        directClick("Тип истории: Расход")
        directClick("Применить фильтры истории")
        waitFor("Показано операций: 3")
        compose.onAllNodesWithText("Счета истории: History cash · KWD").assertCountEquals(2)
        compose.onAllNodesWithText("Счета истории: History USD · USD").assertCountEquals(1)
        compose.onAllNodesWithText("Категории истории: History meals").assertCountEquals(3)
        compose.onAllNodesWithText("Контрагент истории: History recipient").assertCountEquals(3)
        listOf("перевод History transfer", "возврат History refund", "корректировку History adjustment").forEach { label ->
            compose.onNodeWithText("Изменить $label").assertDoesNotExist()
            compose.onNodeWithText("Удалить $label").assertDoesNotExist()
        }
        directClick("Выбрать доступные операции на странице")
        waitFor("Выбрано для классификации: 2")
        compose.onNodeWithContentDescription("Массовый тег History trip").performScrollTo().performClick()
        directClick("Тип истории: Перевод")
        directClick("Применить фильтры истории")
        waitFor("Показано операций: 1")
        compose.onNodeWithText("Счета истории: History cash · KWD, History USD · USD").assertExists()
        compose.onNodeWithText("Контрагент истории: History peer").assertExists()
        directClick("Изменить перевод History transfer")
        waitFor("Вернуться к истории переводов")
        compose.onNodeWithText("Примечание перевода").assertTextContains("History transfer")
        compose.onNodeWithContentDescription("Массовый тег History trip").assertDoesNotExist()
        directClick("Вернуться к истории переводов")
        if (compose.onAllNodesWithText("Сохранить измененный черновик?").fetchSemanticsNodes().isNotEmpty()) directClick("Отбросить черновик и продолжить")
        waitFor("Выбрано для классификации: 2")
        compose.onNodeWithContentDescription("Массовый тег History trip").assertIsOn()
        directClick("Сбросить фильтры истории")
        waitFor("Изменить возврат History refund")
        compose.onNodeWithText("Изменить корректировку History adjustment").assertExists()
        assertEquals(before, runBlocking { api.transactions(session) })
        assertEquals(balances, runBlocking { api.accounts(session) })
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
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
        compose.financialNode("Расход · −2,000 KWD · Поиск & +% B").assertExists()
        compose.financialNode("Расход · −1,000 KWD · Поиск & +% A").assertDoesNotExist()
        compose.financialNode("Доход · 0,500 KWD · History salary").assertDoesNotExist()
        compose.financialNode("Создать возврат расхода Поиск & +% A").assertExists()
        click("Следующая страница истории")
        waitFor("Показано операций: 2")
        compose.financialNode("Расход · −1,000 KWD · Поиск & +% A").assertExists()
        compose.financialNode("Следующая страница истории").assertDoesNotExist()
        click("Изменить операцию Поиск & +% A")
        field("Примечание операции", "Поиск & +% Changed")
        click("Сохранить изменения операции")
        waitFor("Показано операций: 1")
        assertEquals("Поиск & +% Changed", runBlocking { api.transactions(auth.credential(origin)).single { it.id == first.id }.note })
        click("Следующая страница истории")
        waitFor("Показано операций: 2")
        compose.financialNode("Расход · −1,000 KWD · Поиск & +% Changed").assertExists()
        compose.activityRule.scenario.recreate()
        waitFor("Показано операций: 2")
        click("Фильтры истории")
        compose.financialNode("Поиск по примечанию и получателю").assertTextContains("Поиск & +%")
        field("История от (UTC)", "2026-10-06T08:10:00.000003Z")
        click("Применить фильтры истории")
        waitFor("Начало периода должно быть раньше окончания.")
        compose.onNodeWithText("История от (UTC)").assertIsFocused()
        field("Поиск по примечанию и получателю", "x".repeat(201))
        field("Операций на странице (1–100)", "101")
        click("Применить фильтры истории")
        waitFor("Поиск должен содержать не более 200 символов.")
        compose.onNodeWithText("Поиск по примечанию и получателю").assertIsFocused()
        compose.financialNode("Показано операций: 2").assertExists()
        field("Поиск по примечанию и получателю", "Поиск & +%")
        field("История от (UTC)", first.occurred_at)
        click("Применить фильтры истории")
        waitFor("Введите целое число от 1 до 100.")
        compose.onNodeWithText("Операций на странице (1–100)").assertIsFocused()
        compose.financialNode("Показано операций: 2").assertExists()
        field("Операций на странице (1–100)", "1")
        compose.financialNode("Показано операций: 2").assertExists()
        field("История от (UTC)", first.occurred_at)
        field("Поиск по примечанию и получателю", "No matching transaction")
        click("Применить фильтры истории")
        waitFor("По фильтрам операций нет. Измените или сбросьте фильтры.")
        click("Сбросить фильтры истории")
        waitFor("Доход · 0,500 KWD · History salary")
        compose.financialNode("Расход · −1,000 KWD · Поиск & +% Changed").assertExists()
        compose.financialNode("Расход · −1,00 USD · Поиск & +% USD").assertExists()
        assertEquals("97500", runBlocking { api.accounts(auth.credential(origin)).single { it.id == cash.id }.posted_balance_minor })
    }
}
