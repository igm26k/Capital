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
import org.junit.rules.TestName
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransactionDetailsScreenTest {
    @get:Rule(order = -1) val testName = TestName()
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
            if (testName.methodName in setOf("detailIncomeAndRefundActionsProtectPersistedDraftAndRelatedVersions", "detailEditKeepsSelectedVersionAcrossGuardAndRejectsConcurrentUpdate")) {
                val scope = com.capital.accounting.finance.FinancialDrafts(app).open(origin, auth.profile.id, auth.workspace.id)
                scope.put("transaction.note", ApiClient.json.encodeToString("Detail retained raw draft"))
                scope.put("transaction.amount", ApiClient.json.encodeToString("invalid"))
                withTimeout(10000) { while (scope.saving) delay(10) }
                check(!scope.failed)
            }
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> { if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin)) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private suspend fun mutate(path: String, method: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", method, body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
    private fun click(text: String) = compose.operationNode(text).performScrollTo().performClick()
    private fun field(label: String, value: String) {
        compose.operationField(label).performScrollTo().performTextClearance()
        compose.financialNode(label).performTextInput(value)
    }
    private fun waitFor(text: String) = run { compose.prepareFinancialSection(text); compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun load(search: String) {
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета"); waitFor("Для операции: Detail cash · KWD")
        click("Обновить историю"); waitFor("Детали операции Detail expense")
        field("Примечание операции", "Unsaved main draft")
        field("Сумма операции", "2.001")
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Вернуться к истории операций") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.financialNode("Вернуться к истории операций").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Сохранить черновик и продолжить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.financialNode("Сохранить черновик и продолжить").performClick()
        click("Фильтры истории")
        field("Поиск по примечанию и получателю", search)
        click("Применить фильтры истории")
        waitFor("Показано операций: 1")
    }
    private fun history() = runBlocking { api.transactions(auth.credential(origin)) }
    private fun directClick(text: String) {
        compose.waitUntil(30000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithText(text)
        if (text in setOf("Остаться в редакторе", "Сохранить черновик и продолжить", "Отбросить черновик и продолжить")) node.performClick() else node.performScrollTo().performClick()
    }
    private fun directField(label: String, value: String) { compose.onNodeWithText(label).performScrollTo().performTextReplacement(value) }
    private fun openDetails(note: String) { compose.prepareFinancialSection("Детали операции $note"); directClick("Детали операции $note"); waitFor("Примечание детали: $note") }
    private fun editNote(note: String, field: String, changed: String, save: String) {
        openDetails(note); directClick("Редактировать операцию")
        directField(field, changed); directClick(save)
        waitFor("Детали операции $changed")
    }
    private fun deleteFromDetails(note: String, confirm: String) {
        openDetails(note); directClick("Удалить операцию")
        directClick(confirm)
        compose.waitUntil(30000) { compose.onAllNodesWithText("Детали операции $note").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun detailEditsAndConfirmedDeletesReuseEveryEditorAndFeeAggregate() {
        val current = runBlocking { api.accounts(auth.credential(origin)).single { it.id == cash.id } }
        runBlocking { mutate("accounts/${cash.id}/adjustments", "POST", ApiClient.json.encodeToString(AdjustmentCreate(UUID.randomUUID().toString(), current.balance_version, "80000", "Detail action adjustment", "Detail adjustment", "UTC"))) }
        waitFor("Вы вошли: ${auth.profile.email}")
        directClick("Обновить историю")
        editNote("Detail expense", "Примечание операции", "Detail expense edited", "Сохранить изменения операции")
        editNote("Detail transfer", "Примечание перевода", "Detail transfer edited", "Сохранить изменения перевода")
        openDetails("Detail fee"); directClick("Редактировать перевод с этой комиссией")
        compose.onNodeWithText("Примечание перевода").assertTextContains("Detail transfer edited")
        directClick("Вернуться к истории переводов")
        editNote("Detail refund", "Примечание возврата", "Detail refund edited", "Сохранить изменения возврата")
        editNote("Detail adjustment", "Примечание корректировки", "Detail adjustment edited", "Сохранить примечание корректировки")
        assertEquals("80000", runBlocking { api.accounts(auth.credential(origin)).single { it.id == cash.id }.posted_balance_minor })
        assertEquals(7, history().size)
        deleteFromDetails("Detail refund edited", "Подтвердить удаление возврата")
        openDetails("Detail fee"); directClick("Удалить перевод с этой комиссией")
        compose.onNodeWithText("Вместе с переводом будет удалена комиссия: Detail fee; −0,501 KWD").assertExists()
        directClick("Подтвердить удаление перевода")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Детали операции Detail transfer edited").fetchSemanticsNodes().isEmpty() }
        deleteFromDetails("Detail adjustment edited", "Подтвердить удаление корректировки")
        assertEquals("87655", runBlocking { api.accounts(auth.credential(origin)).single { it.id == cash.id }.posted_balance_minor })
        deleteFromDetails("Detail expense edited", "Подтвердить удаление операции")
        assertEquals(2, history().size)
        val accounts = runBlocking { api.accounts(auth.credential(origin)) }
        assertEquals("100000", accounts.single { it.id == cash.id }.posted_balance_minor)
        assertEquals("3000", accounts.single { it.id == usd.id }.posted_balance_minor)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
    @Test fun detailIncomeAndRefundActionsProtectPersistedDraftAndRelatedVersions() {
        runBlocking { mutate("transactions", "POST", ApiClient.json.encodeToString(IncomeCreate(UUID.randomUUID().toString(), "2026-10-06T08:13:00Z", "UTC", "Detail income", "", emptyList(), "income", cash.id, "2000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "2000"))))) }
        waitFor("Вы вошли: ${auth.profile.email}"); directClick("Обновить историю")
        openDetails("Detail income"); directClick("Редактировать операцию")
        waitFor("Сохранить измененный черновик?"); directClick("Остаться в редакторе")
        directClick("Открыть черновик операции")
        compose.onNodeWithText("Примечание операции").assertTextContains("Detail retained raw draft")
        compose.onNodeWithText("Сумма операции").assertTextContains("invalid")
        directClick("Вернуться к истории операций"); directClick("Сохранить черновик и продолжить")
        editNote("Detail income", "Примечание операции", "Detail income edited", "Сохранить изменения операции")
        deleteFromDetails("Detail income edited", "Подтвердить удаление операции")
        openDetails("Detail expense"); directClick("Добавить возврат по этой операции")
        directField("Сумма части возврата 1", "1.000"); directField("Примечание возврата", "Detail action refund")
        directClick("Сохранить возврат"); waitFor("Детали операции Detail action refund")
        openDetails("Detail fee"); directClick("Добавить возврат по этой операции")
        directField("Сумма части возврата 1", "0.100"); directField("Примечание возврата", "Detail action fee refund")
        directClick("Сохранить возврат"); waitFor("Детали операции Detail action fee refund")
        assertEquals(8, history().size)
        assertEquals("79255", runBlocking { api.accounts(auth.credential(origin)).single { it.id == cash.id }.posted_balance_minor })
        deleteFromDetails("Detail action fee refund", "Подтвердить удаление возврата")
        deleteFromDetails("Detail action refund", "Подтвердить удаление возврата")
        assertEquals(6, history().size)
        assertEquals("78155", runBlocking { api.accounts(auth.credential(origin)).single { it.id == cash.id }.posted_balance_minor })
        directClick("Восстановить черновик операции 1")
        compose.onNodeWithText("Примечание операции").assertTextContains("Detail retained raw draft")
        compose.onNodeWithText("Сумма операции").assertTextContains("invalid")
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
    @Test fun detailEditKeepsSelectedVersionAcrossGuardAndRejectsConcurrentUpdate() {
        val income = runBlocking { mutate("transactions", "POST", ApiClient.json.encodeToString(IncomeCreate(UUID.randomUUID().toString(), "2026-10-06T08:13:00Z", "UTC", "Detail income", "", emptyList(), "income", cash.id, "2000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "2000"))))).transactions.single() }
        waitFor("Вы вошли: ${auth.profile.email}"); directClick("Обновить историю")
        openDetails("Detail income"); directClick("Редактировать операцию")
        waitFor("Сохранить измененный черновик?")
        runBlocking { mutate("transactions/${income.id}", "PUT", ApiClient.json.encodeToString(IncomeReplace(income.occurred_at, income.occurred_timezone, "Detail concurrent income", income.payee, income.tag_ids, "income", cash.id, "2000", income.allocations.map { AllocationInput(it.id, it.category_id, it.amount_minor) }, income.version))) }
        directClick("Сохранить черновик и продолжить")
        directClick("Обновить историю")
        waitFor("Версия операции на сервере: 2; версия черновика: 1")
        directField("Примечание операции", "Detail income stale attempt")
        directClick("Сохранить изменения операции")
        waitFor("Обновить данные для новой команды")
        val rejected = runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!! }
        assertEquals("rejected", rejected.state)
        assertEquals(income.version, decodeResponse<IncomeReplace>(rejected.body).expected_version)
        assertEquals("Detail concurrent income", history().single { it.id == income.id }.note)
        compose.onNodeWithText("Примечание операции").assertTextContains("Detail income stale attempt")
        directClick("Обновить данные для новой команды")
        waitFor("Версия операции на сервере: 2; версия черновика: 1")
        directClick("Использовать обновленную версию операции")
        directClick("Сохранить изменения операции")
        waitFor("Детали операции Detail income stale attempt")
        assertEquals("3", history().single { it.id == income.id }.version)
        assertEquals(7, history().size)
        assertEquals("80155", runBlocking { api.accounts(auth.credential(origin)).single { it.id == cash.id }.posted_balance_minor })
        directClick("Восстановить черновик операции 1")
        compose.onNodeWithText("Примечание операции").assertTextContains("Detail retained raw draft")
        compose.onNodeWithText("Сумма операции").assertTextContains("invalid")
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
    @Test fun realFeeTransferNavigationKeepsFilteredHistoryAndUnderlyingDraft() {
        load("Detail fee")
        click("Детали операции Detail fee")
        waitFor("Примечание детали: Detail fee")
        compose.financialNode("Счет детали: Detail cash").assertExists()
        compose.financialNode("Сумма детали: −0,501 KWD").assertExists()
        compose.financialNode("Часть детали 1: 0,501 KWD · Detail group / Detail leaf").assertExists()
        compose.financialNode("Тег детали: $tagName").assertExists()
        click("Открыть исходную операцию: Detail transfer")
        waitFor("Примечание детали: Detail transfer")
        compose.financialNode("Сумма детали: −10,000 KWD").assertExists()
        compose.financialNode("Сумма детали: 3,00 USD").assertExists()
        compose.financialNode("Курс детали: 3/10").assertExists()
        compose.financialNode("Открыть комиссию: Detail fee").assertExists()
        click("Назад к предыдущим деталям")
        waitFor("Примечание детали: Detail fee")
        click("Вернуться к истории")
        click("Восстановить черновик операции 1")
        compose.financialNode("Примечание операции").assertTextContains("Unsaved main draft")
        compose.financialNode("Сумма операции").assertTextContains("2.001")
        compose.financialNode("Поиск по примечанию и получателю").assertTextContains("Detail fee")
        compose.financialNode("Показано операций: 1").assertExists()
        assertEquals(6, history().size)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }

    @Test fun realSplitRefundCycleMissingRecordAndForeignGetRemainReadOnly() {
        load("Detail expense")
        click("Детали операции Detail expense")
        waitFor("Примечание детали: Detail expense")
        compose.financialNode("Сумма детали: −12,345 KWD").assertExists()
        compose.financialNode("Распределено: 12,345 KWD").assertExists()
        compose.financialNode("Время UTC: ${expense.occurred_at}").assertExists()
        compose.financialNode("Часовой пояс детали: Europe/Nicosia").assertExists()
        compose.financialNode("Получатель детали: Detail recipient").assertExists()
        val classifiedIndex = expense.allocations.indexOfFirst { it.amount_minor == "5001" } + 1
        compose.financialNode("Часть детали $classifiedIndex: 5,001 KWD · Detail group / Detail leaf").assertExists()
        compose.financialNode("Доступный возврат части $classifiedIndex: 4,000 KWD").assertExists()
        compose.financialNode("Возвратов детали: 1").assertExists()
        click("Открыть возврат: Detail refund")
        waitFor("Примечание детали: Detail refund")
        compose.financialNode("Сумма детали: 1,001 KWD").assertExists()
        compose.financialNode("Исходная часть возврата: $classifiedIndex").assertExists()
        click("Открыть исходную операцию: Detail expense")
        waitFor("Примечание детали: Detail expense")
        compose.financialNode("Назад к предыдущим деталям").assertDoesNotExist()
        val latest = history().associateBy { it.id }
        runBlocking {
            mutate("transactions/${refund.id}", "DELETE", ApiClient.json.encodeToString(TransactionDelete(latest[refund.id]!!.version, listOf(VersionExpectation(expense.id, latest[expense.id]!!.version)))))
            val parent = api.transaction(auth.credential(origin), expense.id)
            mutate("transactions/${expense.id}", "DELETE", ApiClient.json.encodeToString(TransactionDelete(parent.version, emptyList())))
        }
        click("Обновить детали")
        waitFor("Операция удалена или недоступна.")
        compose.financialNode("Примечание детали: Detail expense").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.financialNode("Операция удалена или недоступна.").assertExists()
        click("Вернуться к истории")
        compose.financialNode("По фильтрам операций нет. Измените или сбросьте фильтры.").assertExists()
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
