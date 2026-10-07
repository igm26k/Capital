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
class RefundsScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private lateinit var source: Account
    private lateinit var alternate: Account
    private lateinit var usd: Account
    private lateinit var category: Category
    private lateinit var tag: Tag
    private lateinit var expense: Transaction
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("refunds-ui-${UUID.randomUUID()}@example.test", "synthetic refund password", "Refund UI", "bearer", "UTC"))))
            suspend fun create(path: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", "POST", body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
            suspend fun account(name: String, currency: String, minor: String) = create("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), name, "cash", currency, "2026-10-06T08:00:00Z", "UTC", minor))).accounts.single()
            source = account("Refund source", "KWD", "100000")
            alternate = account("Refund alternate", "KWD", "20000")
            usd = account("Refund USD", "USD", "3000")
            category = create("categories", ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), "Refund category", null))).categories.single()
            tag = create("tags", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "Refund tag"))).tags.single()
            expense = create("transactions", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T08:10:00Z", "UTC", "Refund expense", "Expense recipient", emptyList(), "expense", source.id, "12345", listOf(AllocationInput(UUID.randomUUID().toString(), category.id, "5001"), AllocationInput(UUID.randomUUID().toString(), null, "7344"))))).transactions.single()
            val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> {
            if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin))
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun field(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextClearance()
        compose.onNodeWithText(label).performTextInput(value)
    }
    private fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun history() = runBlocking { api.transactions(auth.credential(origin)) }
    private fun accounts() = runBlocking { api.accounts(auth.credential(origin)).associateBy { it.id } }
    private fun load() {
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета")
        waitFor("Списать со счета: Refund source · KWD")
        click("Обновить категории и теги")
        waitFor("Тег: Refund tag")
        click("Обновить историю")
        waitFor("Создать возврат расхода Refund expense")
    }

    @Test fun realSplitPartialRefundQuotaAndConcurrentParentVersion() {
        load()
        val first = expense.allocations.single { it.amount_minor == "5001" }
        val second = expense.allocations.single { it.amount_minor == "7344" }
        val firstIndex = expense.allocations.indexOfFirst { it.id == first.id } + 1
        val secondIndex = expense.allocations.indexOfFirst { it.id == second.id } + 1
        click("Создать возврат расхода Refund expense")
        compose.onNodeWithText("Счет возврата: Refund USD · USD").assertDoesNotExist()
        click("Счет возврата: Refund alternate · KWD")
        field("Сумма части возврата $firstIndex", "1.001")
        field("Сумма части возврата $secondIndex", "2.002")
        field("Примечание возврата", "Partial refund")
        field("Дата возврата (YYYY-MM-DDTHH:MM:SS)", "2026-10-06T11:20:00")
        field("Часовой пояс возврата", "Europe/Nicosia")
        compose.onNodeWithContentDescription("Тег возврата Refund tag").performScrollTo().performClick()
        click("Сохранить возврат")
        waitFor("Возврат · 3,003 KWD · Partial refund")
        val partial = history().single { it.note == "Partial refund" }
        assertEquals(expense.id, partial.parent_transaction_id)
        assertEquals(alternate.id, partial.entries.single().account_id)
        assertEquals("3003", partial.entries.single().amount_minor)
        assertEquals(listOf(tag.id), partial.tag_ids)
        assertEquals("2026-10-06T08:20:00.000000Z", partial.occurred_at)
        assertEquals(category.id, partial.allocations.single { it.original_allocation_id == first.id }.category_id)
        assertNull(partial.allocations.single { it.original_allocation_id == second.id }.category_id)
        assertEquals("87655", accounts()[source.id]!!.posted_balance_minor)
        assertEquals("23003", accounts()[alternate.id]!!.posted_balance_minor)
        waitFor("Доступно для части возврата $firstIndex: 4,000 KWD")
        waitFor("Доступно для части возврата $secondIndex: 5,342 KWD")
        click("Создать возврат расхода Refund expense")
        field("Сумма части возврата $firstIndex", "4.001")
        click("Сохранить возврат")
        waitFor("Проверьте положительные суммы частей, доступный возврат, счет, дату и длину текста.")
        assertEquals(1, history().count { it.kind == "refund" })
        field("Сумма части возврата $firstIndex", "0")
        click("Сохранить возврат")
        assertEquals(1, history().count { it.kind == "refund" })
        field("Сумма части возврата $firstIndex", "1.000")
        field("Примечание возврата", "Local concurrent refund")
        click("Счет возврата: Refund alternate · KWD")
        val parent = history().single { it.id == expense.id }
        val remote = RefundCreate(UUID.randomUUID().toString(), "2026-10-06T08:21:00Z", "UTC", "Remote refund", "", emptyList(), "refund", source.id, "1000", expense.id, parent.version, null, listOf(RefundAllocationInput(UUID.randomUUID().toString(), first.id, "1000")))
        runBlocking { api.request("/workspaces/${auth.workspace.id}/transactions", "POST", ApiClient.json.encodeToString(remote), auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id) }
        click("Сохранить возврат")
        waitFor("Изменение отклонено: version_conflict. Обновите данные и проверьте черновик.")
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
        val rejected = runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!! }
        val draft = decodeResponse<RefundCreate>(rejected.body)
        assertEquals("rejected", rejected.state)
        assertEquals("2", draft.expected_parent_version)
        assertNull(draft.expected_transfer_version)
        assertEquals(first.id, draft.allocations.single().original_allocation_id)
        compose.onNodeWithText("Выйти").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Примечание возврата").assertTextContains("Local concurrent refund")
        compose.onNodeWithText("Сумма части возврата $firstIndex").assertTextContains("1,000")
        click("Обновить данные для новой команды")
        waitFor("Данные обновлены. Проверьте поля перед новой командой.")
        assertEquals(2, history().count { it.kind == "refund" })
        click("Использовать обновленные версии расхода и перевода")
        click("Сохранить возврат")
        waitFor("Возврат · 1,000 KWD · Local concurrent refund")
        assertEquals(3, history().count { it.kind == "refund" })
        assertEquals("88655", accounts()[source.id]!!.posted_balance_minor)
        assertEquals("24003", accounts()[alternate.id]!!.posted_balance_minor)
        assertEquals("2000", history().single { it.id == expense.id }.allocations.single { it.id == first.id }.remaining_refundable_minor)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("24,003 KWD").assertExists()
        val latest = accounts()[source.id]!!
        runBlocking { api.request("/workspaces/${auth.workspace.id}/accounts/${source.id}", "PUT", ApiClient.json.encodeToString(AccountUpdate(latest.version, latest.name, latest.type, true)), auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id) }
        click("Обновить счета")
        waitFor("Refund source · В архиве")
        click("Создать возврат расхода Refund expense")
        click("Счет возврата: Refund alternate · KWD")
        compose.onNodeWithText("Сохранить возврат").assertIsNotEnabled()
    }

    @Test fun realFeeRefundAndLostLocalReceiptRepeatOriginalBodyAndKey() {
        val fee = FeeInput(UUID.randomUUID().toString(), source.id, "501", listOf(AllocationInput(UUID.randomUUID().toString(), category.id, "501")), "Refund fee", emptyList())
        val transfer = TransferCreate(UUID.randomUUID().toString(), "2026-10-06T08:15:00Z", "UTC", "Refund transfer", "", emptyList(), "transfer", source.id, usd.id, "10000", "300", null, fee)
        runBlocking { api.request("/workspaces/${auth.workspace.id}/transactions", "POST", ApiClient.json.encodeToString(transfer), auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id) }
        load()
        click("Создать возврат комиссии Refund fee")
        click("Счет возврата: Refund alternate · KWD")
        field("Сумма части возврата 1", "0.101")
        field("Примечание возврата", "Native fee refund")
        field("Дата возврата (YYYY-MM-DDTHH:MM:SS)", "2026-10-06T11:20:00")
        field("Часовой пояс возврата", "Europe/Nicosia")
        click("Сохранить возврат")
        waitFor("Возврат · 0,101 KWD · Native fee refund")
        assertEquals("20101", accounts()[alternate.id]!!.posted_balance_minor)
        assertEquals("77154", accounts()[source.id]!!.posted_balance_minor)
        val after = history().associateBy { it.id }
        assertEquals("2", after[fee.id]!!.version)
        assertEquals("2", after[transfer.id]!!.version)
        assertEquals("400", after[fee.id]!!.allocations.single().remaining_refundable_minor)
        assertEquals(category.id, after.values.single { it.note == "Native fee refund" }.allocations.single().category_id)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
        val replay = RefundCreate(UUID.randomUUID().toString(), "2026-10-06T08:25:30.654321Z", "Europe/Nicosia", "Replay fee refund", "Replay payee", listOf(tag.id), "refund", source.id, "100", fee.id, after[fee.id]!!.version, after[transfer.id]!!.version, listOf(RefundAllocationInput(UUID.randomUUID().toString(), fee.allocations.single().id, "100")))
        val body = ApiClient.json.encodeToString(replay)
        val command = runBlocking { CommandRunner(app.database.commands()).prepare(auth.credential(origin), "transactions", "POST", body) }
        runBlocking { api.request(command.path, command.method, command.body, auth.credential(origin), command.commandId, command.generationId) }
        val appliedCount = history().size
        compose.activityRule.scenario.recreate()
        click("Обновить счета")
        waitFor("Сохраненная команда: ${command.commandId}")
        click("Обновить историю")
        waitFor("Исходный расход: Refund fee")
        waitFor("Версия расхода на сервере: 3; черновика: 2")
        compose.onNodeWithText("Сумма части возврата 1").assertTextContains("0,100")
        compose.onNodeWithText("Примечание возврата").assertTextContains("Replay fee refund")
        compose.onNodeWithText("Дата возврата (YYYY-MM-DDTHH:MM:SS)").assertTextContains("2026-10-06T11:25:30")
        compose.onNodeWithText("Использовать обновленные версии расхода и перевода").assertIsNotEnabled()
        compose.onNodeWithText("Выйти").assertIsNotEnabled()
        assertEquals(body, runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!!.body })
        click("Повторить сохраненную команду")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Сохраненная команда: ${command.commandId}").fetchSemanticsNodes().isEmpty() }
        val confirmed = history().associateBy { it.id }
        assertEquals(appliedCount, confirmed.size)
        assertEquals(2, confirmed.values.count { it.kind == "refund" })
        assertEquals("77254", accounts()[source.id]!!.posted_balance_minor)
        assertEquals("20101", accounts()[alternate.id]!!.posted_balance_minor)
        assertEquals("3", confirmed[fee.id]!!.version)
        assertEquals("3", confirmed[transfer.id]!!.version)
        assertEquals("300", confirmed[fee.id]!!.allocations.single().remaining_refundable_minor)
        assertEquals(replay.occurred_at, confirmed[replay.id]!!.occurred_at)
        assertEquals(replay.allocations.single().id, confirmed[replay.id]!!.allocations.single().id)
        assertEquals(listOf(tag.id), confirmed[replay.id]!!.tag_ids)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
}
