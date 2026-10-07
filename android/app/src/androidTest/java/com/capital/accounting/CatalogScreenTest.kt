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
class CatalogScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("catalog-ui-${UUID.randomUUID()}@example.test", "synthetic catalog password", "Catalog UI", "bearer", "UTC"))))
            suspend fun create(path: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", "POST", body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
            val parent = create("categories", ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), "Home", null))).categories.single()
            create("categories", ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), "Food", parent.id)))
            create("tags", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "<svg onload=alert(1)>")))
            create("tags", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "Shared")))
            create("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Split cash", "cash", "KWD", "2026-10-06T08:00:00Z", "UTC", "100000")))
            val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> {
            if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin))
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realCatalogPaginationHierarchyLiteralTextAndRecreation() {
        runBlocking {
            assertEquals(2, api.categories(auth.credential(origin), limit = 1).size)
            assertEquals(2, api.tags(auth.credential(origin), limit = 1).size)
        }
        compose.waitUntil(30000) { compose.onAllNodesWithText("Вы вошли: ${auth.profile.email}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Обновить категории и теги").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Категория: Home / Food").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Тег: <svg onload=alert(1)>").assertExists()
        compose.onNodeWithText("Тег: Shared").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Категория: Home / Food").assertExists()
        fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
        fun field(label: String, value: String) {
            compose.onNodeWithText(label).performScrollTo().performTextClearance()
            compose.onNodeWithText(label).performTextInput(value)
        }
        click("Обновить счета")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Для операции: Split cash · KWD").fetchSemanticsNodes().isNotEmpty() }
        field("Сумма операции", "12.345")
        field("Примечание операции", "Split expense")
        click("Категория части 1: Home / Food")
        click("Добавить часть операции")
        field("Сумма части 1", "5.001")
        field("Сумма части 2", "7.344")
        click("Категория части 2: Home")
        compose.onNodeWithContentDescription("Выбрать тег Shared").performScrollTo().performClick()
        click("Сохранить операцию")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Расход · −12,345 KWD · Split expense").fetchSemanticsNodes().isNotEmpty() }
        val created = runBlocking { api.transactions(auth.credential(origin)).single { it.kind == "expense" } }
        assertEquals(listOf("5001", "7344"), created.allocations.map { it.amount_minor }.sortedBy { it.toInt() })
        assertEquals(1, created.tag_ids.size)
        val categoryById = runBlocking { api.categories(auth.credential(origin)).associateBy { it.id } }
        val tagById = runBlocking { api.tags(auth.credential(origin)).associateBy { it.id } }
        assertEquals(setOf("Home", "Food"), created.allocations.map { categoryById[it.category_id]!!.name }.toSet())
        assertEquals("Shared", tagById[created.tag_ids.single()]!!.name)
        click("Изменить операцию Split expense")
        field("Сумма части 1", "6.001")
        click("Сохранить изменения операции")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Сумма частей должна точно совпадать с суммой операции.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(created.version, runBlocking { api.transactions(auth.credential(origin)).single { it.kind == "expense" }.version })
        field("Сумма части 2", "6.344")
        field("Примечание операции", "Split edited")
        click("Сохранить изменения операции")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Расход · −12,345 KWD · Split edited").fetchSemanticsNodes().isNotEmpty() }
        val edited = runBlocking { api.transactions(auth.credential(origin)).single { it.kind == "expense" } }
        assertEquals(created.allocations.map { it.id }.toSet(), edited.allocations.map { it.id }.toSet())
        assertEquals(created.tag_ids, edited.tag_ids)
        assertEquals(created.allocations.associate { it.id to it.category_id }, edited.allocations.associate { it.id to it.category_id })
        assertEquals("87655", runBlocking { api.accounts(auth.credential(origin)).single().posted_balance_minor })
        val foreign = runBlocking { decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("catalog-foreign-${UUID.randomUUID()}@example.test", "synthetic catalog password", "Foreign catalog", "bearer", "UTC")))) }
        runBlocking {
            try {
                api.request("/workspaces/${auth.workspace.id}/categories?limit=1", session = foreign.credential(origin))
                fail("Foreign catalog must be denied")
            } catch (e: ApiFailure) { assertEquals(404, e.status) }
            try {
                api.request("/workspaces/${auth.workspace.id}/tags?limit=1", session = foreign.credential(origin))
                fail("Foreign tags must be denied")
            } catch (e: ApiFailure) { assertEquals(404, e.status) }
            api.request("/auth/logout", "POST", session = foreign.credential(origin))
        }
    }
}
