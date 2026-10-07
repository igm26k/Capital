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
class AccountsScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("account-ui-${UUID.randomUUID()}@example.test", "synthetic accounts password", "Accounts UI", "bearer", "UTC"))))
            val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> {
            if (::auth.isInitialized) {
                val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
                val saved = app.credentialVault.load(origin)
                if (saved is com.capital.accounting.auth.VaultRead.Available) api.request("/auth/logout", "POST", session = saved.session)
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun field(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextClearance()
        compose.onNodeWithText(label).performTextInput(value)
    }
    private fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    @Test fun exactCreationArchiveAndExplicitConflictResolution() {
        waitFor("Вы вошли: ${auth.profile.email}")
        field("Название счета", "Android pocket")
        click("KWD")
        field("Начальный остаток", "123.456")
        click("Создать счет")
        waitFor("Изменить счет Android pocket")
        compose.onNodeWithText("123,456 KWD").assertExists()
        val initial = runBlocking { api.accounts(auth.credential(origin), limit = 1).single() }
        assertEquals("123456", initial.posted_balance_minor)
        click("Изменить счет Android pocket")
        field("Новое название счета", "Archived pocket")
        click("Наличные")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        click("Сохранить счет")
        waitFor("Archived pocket · В архиве")
        val archived = runBlocking { api.accounts(auth.credential(origin)).single() }
        assertNotNull(archived.archived_at)
        assertEquals("cash", archived.type)
        assertEquals(initial.balance_version, archived.balance_version)
        click("Изменить счет Archived pocket")
        field("Новое название счета", "Local proposal")
        runBlocking {
            api.request("/workspaces/${archived.workspace_id}/accounts/${archived.id}", "PUT",
                ApiClient.json.encodeToString(AccountUpdate(archived.version, "Server proposal", "bank", true)),
                auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id)
        }
        click("Сохранить счет")
        waitFor("Обновить данные для новой команды")
        compose.onNodeWithText("Выйти").assertIsNotEnabled()
        compose.onNodeWithText("Ваш черновик: Local proposal, cash; в архив").assertExists()
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
        val rejected = runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!! }
        assertEquals("rejected", rejected.state)
        assertEquals(archived.version, decodeResponse<AccountUpdate>(rejected.body).expected_version)
        click("Обновить данные для новой команды")
        waitFor("Данные обновлены. Проверьте поля перед новой командой.")
        click("Использовать обновленную версию")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        click("Сохранить счет")
        waitFor("Изменить счет Local proposal")
        val restored = runBlocking { api.accounts(auth.credential(origin)).single() }
        assertNull(restored.archived_at)
        assertEquals("123456", restored.posted_balance_minor)
        assertEquals(initial.balance_version, restored.balance_version)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        compose.activityRule.scenario.recreate()
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета")
        waitFor("Изменить счет Local proposal")
        val pending = runBlocking {
            com.capital.accounting.finance.CommandRunner(app.database.commands()).prepare(auth.credential(origin), "accounts/${restored.id}", "PUT",
                ApiClient.json.encodeToString(AccountUpdate(restored.version, "Rebound pocket", restored.type, false)))
        }
        runBlocking { api.request("/auth/logout", "POST", session = auth.credential(origin)) }
        click("Обновить счета")
        waitFor("Войти")
        field("Email", auth.profile.email)
        field("Пароль", "synthetic accounts password")
        click("Войти")
        waitFor("Продолжить сохраненную команду в этой сессии")
        compose.onNodeWithText("Повторить сохраненную команду").assertIsNotEnabled()
        compose.onNodeWithText("Выйти").assertIsNotEnabled()
        click("Продолжить сохраненную команду в этой сессии")
        waitFor("Команда сохранена для текущей сессии. Проверьте черновик перед повтором.")
        val rebound = runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!! }
        assertEquals(pending.commandId, rebound.commandId)
        assertEquals(pending.body, rebound.body)
        assertNotEquals(pending.sessionId, rebound.sessionId)
        click("Повторить сохраненную команду")
        waitFor("Изменить счет Rebound pocket")
        val current = runBlocking { (app.credentialVault.load(origin) as com.capital.accounting.auth.VaultRead.Available).session }
        val final = runBlocking { api.accounts(current).single() }
        assertEquals("Rebound pocket", final.name)
        assertEquals("123456", final.posted_balance_minor)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
}
