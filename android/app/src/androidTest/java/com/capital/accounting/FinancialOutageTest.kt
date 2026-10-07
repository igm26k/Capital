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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Host harness stops the real API after ready and before the native Create click. */
@RunWith(AndroidJUnit4::class)
class FinancialOutageTest {
    private lateinit var auth: BearerAuth
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Host outage harness required", InstrumentationRegistry.getArguments().getString("outage_harness") == "true")
            File(app.filesDir, "financial-outage-gate").delete()
            File(app.filesDir, "financial-outage-proof.json").delete()
            auth = decodeResponse(ApiClient(origin).request("/auth/register", "POST", ApiClient.json.encodeToString(Register("outage-${UUID.randomUUID()}@example.test", "synthetic outage password", "Financial outage", "bearer", "UTC"))))
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun nativeCreatePersistsExactCommandWhenActualApiIsStopped() {
        compose.waitUntil(30000) { compose.onAllNodesWithText("Вы вошли: ${auth.profile.email}").fetchSemanticsNodes().isNotEmpty() }
        fun field(label: String, value: String) {
            compose.onNodeWithText(label).performScrollTo().performTextClearance()
            compose.onNodeWithText(label).performTextInput(value)
        }
        field("Название счета", "Outage pocket")
        compose.onNodeWithText("KWD").performScrollTo().performClick()
        field("Начальный остаток", "123.456")
        field("Дата открытия (YYYY-MM-DDTHH:MM:SS)", "2026-10-06T11:00:00")
        field("Часовой пояс открытия", "Europe/Nicosia")
        val proof = JSONObject().put("email", auth.profile.email).put("owner_id", auth.profile.id)
            .put("workspace_id", auth.workspace.id).put("session_id", auth.session.id).put("stage", "ready")
        val output = File(app.filesDir, "financial-outage-proof.json")
        output.writeText(proof.toString())
        compose.waitUntil(60000) { File(app.filesDir, "financial-outage-gate").exists() }
        compose.onNodeWithText("Создать счет").performScrollTo().performClick()
        compose.waitUntil(45000) { compose.onAllNodesWithText("Результат не подтвержден. Повторите ту же команду.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Выйти").assertIsNotEnabled()
        compose.onNodeWithText("Создать счет").assertIsNotEnabled()
        val command = runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!! }
        val draft = decodeResponse<AccountCreate>(command.body)
        assertEquals("pending", command.state)
        assertEquals("123456", draft.opening_balance_minor)
        assertEquals("2026-10-06T08:00:00.000Z", draft.opened_at)
        assertEquals("Europe/Nicosia", draft.occurred_timezone)
        proof.put("stage", "pending").put("command_id", command.commandId).put("account_id", draft.id)
        output.writeText(proof.toString())
    }
}
