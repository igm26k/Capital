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
class AdjustmentsScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private lateinit var account: Account
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("adjustments-ui-${UUID.randomUUID()}@example.test", "synthetic adjustment password", "Adjustment UI", "bearer", "UTC"))))
            account = mutate("accounts", "POST", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Adjustment cash", "cash", "KWD", "2026-10-06T08:00:00Z", "UTC", "100000"))).accounts.single()
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
    private fun history() = runBlocking { api.transactions(auth.credential(origin)) }
    private fun current() = runBlocking { api.accounts(auth.credential(origin)).single { it.id == account.id } }
    private fun load() {
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета")
        waitFor("Корректировать счет Adjustment cash · KWD")
        click("Обновить историю")
        waitFor("Начальный остаток · 100,000 KWD · ")
    }
    @Test fun realNegativeAndZeroTargetsBalanceConflictAndDeletion() {
        load()
        click("Корректировать счет Adjustment cash · KWD")
        field("Причина корректировки", "Cash count")
        click("Сохранить корректировку")
        waitFor("Проверьте остаток, причину, часовой пояс и длину текста. Остаток должен измениться.")
        assertEquals(0, history().count { it.kind == "adjustment" })
        field("Фактический остаток", "-1.234")
        compose.onNodeWithText("Разница нового остатка: −101,234 KWD").assertExists()
        field("Примечание корректировки", "Negative cash")
        field("Часовой пояс корректировки", "Europe/Nicosia")
        click("Сохранить корректировку")
        waitFor("Корректировка · −101,234 KWD · Negative cash")
        val negative = history().single { it.note == "Negative cash" }
        assertEquals("-1234", current().posted_balance_minor)
        assertEquals("Cash count", negative.reason)
        assertEquals("Europe/Nicosia", negative.occurred_timezone)
        assertTrue(negative.allocations.isEmpty())
        click("Корректировать счет Adjustment cash · KWD")
        field("Фактический остаток", "0")
        field("Примечание корректировки", "Zero cash")
        val income = IncomeCreate(UUID.randomUUID().toString(), "2026-10-06T08:10:00Z", "UTC", "Concurrent income", "", emptyList(), "income", account.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000")))
        runBlocking { mutate("transactions", "POST", ApiClient.json.encodeToString(income)) }
        click("Сохранить корректировку")
        waitFor("Изменение отклонено: version_conflict. Обновите данные и проверьте черновик.")
        val command = runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!! }
        assertEquals("2", decodeResponse<AdjustmentCreate>(command.body).expected_balance_version)
        assertEquals("0", decodeResponse<AdjustmentCreate>(command.body).target_balance_minor)
        assertEquals("-234", current().posted_balance_minor)
        compose.onNodeWithText("Выйти").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Фактический остаток").assertTextContains("0,000")
        compose.onNodeWithText("Причина корректировки").assertTextContains("Cash count")
        click("Обновить данные для новой команды")
        waitFor("Версия остатка на сервере: 3; черновика: 2")
        compose.onNodeWithText("Разница нового остатка: 0,234 KWD").assertExists()
        assertEquals(1, history().count { it.kind == "adjustment" })
        click("Использовать обновленную версию остатка")
        click("Сохранить корректировку")
        waitFor("Корректировка · 0,234 KWD · Zero cash")
        assertEquals("0", current().posted_balance_minor)
        click("Удалить корректировку Zero cash")
        click("Отменить удаление корректировки")
        assertEquals(2, history().count { it.kind == "adjustment" })
        click("Удалить корректировку Zero cash")
        click("Подтвердить удаление корректировки")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Удалить корректировку Zero cash").fetchSemanticsNodes().isEmpty() }
        assertEquals("-234", current().posted_balance_minor)
        click("Удалить корректировку Negative cash")
        click("Подтвердить удаление корректировки")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Удалить корректировку Negative cash").fetchSemanticsNodes().isEmpty() }
        assertEquals("101000", current().posted_balance_minor)
        assertEquals(0, history().count { it.kind == "adjustment" })
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }

    @Test fun realLostReceiptReplayAndNoteOnlyVersionConflict() {
        val initial = AdjustmentCreate(UUID.randomUUID().toString(), account.balance_version, "123456", "Initial count", "Initial adjustment", "UTC")
        runBlocking { mutate("accounts/${account.id}/adjustments", "POST", ApiClient.json.encodeToString(initial)) }
        load()
        val draft = AdjustmentCreate(UUID.randomUUID().toString(), current().balance_version, "120000", "Repeated count", "Replay adjustment", "Europe/Nicosia")
        val body = ApiClient.json.encodeToString(draft)
        val command = runBlocking { CommandRunner(app.database.commands()).prepare(auth.credential(origin), "accounts/${account.id}/adjustments", "POST", body) }
        runBlocking { api.request(command.path, command.method, command.body, auth.credential(origin), command.commandId, command.generationId) }
        val applied = history().single { it.id == draft.id }
        compose.activityRule.scenario.recreate()
        click("Обновить счета")
        waitFor("Сохраненная команда: ${command.commandId}")
        waitFor("Корректируемый счет: Adjustment cash")
        compose.onNodeWithText("Фактический остаток").assertTextContains("120,000")
        compose.onNodeWithText("Причина корректировки").assertTextContains("Repeated count")
        assertEquals(body, runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!!.body })
        click("Повторить сохраненную команду")
        waitFor("Изменить корректировку Replay adjustment")
        assertEquals(2, history().count { it.kind == "adjustment" })
        assertEquals(applied.occurred_at, history().single { it.id == draft.id }.occurred_at)
        assertEquals("120000", current().posted_balance_minor)
        click("Изменить корректировку Replay adjustment")
        compose.onNodeWithText("Фактический остаток").assertDoesNotExist()
        val balanceVersion = current().balance_version
        runBlocking { mutate("transactions/${draft.id}", "PUT", ApiClient.json.encodeToString(AdjustmentReplace("adjustment", applied.version, "Remote adjustment note"))) }
        field("Примечание корректировки", "Local adjustment note")
        click("Сохранить примечание корректировки")
        waitFor("Изменение отклонено: version_conflict. Обновите данные и проверьте черновик.")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Примечание корректировки").assertTextContains("Local adjustment note")
        click("Обновить данные для новой команды")
        waitFor("Версия корректировки на сервере: 2; черновика: 1")
        click("Использовать обновленную версию корректировки")
        click("Сохранить примечание корректировки")
        waitFor("Изменить корректировку Local adjustment note")
        val edited = history().single { it.id == draft.id }
        assertEquals("3", edited.version)
        assertEquals(applied.entries, edited.entries)
        assertEquals(applied.occurred_at, edited.occurred_at)
        assertEquals(applied.reason, edited.reason)
        assertEquals(balanceVersion, current().balance_version)
        assertEquals("120000", current().posted_balance_minor)
        click("Удалить корректировку Local adjustment note")
        click("Подтвердить удаление корректировки")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Удалить корректировку Local adjustment note").fetchSemanticsNodes().isEmpty() }
        assertEquals("123456", current().posted_balance_minor)
        assertEquals(1, history().count { it.kind == "adjustment" })
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
}
