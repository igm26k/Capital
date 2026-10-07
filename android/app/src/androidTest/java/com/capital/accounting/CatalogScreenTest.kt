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
