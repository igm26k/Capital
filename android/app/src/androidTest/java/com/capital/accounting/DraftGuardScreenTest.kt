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
class DraftGuardScreenTest {
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
        if (text == "Остаться в редакторе") compose.onNodeWithText(text).performClick()
        else compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun field(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextClearance()
        compose.onNodeWithText(label).performTextInput(value)
    }
    private fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    @Test fun realDraftChoicesRetainSeparateRawAndEditedRecordsWithoutSending() {
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета"); waitFor("Для операции: Detail cash · KWD")
        click("Обновить историю"); waitFor("Изменить операцию Detail expense")
        field("Примечание операции", "Local unsent draft")
        field("Сумма операции", "invalid amount")
        click("Изменить операцию Detail expense")
        waitFor("Сохранить измененный черновик?")
        click("Остаться в редакторе")
        compose.onNodeWithText("Примечание операции").assertTextContains("Local unsent draft")
        click("Изменить операцию Detail expense")
        waitFor("Сохранить измененный черновик?")
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Сохранить черновик и продолжить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сохранить черновик и продолжить").performClick()
        waitFor("Изменение операции")
        compose.onNodeWithText("Примечание операции").assertTextContains("Detail expense")
        field("Примечание операции", "Edited unsent expense")
        click("Отменить изменение операции")
        waitFor("Сохранить измененный черновик?")
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Сохранить черновик и продолжить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сохранить черновик и продолжить").performClick()
        waitFor("Новая операция")
        click("Восстановить черновик операции 1")
        compose.onNodeWithText("Сумма операции").assertTextContains("invalid amount")
        compose.onNodeWithText("Примечание операции").assertTextContains("Local unsent draft")
        click("Восстановить черновик операции 2")
        waitFor("Изменение операции")
        compose.onNodeWithText("Примечание операции").assertTextContains("Edited unsent expense")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Примечание операции").assertTextContains("Edited unsent expense")
        field("Примечание операции", "Discard this edit")
        click("Отменить изменение операции")
        waitFor("Сохранить измененный черновик?")
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Отбросить черновик и продолжить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Отбросить черновик и продолжить").performClick()
        waitFor("Новая операция")
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        assertEquals(6, runBlocking { api.transactions(auth.credential(origin)) }.size)
        assertEquals("Detail expense", runBlocking { api.transaction(auth.credential(origin), expense.id) }.note)
    }
}
