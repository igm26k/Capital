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
class TransactionsScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private lateinit var initial: Account
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("transactions-ui-${UUID.randomUUID()}@example.test", "synthetic transactions password", "Transactions UI", "bearer", "UTC"))))
            val result = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/accounts", "POST",
                ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "KWD cash", "cash", "KWD", "2026-10-06T08:00:00Z", "UTC", "100000")),
                auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
            initial = result.accounts.single()
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
    @Test fun realExpenseIncomeExactBalancesHistoryAndArchiveGuard() {
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета")
        waitFor("Для операции: KWD cash · KWD")
        click("Для операции: KWD cash · KWD")
        field("Сумма операции", "12.345")
        field("Дата операции (YYYY-MM-DDTHH:MM:SS)", "2026-10-06T11:01:00")
        field("Часовой пояс операции", "Europe/Nicosia")
        field("Примечание операции", "<svg onload=alert(1)>")
        click("Сохранить операцию")
        waitFor("Расход · −12,345 KWD · <svg onload=alert(1)>")
        val afterExpense = runBlocking { api.accounts(auth.credential(origin)).single() }
        assertEquals("87655", afterExpense.posted_balance_minor)
        click("Доход")
        field("Сумма операции", "5.001")
        field("Примечание операции", "Salary")
        click("Сохранить операцию")
        waitFor("Доход · 5,001 KWD · Salary")
        val afterIncome = runBlocking { api.accounts(auth.credential(origin)).single() }
        assertEquals("92656", afterIncome.posted_balance_minor)
        val history = runBlocking { api.transactions(auth.credential(origin), limit = 1) }
        assertEquals(3, history.size)
        val expense = history.single { it.kind == "expense" }
        assertEquals("12345", expense.allocations.single().amount_minor)
        assertNull(expense.allocations.single().category_id)
        assertEquals("-12345", expense.entries.single().amount_minor)
        assertEquals("2026-10-06T08:01:00.000000Z", expense.occurred_at)
        assertEquals("Europe/Nicosia", expense.occurred_timezone)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        field("Сумма операции", "0")
        click("Сохранить операцию")
        waitFor("Проверьте положительную сумму, счет, дату, часовой пояс и длину текста.")
        assertEquals(3, runBlocking { api.transactions(auth.credential(origin)).size })
        val pending = runBlocking {
            com.capital.accounting.finance.CommandRunner(app.database.commands()).prepare(auth.credential(origin), "transactions", "POST",
                ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T08:02:00.000Z", "Europe/Nicosia", "Pending expense", "", emptyList(), "expense", initial.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000")))))
        }
        compose.activityRule.scenario.recreate()
        click("Обновить счета")
        waitFor("Сохраненная команда: ${pending.commandId}")
        waitFor("1,000")
        compose.onNodeWithText("2026-10-06T11:02:00").assertExists()
        compose.onNodeWithText("Pending expense").assertExists()
        compose.onNodeWithText("Сохранить операцию").assertIsNotEnabled()
        click("Повторить сохраненную команду")
        waitFor("Расход · −1,000 KWD · Pending expense")
        assertEquals("91656", runBlocking { api.accounts(auth.credential(origin)).single().posted_balance_minor })
        assertEquals(4, runBlocking { api.transactions(auth.credential(origin), limit = 1).size })
        click("Изменить операцию Salary")
        field("Сумма операции", "6.001")
        field("Примечание операции", "Updated salary")
        click("Сохранить изменения операции")
        waitFor("Доход · 6,001 KWD · Updated salary")
        assertEquals("92656", runBlocking { api.accounts(auth.credential(origin)).single().posted_balance_minor })
        click("Отменить изменение операции")
        click("Изменить операцию Pending expense")
        field("Сумма операции", "1.5")
        field("Примечание операции", "Local edited")
        runBlocking {
            val target = api.transactions(auth.credential(origin)).single { it.note == "Pending expense" }
            api.request("/workspaces/${auth.workspace.id}/transactions/${target.id}", "PUT",
                ApiClient.json.encodeToString(ExpenseReplace(target.occurred_at, target.occurred_timezone, "Server edited", target.payee, target.tag_ids, "expense", initial.id, "1200", listOf(AllocationInput(target.allocations.single().id, null, "1200")), target.version, null)),
                auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id)
        }
        click("Сохранить изменения операции")
        waitFor("Обновить данные для новой команды")
        compose.onNodeWithText("Выйти").assertIsNotEnabled()
        compose.onNodeWithText("На сервере: Server edited; версия 2; −1,200 KWD").assertExists()
        assertEquals("rejected", runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!!.state })
        click("Обновить данные для новой команды")
        waitFor("Использовать обновленную версию операции")
        click("Использовать обновленную версию операции")
        click("Сохранить изменения операции")
        waitFor("Расход · −1,500 KWD · Local edited")
        assertEquals("92156", runBlocking { api.accounts(auth.credential(origin)).single().posted_balance_minor })
        click("Отменить изменение операции")
        click("Удалить операцию Local edited")
        click("Отменить удаление")
        assertEquals(4, runBlocking { api.transactions(auth.credential(origin)).size })
        click("Удалить операцию Local edited")
        click("Подтвердить удаление операции")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Удалить операцию Local edited").fetchSemanticsNodes().isEmpty() }
        assertEquals("93656", runBlocking { api.accounts(auth.credential(origin)).single().posted_balance_minor })
        assertEquals(3, runBlocking { api.transactions(auth.credential(origin)).size })
        click("Удалить операцию Updated salary")
        click("Подтвердить удаление операции")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Удалить операцию Updated salary").fetchSemanticsNodes().isEmpty() }
        assertEquals("87655", runBlocking { api.accounts(auth.credential(origin)).single().posted_balance_minor })
        assertEquals(2, runBlocking { api.transactions(auth.credential(origin)).size })
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        runBlocking {
            val currentAccount = api.accounts(auth.credential(origin)).single()
            api.request("/workspaces/${auth.workspace.id}/accounts/${currentAccount.id}", "PUT",
                ApiClient.json.encodeToString(AccountUpdate(currentAccount.version, currentAccount.name, currentAccount.type, true)),
                auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id)
        }
        click("Обновить счета")
        waitFor("KWD cash · В архиве")
        compose.onNodeWithText("Сохранить операцию").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить историю")
        waitFor("Расход · −12,345 KWD · <svg onload=alert(1)>")
        compose.onNodeWithText("Доход · 6,001 KWD · Updated salary").assertDoesNotExist()
    }
}
