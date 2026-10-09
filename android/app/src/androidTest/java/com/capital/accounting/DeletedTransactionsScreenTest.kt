package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.ConnectionSettings
import com.capital.accounting.finance.CommandRunner
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
class DeletedTransactionsScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private lateinit var cash: Account
    private lateinit var usd: Account
    private lateinit var income: Transaction
    private lateinit var expense: Transaction
    private lateinit var transfer: Transaction
    private lateinit var refund: Transaction
    private lateinit var adjustment: Transaction
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("deleted-ui-${UUID.randomUUID()}@example.test", "synthetic deleted transaction password", "Deleted UI", "bearer", "UTC"))))
            cash = mutate("accounts", "POST", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Deleted cash", "cash", "KWD", "2026-10-06T08:00:00Z", "UTC", "100000"))).accounts.single()
            usd = mutate("accounts", "POST", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Deleted USD", "cash", "USD", "2026-10-06T08:00:00Z", "UTC", "3000"))).accounts.single()
            income = mutate("transactions", "POST", ApiClient.json.encodeToString(IncomeCreate(UUID.randomUUID().toString(), "2026-10-06T08:10:00.123456Z", "UTC", "Independent income", "Recipient", emptyList(), "income", cash.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000"))))).transactions.single()
            expense = mutate("transactions", "POST", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T08:11:00Z", "UTC", "Parent expense", "Recipient", emptyList(), "expense", cash.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000"))))).transactions.single()
            val fee = FeeInput(UUID.randomUUID().toString(), cash.id, "100", listOf(AllocationInput(UUID.randomUUID().toString(), null, "100")), "Deleted fee", emptyList())
            transfer = mutate("transactions", "POST", ApiClient.json.encodeToString(TransferCreate(UUID.randomUUID().toString(), "2026-10-06T08:12:00Z", "UTC", "Original transfer", "Recipient", emptyList(), "transfer", cash.id, usd.id, "10000", "300", null, fee))).transactions.single { it.kind == "transfer" }
            refund = mutate("transactions", "POST", ApiClient.json.encodeToString(RefundCreate(UUID.randomUUID().toString(), "2026-10-06T08:13:00Z", "UTC", "Original refund", "Recipient", emptyList(), "refund", cash.id, "100", expense.id, expense.version, null, listOf(RefundAllocationInput(UUID.randomUUID().toString(), expense.allocations.single().id, "100"))))).transactions.single { it.kind == "refund" }
            val latest = api.accounts(auth.credential(origin)).single { it.id == cash.id }
            adjustment = mutate("accounts/${cash.id}/adjustments", "POST", ApiClient.json.encodeToString(AdjustmentCreate(UUID.randomUUID().toString(), latest.balance_version, "91000", "Count", "Original adjustment", "UTC"))).transactions.single()
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> { if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin)) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private suspend fun mutate(path: String, method: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", method, body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
    private fun history() = runBlocking { api.transactions(auth.credential(origin)) }
    private fun balances() = runBlocking { api.accounts(auth.credential(origin)).associate { it.id to it.posted_balance_minor } }
    private fun remove(item: Transaction, parents: List<Transaction> = emptyList()) = runBlocking {
        mutate("transactions/${item.id}", "DELETE", ApiClient.json.encodeToString(TransactionDelete(item.version, parents.map { VersionExpectation(it.id, it.version) })))
    }
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun field(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextClearance()
        compose.onNodeWithText(label).performTextInput(value)
    }
    private fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun load() {
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета"); waitFor("Для операции: Deleted cash · KWD")
        click("Обновить историю"); waitFor("Изменить корректировку Original adjustment")
    }
    @Test fun realMissingIncomeRejectsDurableDraftAndAdjustmentRefreshCannotDiscardIt() {
        load()
        click("Изменить операцию Independent income")
        field("Примечание операции", "Kept income draft")
        remove(income)
        click("Сохранить изменения операции")
        waitFor("Изменение отклонено: not_found. Обновите данные и проверьте черновик.")
        val command = runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!! }
        val replacement = decodeResponse<IncomeReplace>(command.body)
        assertEquals("rejected", command.state)
        assertEquals(income.allocations.single().id, replacement.allocations.single().id)
        assertEquals(income.occurred_at, replacement.occurred_at)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Примечание операции").assertTextContains("Kept income draft")
        click("Обновить данные для новой команды")
        waitFor("Операция удалена на сервере. Черновик сохранен; отмените редактирование.")
        compose.onNodeWithText("Сохранить изменения операции").assertIsNotEnabled()
        compose.onNodeWithText("Примечание операции").assertTextContains("Kept income draft")
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        click("Отменить изменение операции")
        click("Изменить корректировку Original adjustment")
        field("Примечание корректировки", "Own adjustment saved")
        click("Сохранить примечание корректировки")
        waitFor("Изменить корректировку Own adjustment saved")
        val own = history().single { it.id == adjustment.id }
        val body = ApiClient.json.encodeToString(AdjustmentReplace("adjustment", own.version, "Recovered own adjustment"))
        val confirmed = runBlocking { CommandRunner(app.database.commands()).prepare(auth.credential(origin), "transactions/${own.id}", "PUT", body) }
        val response = runBlocking { api.request(confirmed.path, confirmed.method, confirmed.body, auth.credential(origin), confirmed.commandId, confirmed.generationId) }
        val written = decodeResponse<MutationResult>(response).transactions.single { it.id == own.id }
        runBlocking {
            assertEquals(1, app.database.commands().confirm(confirmed.commandId, response))
            mutate("transactions/${own.id}", "PUT", ApiClient.json.encodeToString(AdjustmentReplace("adjustment", written.version, "Concurrent adjustment")))
        }
        compose.activityRule.scenario.recreate()
        click("Обновить счета")
        waitFor("Сохраненная команда: ${confirmed.commandId}")
        click("Принять подтвержденный результат")
        waitFor("Версия корректировки на сервере: 4; черновика: 3")
        compose.onNodeWithText("Примечание корректировки").assertTextContains("Recovered own adjustment")
        compose.onNodeWithText("Использовать обновленную версию корректировки").assertIsEnabled()
        assertEquals("3", written.version)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        field("Примечание корректировки", "Kept adjustment draft")
        click("Удалить корректировку Concurrent adjustment")
        remove(history().single { it.id == adjustment.id })
        click("Обновить историю")
        waitFor("Корректировка удалена на сервере. Черновик сохранен; отмените редактирование.")
        compose.onNodeWithText("Примечание корректировки").assertTextContains("Kept adjustment draft")
        compose.onNodeWithText("Сохранить примечание корректировки").assertIsNotEnabled()
        compose.onNodeWithText("Подтвердить удаление корректировки").assertIsNotEnabled()
        click("Отменить удаление корректировки")
        click("Отменить корректировку")
        assertEquals("89000", balances()[cash.id])
        assertEquals("3300", balances()[usd.id])
        assertEquals(6, history().size)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }

    @Test fun realDeletedTransferAndRefundKeepDraftsDespiteOldSuccessMessage() {
        load()
        click("Изменить перевод Original transfer")
        field("Примечание перевода", "Own transfer saved")
        click("Сохранить изменения перевода")
        waitFor("Изменить перевод Own transfer saved")
        field("Примечание перевода", "Kept transfer draft")
        click("Удалить перевод Own transfer saved")
        val snapshot = history().associateBy { it.id }
        val current = snapshot[transfer.id]!!
        remove(current, listOf(snapshot[current.fee_transaction_id]!!))
        click("Обновить историю")
        waitFor("Перевод удален на сервере. Черновик сохранен; отмените редактирование.")
        compose.onNodeWithText("Примечание перевода").assertTextContains("Kept transfer draft")
        compose.onNodeWithText("Сохранить изменения перевода").assertIsNotEnabled()
        compose.onNodeWithText("Подтвердить удаление перевода").assertIsNotEnabled()
        click("Отменить удаление перевода")
        click("Отменить изменение перевода")
        click("Изменить возврат Original refund")
        field("Примечание возврата", "Own refund saved")
        click("Сохранить изменения возврата")
        waitFor("Изменить возврат Own refund saved")
        field("Примечание возврата", "Kept refund draft")
        click("Удалить возврат Own refund saved")
        val latest = history().associateBy { it.id }
        remove(latest[refund.id]!!, listOf(latest[expense.id]!!))
        click("Обновить историю")
        waitFor("Возврат удален на сервере. Черновик сохранен; отмените редактирование.")
        compose.onNodeWithText("Примечание возврата").assertTextContains("Kept refund draft")
        compose.onNodeWithText("Сохранить изменения возврата").assertIsNotEnabled()
        compose.onNodeWithText("Подтвердить удаление возврата").assertIsNotEnabled()
        click("Отменить удаление возврата")
        click("Отменить изменение возврата")
        assertEquals("101000", balances()[cash.id])
        assertEquals("3000", balances()[usd.id])
        assertEquals(5, history().size)
        assertEquals("1000", history().single { it.id == expense.id }.allocations.single().remaining_refundable_minor)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
}
