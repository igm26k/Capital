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
class CatalogDraftGuardScreenTest {
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
    private fun click(text: String) {
        compose.waitUntil(10000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        if (text == "Остаться в редакторе") compose.onNodeWithText(text).performClick()
        else compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun field(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextClearance()
        compose.onNodeWithText(label).performTextInput(value)
    }
    private fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun choose(text: String) {
        waitFor("Сохранить измененный черновик?")
        compose.waitUntil(10000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performClick()
    }
    @Test fun accountAndCatalogDraftChoicesSurviveRecreationAndOwnReceipts() {
        waitFor("Вы вошли: ${auth.profile.email}"); waitFor("Обновить счета")
        click("Обновить счета"); waitFor("Изменить счет Detail cash")
        click("Обновить категории и теги"); waitFor("Изменить тег $tagName")
        click("Добавить счет")
        field("Название счета", "Unsent account")
        field("Начальный остаток", "invalid")
        click("KWD")
        click("Вернуться к счетам"); choose("Остаться в редакторе")
        compose.onNodeWithText("Название счета").assertTextContains("Unsent account")
        click("Вернуться к счетам"); choose("Сохранить черновик и продолжить")
        click("Изменить счет Detail cash")
        field("Новое название счета", "Own account")
        click("Сохранить счет"); waitFor("Изменить счет Own account")
        compose.waitUntil(30000) { runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) } == null }
        click("Открыть черновик счета")
        val own = runBlocking { api.accounts(auth.credential(origin)).single { it.id == cash.id } }
        val foreign = runBlocking { mutate("accounts/${cash.id}", "PUT", ApiClient.json.encodeToString(AccountUpdate(own.version, "Foreign account", own.type, false))).accounts.single() }
        click("Обновить счета")
        waitFor("Текущая версия на сервере: ${foreign.version}; ваш черновик: ${own.version}")
        click("Новый счет вместо изменения")
        compose.onNodeWithText("Сохранить измененный черновик?").assertDoesNotExist()
        click("Восстановить черновик счета 1")
        compose.onNodeWithText("Начальный остаток").assertTextContains("invalid")
        click("Вернуться к счетам")
        field("Название категории", "Unsent category")
        click("Родитель категории: Detail group")
        click("Новый тег"); choose("Сохранить черновик и продолжить")
        field("Название тега", "Unsent tag")
        click("Изменить тег $tagName"); choose("Сохранить черновик и продолжить")
        field("Название тега", "Own tag")
        click("Сохранить тег"); waitFor("Изменить тег Own tag")
        compose.waitUntil(30000) { runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) } == null }
        val ownTag = runBlocking { api.tags(auth.credential(origin)).single { it.name == "Own tag" } }
        runBlocking { mutate("tags/${ownTag.id}", "PUT", ApiClient.json.encodeToString(TagUpdate(ownTag.version, "Foreign tag", false))) }
        click("Обновить категории и теги")
        waitFor("Версия классификации на сервере: 3; версия черновика: 2")
        click("Восстановить черновик классификации 1")
        compose.onNodeWithText("Сохранить измененный черновик?").assertDoesNotExist()
        compose.onNodeWithText("Название категории").assertTextContains("Unsent category")
        compose.onNodeWithText("Родитель: Detail group").assertExists()
        click("Восстановить черновик классификации 2")
        compose.onNodeWithText("Название тега").assertTextContains("Unsent tag")
        field("Название тега", "Discard tag")
        click("Новая категория"); choose("Отбросить черновик и продолжить")
        click("Восстановить черновик классификации 2")
        compose.activityRule.scenario.recreate()
        waitFor("Обновить счета")
        compose.onNodeWithText("Название тега").assertTextContains("Unsent tag")
        click("Восстановить черновик счета 1")
        compose.onNodeWithText("Начальный остаток").assertTextContains("invalid")
        waitFor("Локальные черновики сохранены. Отправка операций выполняется отдельно.")
        assertEquals(6, runBlocking { api.transactions(auth.credential(origin)).size })
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
}
