package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.ConnectionSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FinancialAddScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private lateinit var cash: Account
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("add-ui-${UUID.randomUUID()}@example.test", "synthetic add password", "Add UI", "bearer", "UTC"))))
            suspend fun create(path: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", "POST", body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
            cash = create("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Add cash", "cash", "KWD", "2026-10-06T08:00:00Z", "UTC", "100000"))).accounts.single()
            create("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Add USD", "cash", "USD", "2026-10-06T08:00:00Z", "UTC", "3000")))
            create("transactions", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T08:10:00Z", "UTC", "Add expense", "", emptyList(), "expense", cash.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000")))))
            create("transactions", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T08:10:00Z", "UTC", "", "", emptyList(), "expense", cash.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000")))))
            val scope = com.capital.accounting.finance.FinancialDrafts(app).open(origin, auth.profile.id, auth.workspace.id)
            scope.put("account.name", ApiClient.json.encodeToString("Persisted add draft"))
            scope.put("account.balance", ApiClient.json.encodeToString("invalid"))
            withTimeout(10000) { while (scope.saving) delay(10) }
            check(!scope.failed)
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> { if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin)) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String) {
        try {
            compose.waitUntil(30000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: ComposeTimeoutException) {
            val labels = listOf("Добавить", "Что добавить?", "Сохранить измененный черновик?", "Вернуться к счетам", "Сохранение черновиков на устройстве…", "Не удалось сохранить черновики на устройстве. Повторите сохранение.")
            throw AssertionError("Expected enabled $text; visible states: ${labels.filter { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }}", failure)
        }
        val node = compose.onNodeWithText(text)
        if (text in setOf("Добавить", "Отменить добавление", "Остаться в редакторе", "Сохранить черновик и продолжить", "Отбросить черновик и продолжить")) node.performClick()
        else node.performScrollTo().performClick()
    }
    private fun field(label: String, value: String) = compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
    private fun choose(action: String) { click("Добавить"); waitFor("Что добавить?"); click(action) }
    private fun close(back: String) {
        click(back)
        if (compose.onAllNodesWithText("Сохранить измененный черновик?").fetchSemanticsNodes().isNotEmpty()) click("Отбросить черновик и продолжить")
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Добавить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun fixedAddRoutesAllKindsGuardsPersistedDraftAndOnlySubmitWritesLedger() {
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета"); click("Обновить историю")
        waitFor("Расход · −1,000 KWD · Add expense")
        compose.onNodeWithText("Удалить операцию Add expense").performScrollTo()
        compose.onNodeWithText("Добавить").assertIsDisplayed()
        click("Добавить"); click("Отменить добавление")
        choose("Создать счет")
        waitFor("Сохранить измененный черновик?")
        click("Остаться в редакторе")
        click("Открыть черновик счета")
        compose.onNodeWithText("Название счета").assertTextContains("Persisted add draft")
        compose.onNodeWithText("Начальный остаток").assertTextContains("invalid")
        compose.onNodeWithText("Добавить").assertDoesNotExist()
        click("Вернуться к счетам"); click("Сохранить черновик и продолжить")

        choose("Создать счет"); waitFor("Редактор счета"); close("Вернуться к счетам")
        choose("Создать категорию"); waitFor("Редактор категории"); close("Вернуться к категориям и тегам")
        choose("Создать тег"); waitFor("Редактор тега"); close("Вернуться к категориям и тегам")
        choose("Создать расход"); waitFor("Тип операции: Расход"); close("Вернуться к истории операций")
        choose("Создать перевод"); waitFor("Вернуться к истории переводов"); close("Вернуться к истории переводов")
        choose("Создать возврат"); waitFor("Исходный расход для возврата")
        compose.onNodeWithText("Возврат для расхода −1,000 KWD от 2026-10-06T08:10:00").assertExists()
        click("Возврат для Add expense")
        waitFor("Исходный расход: Add expense"); close("Вернуться к истории возвратов")
        choose("Создать корректировку"); waitFor("Счет для корректировки"); click("Остаток счета Add cash · KWD")
        waitFor("Корректируемый счет: Add cash")
        compose.onNodeWithText("Фактический остаток").assertTextContains("98,000")
        close("Вернуться к истории корректировок")
        assertEquals(4, runBlocking { api.transactions(auth.credential(origin)) }.size)
        assertEquals(2, runBlocking { api.accounts(auth.credential(origin)) }.size)
        assertEquals(0, runBlocking { api.categories(auth.credential(origin)) }.size)
        assertEquals(0, runBlocking { api.tags(auth.credential(origin)) }.size)

        choose("Создать доход"); waitFor("Тип операции: Доход")
        click("Для операции: Add cash · KWD")
        field("Сумма операции", "2.000"); field("Примечание операции", "Global add income")
        click("Сохранить операцию")
        waitFor("Доход · 2,000 KWD · Global add income")
        assertEquals(5, runBlocking { api.transactions(auth.credential(origin)) }.size)
        assertEquals("100000", runBlocking { api.accounts(auth.credential(origin)).single { it.id == cash.id }.posted_balance_minor })
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        compose.onNodeWithContentDescription("Раздел Счета").performClick()
        click("Восстановить черновик счета 1")
        compose.onNodeWithText("Название счета").assertTextContains("Persisted add draft")
        compose.onNodeWithText("Начальный остаток").assertTextContains("invalid")
    }
}
