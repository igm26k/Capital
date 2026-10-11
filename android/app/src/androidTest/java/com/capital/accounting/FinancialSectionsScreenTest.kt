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
class FinancialSectionsScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("sections-${UUID.randomUUID()}@example.test", "synthetic section password", "Sections UI", "bearer", "UTC"))))
            suspend fun create(path: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", "POST", body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
            create("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Section cash", "cash", "KWD", "2026-10-06T08:00:00Z", "UTC", "100000")))
            create("categories", ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), "Section category", null)))
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> { if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin)) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String) {
        compose.waitUntil(10000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun section(label: String) { compose.onNodeWithContentDescription("Раздел $label").performClick() }
    private fun field(label: String, value: String) { compose.onNodeWithText(label).performScrollTo().performTextReplacement(value) }
    private fun keep(back: String) {
        click(back)
        waitFor("Сохранить измененный черновик?")
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Сохранить черновик и продолжить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сохранить черновик и продолжить").performClick()
    }
    @Test fun realSectionsHideOtherListsAndRetainScopedDraftsAcrossNavigationAndRecreation() {
        waitFor("История операций")
        compose.onNodeWithContentDescription("Раздел Операции").assertIsSelected()
        compose.onNodeWithText("Добавить счет").assertDoesNotExist()
        click("Обновить счета"); click("Обновить категории и теги")
        section("Счета"); waitFor("Изменить счет Section cash")
        compose.onNodeWithText("История операций").assertDoesNotExist()
        compose.onNodeWithText("Категории и теги").assertDoesNotExist()
        click("Добавить счет"); field("Название счета", "Section account draft"); field("Начальный остаток", "invalid")
        keep("Вернуться к счетам")
        section("Еще"); waitFor("Категория: Section category")
        compose.onNodeWithText("Устройства").assertExists()
        compose.onNodeWithText("Изменить счет Section cash").assertDoesNotExist()
        click("Новая категория"); field("Название категории", "Section category draft")
        keep("Вернуться к категориям и тегам")
        section("Операции"); waitFor("История операций")
        compose.onNodeWithText("Категории и теги").assertDoesNotExist()
        click("Открыть черновик операции"); field("Примечание операции", "Section operation draft")
        keep("Вернуться к истории операций")
        section("Счета"); click("Восстановить черновик счета 1")
        compose.onNodeWithText("Название счета").assertTextContains("Section account draft")
        compose.onNodeWithText("Начальный остаток").assertTextContains("invalid")
        click("Вернуться к счетам")
        section("Еще"); click("Восстановить черновик классификации 1")
        compose.onNodeWithText("Название категории").assertTextContains("Section category draft")
        click("Вернуться к категориям и тегам")
        compose.activityRule.scenario.recreate()
        waitFor("Категории и теги")
        compose.onNodeWithContentDescription("Раздел Еще").assertIsSelected()
        section("Операции"); click("Восстановить черновик операции 1")
        compose.onNodeWithText("Примечание операции").assertTextContains("Section operation draft")
        click("Вернуться к истории операций")
        val session = auth.credential(origin)
        val accounts = runBlocking { api.accounts(session) }
        assertEquals(1, accounts.size)
        assertEquals("100000", accounts.single().posted_balance_minor)
        assertEquals(1, runBlocking { api.transactions(session) }.size)
        assertEquals(1, runBlocking { api.categories(session) }.size)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }
}
