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
class TransactionDetailsScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private lateinit var cash: Account
    private lateinit var usd: Account
    private lateinit var expense: Transaction
    private lateinit var transfer: Transaction
    private lateinit var fee: Transaction
    private lateinit var refund: Transaction
    private val tagName = "Detail <svg onload=alert(1)>"
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("details-ui-${UUID.randomUUID()}@example.test", "synthetic details password", "Details UI", "bearer", "UTC"))))
            cash = mutate("accounts", "POST", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Detail cash", "cash", "KWD", "2026-10-06T08:00:00Z", "UTC", "100000"))).accounts.single()
            usd = mutate("accounts", "POST", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Detail USD", "cash", "USD", "2026-10-06T08:00:00Z", "UTC", "3000"))).accounts.single()
            val group = mutate("categories", "POST", ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), "Detail group", null))).categories.single()
            val leaf = mutate("categories", "POST", ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), "Detail leaf", group.id))).categories.single()
            val tag = mutate("tags", "POST", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), tagName))).tags.single()
            expense = mutate("transactions", "POST", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T08:10:00.123456Z", "Europe/Nicosia", "Detail expense", "Detail recipient", listOf(tag.id), "expense", cash.id, "12345", listOf(AllocationInput(UUID.randomUUID().toString(), leaf.id, "5001"), AllocationInput(UUID.randomUUID().toString(), null, "7344"))))).transactions.single()
            val feeInput = FeeInput(UUID.randomUUID().toString(), cash.id, "501", listOf(AllocationInput(UUID.randomUUID().toString(), leaf.id, "501")), "Detail fee", listOf(tag.id))
            val result = mutate("transactions", "POST", ApiClient.json.encodeToString(TransferCreate(UUID.randomUUID().toString(), "2026-10-06T08:11:00Z", "UTC", "Detail transfer", "Transfer recipient", emptyList(), "transfer", cash.id, usd.id, "10000", "300", null, feeInput)))
            transfer = result.transactions.single { it.kind == "transfer" }
            fee = result.transactions.single { it.id == feeInput.id }
            refund = mutate("transactions", "POST", ApiClient.json.encodeToString(RefundCreate(UUID.randomUUID().toString(), "2026-10-06T08:12:00Z", "UTC", "Detail refund", "Refund recipient", listOf(tag.id), "refund", cash.id, "1001", expense.id, expense.version, null, listOf(RefundAllocationInput(UUID.randomUUID().toString(), expense.allocations.single { it.amount_minor == "5001" }.id, "1001"))))).transactions.single { it.kind == "refund" }
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> { if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin)) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private suspend fun mutate(path: String, method: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", method, body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun field(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextClearance()
        compose.onNodeWithText(label).performTextInput(value)
    }
    private fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun load(search: String) {
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета"); waitFor("Для операции: Detail cash · KWD")
        click("Обновить историю"); waitFor("Детали операции Detail expense")
        field("Примечание операции", "Unsaved main draft")
        field("Сумма операции", "2.001")
        click("Фильтры истории")
        field("Поиск по примечанию и получателю", search)
        click("Применить фильтры истории")
        waitFor("Показано операций: 1")
    }
    private fun history() = runBlocking { api.transactions(auth.credential(origin)) }
    @Test fun realFeeTransferNavigationKeepsFilteredHistoryAndUnderlyingDraft() {
        load("Detail fee")
        click("Детали операции Detail fee")
        waitFor("Примечание детали: Detail fee")
        compose.onNodeWithText("Счет детали: Detail cash").assertExists()
        compose.onNodeWithText("Сумма детали: −0,501 KWD").assertExists()
        compose.onNodeWithText("Часть детали 1: 0,501 KWD · Detail group / Detail leaf").assertExists()
        compose.onNodeWithText("Тег детали: $tagName").assertExists()
        click("Открыть исходную операцию: Detail transfer")
        waitFor("Примечание детали: Detail transfer")
        compose.onNodeWithText("Сумма детали: −10,000 KWD").assertExists()
        compose.onNodeWithText("Сумма детали: 3,00 USD").assertExists()
        compose.onNodeWithText("Курс детали: 3/10").assertExists()
        compose.onNodeWithText("Открыть комиссию: Detail fee").assertExists()
        click("Назад к предыдущим деталям")
        waitFor("Примечание детали: Detail fee")
        click("Вернуться к истории")
        compose.onNodeWithText("Примечание операции").assertTextContains("Unsaved main draft")
        compose.onNodeWithText("Сумма операции").assertTextContains("2.001")
        compose.onNodeWithText("Поиск по примечанию и получателю").assertTextContains("Detail fee")
        compose.onNodeWithText("Показано операций: 1").assertExists()
        assertEquals(6, history().size)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }

    @Test fun realSplitRefundCycleMissingRecordAndForeignGetRemainReadOnly() {
        load("Detail expense")
        click("Детали операции Detail expense")
        waitFor("Примечание детали: Detail expense")
        compose.onNodeWithText("Сумма детали: −12,345 KWD").assertExists()
        compose.onNodeWithText("Распределено: 12,345 KWD").assertExists()
        compose.onNodeWithText("Время UTC: ${expense.occurred_at}").assertExists()
        compose.onNodeWithText("Часовой пояс детали: Europe/Nicosia").assertExists()
        compose.onNodeWithText("Получатель детали: Detail recipient").assertExists()
        val classifiedIndex = expense.allocations.indexOfFirst { it.amount_minor == "5001" } + 1
        compose.onNodeWithText("Часть детали $classifiedIndex: 5,001 KWD · Detail group / Detail leaf").assertExists()
        compose.onNodeWithText("Доступный возврат части $classifiedIndex: 4,000 KWD").assertExists()
        compose.onNodeWithText("Возвратов детали: 1").assertExists()
        click("Открыть возврат: Detail refund")
        waitFor("Примечание детали: Detail refund")
        compose.onNodeWithText("Сумма детали: 1,001 KWD").assertExists()
        compose.onNodeWithText("Исходная часть возврата: $classifiedIndex").assertExists()
        click("Открыть исходную операцию: Detail expense")
        waitFor("Примечание детали: Detail expense")
        compose.onNodeWithText("Назад к предыдущим деталям").assertDoesNotExist()
        val latest = history().associateBy { it.id }
        runBlocking {
            mutate("transactions/${refund.id}", "DELETE", ApiClient.json.encodeToString(TransactionDelete(latest[refund.id]!!.version, listOf(VersionExpectation(expense.id, latest[expense.id]!!.version)))))
            val parent = api.transaction(auth.credential(origin), expense.id)
            mutate("transactions/${expense.id}", "DELETE", ApiClient.json.encodeToString(TransactionDelete(parent.version, emptyList())))
        }
        click("Обновить детали")
        waitFor("Операция удалена или недоступна.")
        compose.onNodeWithText("Примечание детали: Detail expense").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Операция удалена или недоступна.").assertExists()
        click("Вернуться к истории")
        compose.onNodeWithText("По фильтрам операций нет. Измените или сбросьте фильтры.").assertExists()
        val foreign = decodeResponse<BearerAuth>(runBlocking { api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("detail-foreign-${UUID.randomUUID()}@example.test", "synthetic foreign details password", "Foreign details", "bearer", "UTC"))) })
        try {
            try { runBlocking { api.transaction(foreign.credential(origin), transfer.id) }; fail("Foreign transaction must be hidden") }
            catch (failure: ApiFailure) { assertEquals(404, failure.status) }
        } finally { runBlocking { api.request("/auth/logout", "POST", session = foreign.credential(origin)) } }
        assertEquals(4, history().size)
        val balances = runBlocking { api.accounts(auth.credential(origin)).associate { it.id to it.posted_balance_minor } }
        assertEquals("89499", balances[cash.id]); assertEquals("3300", balances[usd.id])
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
}
