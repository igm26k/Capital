package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import com.capital.accounting.data.ConnectionSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidWebScreenTest {
    private lateinit var auth: BearerAuth
    private lateinit var api: ApiClient
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue(InstrumentationRegistry.getArguments().getString("api_origin") != null)
            File(app.filesDir, "android-web-proof.json").delete()
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("android-web-${UUID.randomUUID()}@example.test", "Android web acceptance password", "Native web acceptance", "bearer", "UTC"))))
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
    @Test fun nativeFormsCreateExactKwdLedgerForIndependentChromeRead() {
        waitFor("Создайте первый счет")
        click("Добавить счет"); field("Название счета", "Native KWD cash"); click("KWD")
        field("Начальный остаток", "100,000")
        field("Дата открытия (YYYY-MM-DDTHH:MM:SS)", "2026-10-01T12:00:00")
        field("Часовой пояс открытия", "UTC"); click("Создать счет")
        waitFor("Изменить счет Native KWD cash")
        click("Расход"); field("Сумма операции", "12,345")
        field("Дата операции (YYYY-MM-DDTHH:MM:SS)", "2026-10-06T12:00:00")
        field("Часовой пояс операции", "UTC")
        field("Получатель операции", "Native vendor")
        field("Примечание операции", "Native to Chrome expense")
        click("Сохранить операцию"); waitFor("Детали операции Native to Chrome expense")
        val session = auth.credential(origin)
        val account = runBlocking { api.accounts(session).single() }
        val records = runBlocking { api.transactions(session) }
        val expense = records.single { it.kind == "expense" }
        assertEquals(2, records.size); assertEquals("87655", account.posted_balance_minor)
        assertEquals("12345", expense.allocations.single().amount_minor)
        assertEquals("-12345", expense.entries.single().amount_minor)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        var action: String? = null
        compose.activityRule.scenario.onActivity { action = ViewModelProvider(it)[AuthViewModel::class.java].state.transactionReceipt?.commandId }
        assertNotNull(action)
        val proof = JSONObject().put("email", auth.profile.email).put("workspace", auth.workspace.id)
            .put("account", account.id).put("expense", expense.id).put("action", action)
            .put("balance_minor", "87655").put("amount_minor", "12345").put("native_ui", true)
        File(app.filesDir, "android-web-proof.json").writeText(proof.toString())
    }
}
