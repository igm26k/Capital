package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import kotlinx.serialization.encodeToString
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.data.ConnectionSettings
import kotlinx.coroutines.runBlocking
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
class AuthScreenTest {
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() {
            assumeTrue("Real API origin required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            runBlocking {
                val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
                app.credentialVault.clear()
                app.database.settings().save(ConnectionSettings(origin = origin))
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun registrationRenewAndRecreationKeepSamePersistedBearerSession() {
        val email = "android-ui-${UUID.randomUUID()}@example.test"
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Email") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Email").performTextInput(email)
        compose.onNodeWithText("Пароль").performTextInput("  android ui 🥨 password  ")
        compose.onNodeWithText("Название устройства").performTextClearance()
        compose.onNodeWithText("Название устройства").performTextInput("<svg onload=alert(1)>")
        compose.onNodeWithText("Зарегистрироваться").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Вы вошли: $email").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Устройство: <svg onload=alert(1)>").assertExists()
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
        val registered = runBlocking { (app.credentialVault.load(origin) as VaultRead.Available).session }
        compose.onNodeWithText("Выйти").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Вы вышли из аккаунта").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Пароль").performScrollTo().performTextInput("  android ui 🥨 password  ")
        compose.onNodeWithText("Войти").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Вы вошли: $email").fetchSemanticsNodes().isNotEmpty() }
        val before = runBlocking { (app.credentialVault.load(origin) as VaultRead.Available).session }
        assertNotEquals(registered.sessionId, before.sessionId)
        compose.onNodeWithText("Продлить сессию").performScrollTo().performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Продлить сессию") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Вы вошли: $email").fetchSemanticsNodes().isNotEmpty() }
        val after = runBlocking { (app.credentialVault.load(origin) as VaultRead.Available).session }
        assertEquals(before.sessionId, after.sessionId)
        assertTrue("Recreation changed credential", before.token == after.token)
        assertFalse(after.logoutPending)
        val other = runBlocking {
            val api = ApiClient(origin)
            decodeResponse<BearerAuth>(api.request("/auth/login", "POST", ApiClient.json.encodeToString(Login(email, "  android ui 🥨 password  ", "Runtime device", "bearer"))))
        }
        runBlocking {
            val pages = ApiClient(origin).sessions(after, limit = 1)
            assertEquals(2, pages.size)
            assertEquals(1, pages.count { it.is_current })
            assertTrue(pages.any { it.id == other.session.id })
        }
        // Synthetic identity only; never emit token/password into acceptance artifacts.
        File(app.filesDir, "android-auth-proof.json").writeText(JSONObject().put("email", email).put("session_id", after.sessionId).put("revoke_session_id", other.session.id).toString())
    }
}
