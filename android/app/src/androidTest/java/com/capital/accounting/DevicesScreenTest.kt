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
class DevicesScreenTest {
    private lateinit var api: ApiClient
    private lateinit var primary: BearerAuth
    private lateinit var other: BearerAuth
    private lateinit var foreign: BearerAuth
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            val email = "devices-${UUID.randomUUID()}@example.test"
            val password = " synthetic device password "
            primary = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register(email, password, "Primary device", "bearer", "UTC"))))
            other = decodeResponse(api.request("/auth/login", "POST", ApiClient.json.encodeToString(Login(email, password, "Other device", "bearer"))))
            foreign = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("foreign-${UUID.randomUUID()}@example.test", password, "Foreign device", "bearer", "UTC"))))
            val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(primary.credential(origin))
        }
        override fun after() = runBlocking<Unit> {
            if (::foreign.isInitialized) api.request("/auth/logout", "POST", session = foreign.credential(origin))
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun devicesAreScopedAndOtherAndCurrentRevokeAreReal() {
        runBlocking {
            val own = api.sessions(primary.credential(origin), limit = 1)
            assertEquals(2, own.size)
            assertEquals(1, own.count { it.is_current })
            assertFalse(own.any { it.id == foreign.session.id })
            for ((caller, target) in listOf(primary to foreign, foreign to other)) {
                try {
                    api.request("/sessions/${target.session.id}", "DELETE", session = caller.credential(origin))
                    fail("Foreign session revoke must be denied")
                } catch (e: ApiFailure) { assertEquals(404, e.status) }
            }
        }
        compose.waitUntil(30000) { compose.onAllNodesWithText("Вы вошли: ${primary.profile.email}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Обновить устройства").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Отключить устройство Other device").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Отключить устройство Other device").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Устройство отключено").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            try { api.request("/auth/session", session = other.credential(origin)); fail("Other credential still active") }
            catch (e: ApiFailure) { assertEquals(401, e.status) }
            api.request("/auth/session", session = primary.credential(origin))
            assertTrue(decodeResponse<Acknowledgement>(api.request("/sessions/${other.session.id}", "DELETE", session = primary.credential(origin))).ok)
        }
        compose.onNodeWithText("Отключить устройство Other device").assertDoesNotExist()
        compose.onNodeWithText("Завершить текущую сессию").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Вы вышли из аккаунта").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            try { api.request("/auth/session", session = primary.credential(origin)); fail("Current credential still active") }
            catch (e: ApiFailure) { assertEquals(401, e.status) }
        }
    }
}
