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
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
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
    private fun click(text: String) {
        compose.prepareFinancialSection(text)
        compose.waitUntil(10000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.financialNode(text).performScrollTo().performClick()
        if (text.startsWith("Изменить счет ") || text == "Новый счет вместо изменения") {
            if (compose.onAllNodesWithText("Сохранить измененный черновик?").fetchSemanticsNodes().isNotEmpty()) {
                compose.waitUntil(10000) { compose.onAllNodes(hasText("Отбросить черновик и продолжить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.financialNode("Отбросить черновик и продолжить").performClick()
            }
        }
    }
    private fun field(label: String, value: String) {
        compose.financialNode(label).performScrollTo().performTextClearance()
        compose.financialNode(label).performTextInput(value)
    }
    private fun waitFor(text: String) = run { compose.prepareFinancialSection(text); compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun currencyTotalsArchiveFilterAndArchivedHistorySurviveRecreation() {
        val before = runBlocking {
            suspend fun create(name: String, currency: String, balance: String) = decodeResponse<MutationResult>(api.request(
                "/workspaces/${auth.workspace.id}/accounts", "POST",
                ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), name, "cash", currency, "2026-10-06T08:00:00Z", "UTC", balance)),
                auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id)).accounts.single()
            create("Totals cash", "KWD", "100001")
            create("Totals debt", "KWD", "-2000")
            create("Totals USD one", "USD", "9000000000000000")
            create("Totals USD two", "USD", "9000000000000000")
            val archived = create("Totals archive", "KWD", "7000")
            api.request("/workspaces/${auth.workspace.id}/accounts/${archived.id}", "PUT",
                ApiClient.json.encodeToString(AccountUpdate(archived.version, archived.name, archived.type, true)),
                auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id)
            api.accounts(auth.credential(origin))
        }
        fun directClick(text: String) {
            compose.waitUntil(30000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text).performScrollTo().performClick()
        }
        waitFor("Вы вошли: ${auth.profile.email}")
        directClick("Обновить счета")
        compose.onNodeWithContentDescription("Раздел Счета").performClick()
        waitFor("Итого: 98,001 KWD")
        compose.onNodeWithText("Итого: 180000000000000,00 USD").assertExists()
        compose.onNodeWithText("Изменить счет Totals debt").assertExists()
        compose.onNodeWithText("Изменить счет Totals archive").assertDoesNotExist()
        directClick("Архивные счета")
        waitFor("Итого: 7,000 KWD")
        compose.onNodeWithText("Итого: 180000000000000,00 USD").assertDoesNotExist()
        compose.onNodeWithText("Изменить счет Totals cash").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        waitFor("Показаны счета: архивные")
        compose.onNodeWithText("Итого: 7,000 KWD").assertExists()
        directClick("Операции счета Totals archive")
        waitFor("История счета: Totals archive")
        waitFor("Показано операций: 1")
        compose.onNodeWithContentDescription("Раздел Счета").performClick()
        compose.onNodeWithText("Показаны счета: архивные").assertExists()
        directClick("Все счета")
        waitFor("Итого: 105,001 KWD")
        compose.onNodeWithText("Итого: 180000000000000,00 USD").assertExists()
        compose.onNodeWithText("Изменить счет Totals archive").assertExists()
        compose.onNodeWithText("Изменить счет Totals cash").assertExists()
        assertEquals(before.sortedBy { it.id }, runBlocking { api.accounts(auth.credential(origin)) }.sortedBy { it.id })
        assertEquals(5, runBlocking { api.transactions(auth.credential(origin)) }.size)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
    @Test fun separateAccountEditorGuardsSystemBackAndReturnsOnlyAfterConfirmation() {
        waitFor("Вы вошли: ${auth.profile.email}"); waitFor("Добавить счет")
        compose.financialNode("Название счета").assertDoesNotExist()
        click("Добавить счет")
        field("Название счета", "Retained account")
        field("Начальный остаток", "invalid")
        click("KWD")
        fun systemBack() {
            compose.waitUntil(10000) { compose.onAllNodes(hasText("Вернуться к счетам") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        systemBack()
        android.os.SystemClock.sleep(500)
        if (compose.onAllNodesWithText("Сохранить измененный черновик?").fetchSemanticsNodes().isEmpty()) systemBack()
        waitFor("Сохранить измененный черновик?")
        compose.financialNode("Остаться в редакторе").performClick()
        compose.financialNode("Название счета").assertTextContains("Retained account")
        compose.activityRule.scenario.recreate()
        waitFor("Редактор счета")
        compose.financialNode("Начальный остаток").assertTextContains("invalid")
        click("Вернуться к счетам")
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Сохранить черновик и продолжить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.financialNode("Сохранить черновик и продолжить").performClick()
        waitFor("Добавить счет")
        compose.financialNode("Редактор счета").assertDoesNotExist()
        assertEquals(0, runBlocking { api.accounts(auth.credential(origin)).size })
        click("Восстановить черновик счета 1")
        compose.financialNode("Название счета").assertTextContains("Retained account")
        field("Название счета", "Discarded account")
        click("Вернуться к счетам")
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Отбросить черновик и продолжить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.financialNode("Отбросить черновик и продолжить").performClick()
        waitFor("Добавить счет")
        click("Восстановить черновик счета 1")
        field("Начальный остаток", "123.456")
        click("Создать счет")
        waitFor("Изменить счет Retained account")
        compose.financialNode("Редактор счета").assertDoesNotExist()
        compose.financialNode("123,456 KWD").assertExists()
        val account = runBlocking { api.accounts(auth.credential(origin)).single() }
        assertEquals("123456", account.posted_balance_minor)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
    @Test fun editorExposesExplicitRetryWhenActualAtomicFileCommitFails() {
        waitFor("Вы вошли: ${auth.profile.email}"); waitFor("Добавить счет")
        click("Добавить счет")
        field("Название счета", "Before disk failure")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(ApiClient.json.encodeToString(listOf(origin, auth.profile.id, auth.workspace.id)).toByteArray()).joinToString("") { "%02x".format(it) }
        val file = java.io.File(app.noBackupFilesDir, "financial-drafts/$hash.json")
        val backup = java.io.File(file.parentFile, "$hash.test-original")
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Вернуться к счетам") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(file.renameTo(backup))
        assertTrue(file.mkdir())
        val obstruction = java.io.File(file, "obstruction").apply { writeText("synthetic fixture") }
        try {
            field("Название счета", "Retained during disk failure")
            waitFor("Повторить локальное сохранение")
            click("Вернуться к счетам")
            waitFor("Сохранить измененный черновик?")
            compose.financialNode("Сохранить черновик и продолжить").assertIsNotEnabled()
            compose.financialNode("Остаться в редакторе").performClick()
            compose.financialNode("Название счета").assertTextContains("Retained during disk failure")
            assertTrue(obstruction.delete()); assertTrue(file.delete()); assertTrue(backup.renameTo(file))
            click("Повторить локальное сохранение")
            compose.waitUntil(10000) { compose.onAllNodesWithText("Повторить локальное сохранение").fetchSemanticsNodes().isEmpty() }
            val disk = runBlocking { com.capital.accounting.finance.FinancialDrafts(app).open(origin, auth.profile.id, auth.workspace.id) }
            assertEquals(ApiClient.json.encodeToString("Retained during disk failure"), disk.read("account.name"))
            assertEquals(0, runBlocking { api.accounts(auth.credential(origin)).size })
            assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        } finally {
            if (file.isDirectory) { obstruction.delete(); file.delete() }
            if (backup.exists()) { file.delete(); assertTrue(backup.renameTo(file)) }
        }
    }
    @Test fun exactCreationArchiveAndExplicitConflictResolution() {
        waitFor("Вы вошли: ${auth.profile.email}")
        waitFor("Добавить счет"); click("Добавить счет")
        field("Название счета", "Android pocket")
        click("KWD")
        field("Начальный остаток", "123.456")
        click("Создать счет")
        waitFor("Изменить счет Android pocket")
        compose.financialNode("123,456 KWD").assertExists()
        val initial = runBlocking { api.accounts(auth.credential(origin), limit = 1).single() }
        assertEquals("123456", initial.posted_balance_minor)
        click("Изменить счет Android pocket")
        field("Новое название счета", "Archived pocket")
        click("Наличные")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        click("Сохранить счет")
        waitFor("Добавить счет")
        compose.onNodeWithContentDescription("Раздел Счета").performClick()
        click("Архивные счета")
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
        compose.financialNode("Выйти").assertIsNotEnabled()
        compose.financialNode("Ваш черновик: Local proposal, cash; в архив").assertExists()
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
        val rejected = runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!! }
        assertEquals("rejected", rejected.state)
        assertEquals(archived.version, decodeResponse<AccountUpdate>(rejected.body).expected_version)
        click("Обновить данные для новой команды")
        waitFor("Данные обновлены. Проверьте поля перед новой командой.")
        click("Использовать обновленную версию")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        click("Сохранить счет")
        waitFor("Добавить счет")
        click("Активные счета")
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
        compose.financialNode("Повторить сохраненную команду").assertIsNotEnabled()
        compose.financialNode("Выйти").assertIsNotEnabled()
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
