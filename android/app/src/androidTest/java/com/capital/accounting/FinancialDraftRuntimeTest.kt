package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.data.ConnectionSettings
import com.capital.accounting.finance.FinancialDrafts
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
import java.security.MessageDigest
import java.util.UUID

/** Two native phases separated by an actual host force-stop, not Activity recreation. */
@RunWith(AndroidJUnit4::class)
class FinancialDraftRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val origin get() = args.getString("api_origin")!!
    private val phase get() = args.getString("draft_phase")!!
    private val api get() = ApiClient(origin)
    private lateinit var session: StoredSession
    private lateinit var auth: BearerAuth
    private lateinit var expense: Transaction
    private val proof get() = File(app.filesDir, "financial-draft-proof.json")
    private fun snapshot(): JSONObject {
        val hash = MessageDigest.getInstance("SHA-256").digest(ApiClient.json.encodeToString(listOf(origin, session.ownerId, session.workspaceId)).toByteArray()).joinToString("") { "%02x".format(it) }
        return JSONObject(File(app.noBackupFilesDir, "financial-drafts/$hash.json").readText()).getJSONObject("fields")
    }
    private suspend fun mutate(path: String, body: String, method: String = "POST") = decodeResponse<MutationResult>(api.request("/workspaces/${session.workspaceId}/$path", method, body, session, UUID.randomUUID().toString(), session.generationId))
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Host draft harness required", args.getString("draft_phase") in listOf("prepare", "verify"))
            if (phase == "prepare") {
                auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("draft-ui-${UUID.randomUUID()}@example.test", "synthetic draft password", "Draft runtime", "bearer", "UTC"))))
                session = auth.credential(origin)
                val cash = mutate("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Draft cash", "cash", "KWD", "2026-10-06T08:00:00Z", "UTC", "100000"))).accounts.single()
                mutate("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Draft USD", "cash", "USD", "2026-10-06T08:00:00Z", "UTC", "3000")))
                expense = mutate("transactions", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T08:10:00.123456Z", "Europe/Nicosia", "Draft expense", "Original recipient", emptyList(), "expense", cash.id, "12345", listOf(AllocationInput(UUID.randomUUID().toString(), null, "5001"), AllocationInput(UUID.randomUUID().toString(), null, "7344"))))).transactions.single()
                app.database.settings().save(ConnectionSettings(origin = origin))
                app.credentialVault.save(session)
            } else {
                session = (app.credentialVault.load(origin) as VaultRead.Available).session
                auth = decodeResponse(api.request("/auth/session", session = session))
                expense = api.transaction(session, proof.readText().let { JSONObject(it).getString("expense_id") })
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
    private fun saved() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("Локальные черновики сохранены. Отправка операций выполняется отдельно.").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun nativeDraftsSurviveActualProcessStopWithoutSendingOrAdoptingForeignVersions() {
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета"); waitFor("Для операции: Draft cash · KWD")
        click("Обновить историю"); waitFor("Детали операции ${expense.note}")
        if (phase == "prepare") {
            field("Название счета", "Unsent account")
            click("KWD")
            field("Начальный остаток", "invalid balance")
            field("Название категории", "Unsent category")
            click("Изменить операцию Draft expense")
            field("Примечание операции", "Unsent expense <svg onload=alert(1)>")
            field("Получатель операции", "Unsent recipient")
            field("Сумма списания перевода", "10.001")
            field("Сумма зачисления перевода", "3.01")
            field("Примечание перевода", "Unsent transfer")
            click("Добавить комиссию перевода")
            field("Сумма комиссии перевода", "0.501")
            field("Примечание комиссии перевода", "Unsent fee")
            click("Создать возврат расхода Draft expense")
            field("Сумма части возврата 1", "0.001")
            field("Примечание возврата", "Unsent refund")
            click("Корректировать счет Draft cash · KWD")
            field("Фактический остаток", "-1.234")
            field("Причина корректировки", "Unsent reason")
            saved()
            val before = snapshot()
            assertEquals("\"1\"", before.getString("transaction.editingVersion"))
            assertEquals("\"1\"", before.getString("refund.parentVersion"))
            // A different client updates the record after the draft has been durably saved.
            val updated = runBlocking { mutate("transactions/${expense.id}", ApiClient.json.encodeToString(ExpenseReplace(expense.occurred_at, expense.occurred_timezone, "Foreign version2", expense.payee, expense.tag_ids, "expense", expense.entries.single().account_id, "12345", expense.allocations.map { AllocationInput(it.id, it.category_id, it.amount_minor) }, expense.version, null)), "PUT") }.transactions.single()
            assertEquals("2", updated.version)
            proof.writeText(JSONObject().put("stage", "prepared").put("email", auth.profile.email).put("owner_id", session.ownerId).put("workspace_id", session.workspaceId).put("expense_id", expense.id).put("snapshot", before).toString())
        } else {
            saved()
            val before = JSONObject(proof.readText()).getJSONObject("snapshot")
            val after = snapshot()
            assertEquals(before.keys().asSequence().toSet(), after.keys().asSequence().toSet())
            before.keys().forEach { key -> assertEquals("Restored field $key", before.getString(key), after.getString(key)) }
            listOf("Название счета" to "Unsent account", "Начальный остаток" to "invalid balance", "Название категории" to "Unsent category", "Примечание операции" to "Unsent expense <svg onload=alert(1)>", "Получатель операции" to "Unsent recipient", "Сумма списания перевода" to "10.001", "Сумма зачисления перевода" to "3.01", "Примечание перевода" to "Unsent transfer", "Сумма комиссии перевода" to "0.501", "Примечание комиссии перевода" to "Unsent fee", "Сумма части возврата 1" to "0.001", "Примечание возврата" to "Unsent refund", "Фактический остаток" to "-1.234", "Причина корректировки" to "Unsent reason").forEach { (label, value) -> compose.onNodeWithText(label).assertTextContains(value) }
            compose.onNodeWithText("Версия операции на сервере: 2; версия черновика: 1").assertExists()
            compose.onNodeWithText("Использовать обновленную версию операции").assertExists()
            assertEquals("2026-10-06T08:10:00.123456Z", runBlocking { api.transaction(session, expense.id) }.occurred_at)
            // A real logout/login must change draft scope and restore it only for its owner.
            val foreignEmail = "draft-foreign-${UUID.randomUUID()}@example.test"
            val foreign = runBlocking { decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register(foreignEmail, "synthetic draft password", "Foreign drafts", "bearer", "UTC")))) }
            runBlocking { api.request("/auth/logout", "POST", session = foreign.credential(origin)) }
            click("Выйти"); waitFor("Войти")
            field("Email", foreignEmail); field("Пароль", "synthetic draft password"); click("Войти")
            waitFor("Вы вошли: $foreignEmail"); waitFor("Новая операция"); saved()
            compose.onNodeWithText("Примечание операции").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            compose.onNodeWithText("Название счета").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            field("Примечание операции", "Foreign local draft"); saved()
            click("Выйти"); waitFor("Войти")
            field("Email", auth.profile.email); field("Пароль", "synthetic draft password"); click("Войти")
            waitFor("Вы вошли: ${auth.profile.email}"); waitFor("Изменение операции"); saved()
            compose.onNodeWithText("Примечание операции").assertTextContains("Unsent expense <svg onload=alert(1)>")
            val fresh = runBlocking { (app.credentialVault.load(origin) as VaultRead.Available).session }
            assertNotEquals(session.sessionId, fresh.sessionId)
            val disk = runBlocking { FinancialDrafts(app).open(origin, fresh.ownerId, fresh.workspaceId) }
            assertEquals(before.getString("transaction.parts"), disk.read("transaction.parts"))
            proof.writeText(JSONObject(proof.readText()).put("stage", "verified").put("process_restart", true).put("scope_isolation", true).put("stale_version_retained", true).toString())
            click("Выйти"); waitFor("Войти")
        }
        assertNull(runBlocking { app.database.commands().get(origin, session.ownerId, session.workspaceId) })
        if (phase == "prepare") assertEquals(3, runBlocking { api.transactions(session) }.size)
    }
}
