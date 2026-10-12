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
class FieldValidationScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue(InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("field-errors-${UUID.randomUUID()}@example.test", "synthetic field error password", "Field UI", "bearer", "UTC"))))
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> { if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin)) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) { compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(text: String) {
        compose.prepareFinancialSection(text)
        compose.waitUntil(30000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun field(label: String, value: String) { compose.onNodeWithText(label).performScrollTo().performTextReplacement(value) }
    private fun focus(label: String) {
        compose.waitUntil(10000) { compose.onAllNodes(hasText(label) and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).assertIsDisplayed().assertIsFocused()
    }
    private fun noAccountMutation() = runBlocking {
        assertTrue(api.accounts(auth.credential(origin)).isEmpty())
        assertTrue(api.transactions(auth.credential(origin)).isEmpty())
        assertNull(app.database.commands().get(origin, auth.profile.id, auth.workspace.id))
    }
    @Test fun errorsStayBesideFieldsFocusFirstAndNeverSendInvalidAccountCategoryOrTag() {
        waitFor("Создайте первый счет")
        click("Добавить счет"); click("Создать счет")
        waitFor("Введите название от 1 до 100 символов."); focus("Название счета")
        noAccountMutation()
        field("Название счета", "Inline account")
        field("Начальный остаток", "invalid")
        field("Дата открытия (YYYY-MM-DDTHH:MM:SS)", "2026-02-30T12:00:00")
        field("Часовой пояс открытия", "Unknown/Zone")
        click("Создать счет")
        waitFor("Введите сумму цифрами, например 12,34.")
        waitFor("Введите существующую дату в формате YYYY-MM-DDTHH:MM:SS.")
        waitFor("Введите известный часовой пояс, например UTC или Europe/Nicosia.")
        focus("Начальный остаток")
        compose.onNodeWithText("Начальный остаток").assertTextContains("invalid")
        noAccountMutation()
        field("Начальный остаток", "12,34"); click("Создать счет"); focus("Дата открытия (YYYY-MM-DDTHH:MM:SS)")
        noAccountMutation()
        field("Дата открытия (YYYY-MM-DDTHH:MM:SS)", "2026-10-06T12:00:00"); click("Создать счет"); focus("Часовой пояс открытия")
        noAccountMutation()
        field("Часовой пояс открытия", "UTC"); click("Создать счет")
        waitFor("Изменить счет Inline account")
        val session = auth.credential(origin)
        val balances = runBlocking { api.accounts(session) }
        assertEquals("1234", balances.single().posted_balance_minor)
        click("Новая категория"); click("Сохранить категорию")
        waitFor("Введите название от 1 до 100 символов."); focus("Название категории")
        field("Название категории", "x".repeat(101)); click("Сохранить категорию"); focus("Название категории")
        assertTrue(runBlocking { api.categories(session) }.isEmpty())
        field("Название категории", "Inline category"); click("Сохранить категорию"); waitFor("Изменить категорию Inline category")
        click("Новый тег"); field("Название тега", "x".repeat(51)); click("Сохранить тег")
        waitFor("Введите название от 1 до 50 символов."); focus("Название тега")
        assertTrue(runBlocking { api.tags(session) }.isEmpty())
        field("Название тега", "Inline tag"); click("Сохранить тег"); waitFor("Изменить тег Inline tag")
        assertEquals(balances, runBlocking { api.accounts(session) })
        assertEquals(1, runBlocking { api.transactions(session) }.size)
        assertEquals(1, runBlocking { api.categories(session) }.size)
        assertEquals(1, runBlocking { api.tags(session) }.size)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
    @Test fun incomeAndExpenseValidateRawFieldsAndSplitBeforeAnyCommand() {
        waitFor("Создайте первый счет")
        click("Добавить счет"); field("Название счета", "Operations account")
        field("Дата открытия (YYYY-MM-DDTHH:MM:SS)", "2026-10-01T12:00:00")
        field("Часовой пояс открытия", "UTC"); click("Создать счет")
        waitFor("Изменить счет Operations account")
        val session = auth.credential(origin)
        val baseline = runBlocking { api.accounts(session) }
        fun unchanged() = runBlocking {
            assertEquals(baseline, api.accounts(session))
            assertEquals(1, api.transactions(session).size)
            assertNull(app.database.commands().get(origin, auth.profile.id, auth.workspace.id))
        }
        click("Расход")
        field("Сумма операции", "invalid")
        field("Дата операции (YYYY-MM-DDTHH:MM:SS)", "2026-02-30T12:00:00")
        field("Часовой пояс операции", "Unknown/Zone")
        field("Получатель операции", "x".repeat(201))
        field("Примечание операции", "x".repeat(2001))
        click("Сохранить операцию"); focus("Сумма операции"); unchanged()
        compose.onNodeWithText("Сумма операции").assertTextContains("invalid")
        field("Сумма операции", "3,00"); click("Сохранить операцию"); focus("Дата операции (YYYY-MM-DDTHH:MM:SS)"); unchanged()
        field("Дата операции (YYYY-MM-DDTHH:MM:SS)", "2026-10-06T12:00:00"); click("Сохранить операцию"); focus("Часовой пояс операции"); unchanged()
        field("Часовой пояс операции", "UTC"); click("Сохранить операцию"); focus("Получатель операции"); unchanged()
        field("Получатель операции", "Vendor"); click("Сохранить операцию"); focus("Примечание операции"); unchanged()
        field("Примечание операции", "Validated expense")
        click("Добавить часть операции")
        field("Сумма части 1", "1,00"); field("Сумма части 2", "1,00")
        click("Сохранить операцию"); focus("Сумма части 1"); unchanged()
        field("Сумма части 2", "2,00"); click("Сохранить операцию")
        waitFor("Детали операции Validated expense")
        click("Доход"); field("Сумма операции", "0"); click("Сохранить операцию"); focus("Сумма операции")
        assertEquals(2, runBlocking { api.transactions(session) }.size)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        field("Сумма операции", "4,00"); field("Примечание операции", "Validated income"); click("Сохранить операцию")
        waitFor("Детали операции Validated income")
        val records = runBlocking { api.transactions(session) }
        assertEquals(3, records.size)
        assertEquals(listOf("100", "200"), records.single { it.note == "Validated expense" }.allocations.map { it.amount_minor }.sorted())
        assertEquals("100", runBlocking { api.accounts(session) }.single().posted_balance_minor)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }

    @Test fun transferAndFeeErrorsFocusInOrderAndPreserveLedgerUntilValid() {
        waitFor("Создайте первый счет")
        for ((name, currency) in listOf("Validation source" to "EUR", "Validation target" to "KWD")) {
            click("Добавить счет"); field("Название счета", name); click(currency)
            field("Дата открытия (YYYY-MM-DDTHH:MM:SS)", "2026-10-01T12:00:00")
            field("Часовой пояс открытия", "UTC"); click("Создать счет"); waitFor("Изменить счет $name")
        }
        val session = auth.credential(origin)
        val baseline = runBlocking { api.accounts(session) }
        fun unchanged() = runBlocking {
            assertEquals(baseline, api.accounts(session))
            assertEquals(2, api.transactions(session).size)
            assertNull(app.database.commands().get(origin, auth.profile.id, auth.workspace.id))
        }
        click("Новый перевод")
        click("Списать со счета: Validation source · EUR")
        click("Зачислить на счет: Validation target · KWD")
        field("Сумма списания перевода", "bad"); field("Сумма зачисления перевода", "0")
        field("Дата перевода (YYYY-MM-DDTHH:MM:SS)", "2026-02-30T12:00:00")
        field("Часовой пояс перевода", "Unknown/Zone")
        click("Добавить комиссию перевода")
        click("Счет комиссии: Validation source · EUR")
        field("Сумма комиссии перевода", "bad")
        field("Примечание комиссии перевода", "x".repeat(2001))
        click("Сохранить перевод"); focus("Сумма списания перевода"); unchanged()
        field("Сумма списания перевода", "3,00"); click("Сохранить перевод"); focus("Сумма зачисления перевода"); unchanged()
        field("Сумма зачисления перевода", "1,234"); click("Сохранить перевод"); focus("Дата перевода (YYYY-MM-DDTHH:MM:SS)"); unchanged()
        field("Дата перевода (YYYY-MM-DDTHH:MM:SS)", "2026-10-06T12:00:00"); click("Сохранить перевод"); focus("Часовой пояс перевода"); unchanged()
        field("Часовой пояс перевода", "UTC"); click("Сохранить перевод"); focus("Сумма комиссии перевода"); unchanged()
        field("Сумма комиссии перевода", "0,30"); click("Сохранить перевод"); focus("Примечание комиссии перевода"); unchanged()
        field("Примечание комиссии перевода", "Validated fee")
        field("Примечание перевода", "Validated transfer")
        click("Добавить часть комиссии")
        field("Сумма части комиссии 1", "0,10"); field("Сумма части комиссии 2", "0,10")
        click("Сохранить перевод"); focus("Сумма части комиссии 1"); unchanged()
        field("Сумма части комиссии 2", "0,20"); click("Сохранить перевод")
        waitFor("Детали операции Validated transfer")
        val records = runBlocking { api.transactions(session) }
        assertEquals(4, records.size)
        val transfer = records.single { it.note == "Validated transfer" }
        val fee = records.single { it.note == "Validated fee" }
        assertEquals(fee.id, transfer.fee_transaction_id)
        assertEquals(listOf("10", "20"), fee.allocations.map { it.amount_minor }.sorted())
        val balances = runBlocking { api.accounts(session) }
        assertEquals("-330", balances.single { it.name == "Validation source" }.posted_balance_minor)
        assertEquals("1234", balances.single { it.name == "Validation target" }.posted_balance_minor)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }

}
