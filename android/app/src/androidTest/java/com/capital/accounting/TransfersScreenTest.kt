package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.ConnectionSettings
import com.capital.accounting.finance.CommandRunner
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
class TransfersScreenTest {
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private lateinit var source: Account
    private lateinit var same: Account
    private lateinit var target: Account
    private lateinit var category: Category
    private lateinit var tag: Tag
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue("Real API required", InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin)
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("transfers-ui-${UUID.randomUUID()}@example.test", "synthetic transfer password", "Transfer UI", "bearer", "UTC"))))
            suspend fun create(path: String, body: String) = decodeResponse<MutationResult>(api.request("/workspaces/${auth.workspace.id}/$path", "POST", body, auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id))
            suspend fun account(name: String, currency: String, minor: String) = create("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), name, "cash", currency, "2026-10-06T08:00:00Z", "UTC", minor))).accounts.single()
            source = account("Transfer source", "KWD", "100000")
            same = account("Transfer same", "KWD", "20000")
            target = account("Transfer target", "USD", "3000")
            category = create("categories", ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), "Bank fee", null))).categories.single()
            tag = create("tags", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "Transfer tag"))).tags.single()
            val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
            app.database.settings().save(ConnectionSettings(origin = origin))
            app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> {
            if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin))
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realSameAndCrossCurrencyTransfersFeeAndLostReceiptReplay() {
        fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
        fun field(label: String, value: String) {
            compose.onNodeWithText(label).performScrollTo().performTextClearance()
            compose.onNodeWithText(label).performTextInput(value)
        }
        fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        fun waitTransfer(note: String) = compose.waitUntil(30000) {
            compose.onAllNodes(hasText("Перевод ·", substring = true) and hasText(note, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        fun accounts() = runBlocking { api.accounts(auth.credential(origin)).associateBy { it.id } }
        fun history() = runBlocking { api.transactions(auth.credential(origin)) }
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета")
        waitFor("Списать со счета: Transfer source · KWD")
        click("Обновить категории и теги")
        waitFor("Тег: Transfer tag")
        click("Списать со счета: Transfer source · KWD")
        click("Зачислить на счет: Transfer same · KWD")
        field("Сумма списания перевода", "10.000")
        field("Примечание перевода", "Same transfer")
        field("Дата перевода (YYYY-MM-DDTHH:MM:SS)", "2026-10-06T11:05:00")
        field("Часовой пояс перевода", "Europe/Nicosia")
        compose.onNodeWithText("Сумма зачисления перевода").assertIsNotEnabled()
        click("Сохранить перевод")
        waitTransfer("Same transfer")
        val sameTransfer = history().single { it.note == "Same transfer" }
        assertEquals(setOf("-10000", "10000"), sameTransfer.entries.map { it.amount_minor }.toSet())
        assertEquals(Rate("1", "1"), sameTransfer.rate)
        assertEquals("90000", accounts()[source.id]!!.posted_balance_minor)
        assertEquals("30000", accounts()[same.id]!!.posted_balance_minor)
        val beforeCross = accounts()[source.id]!!
        click("Зачислить на счет: Transfer target · USD")
        field("Сумма списания перевода", "12.345")
        field("Сумма зачисления перевода", "10.01")
        field("Примечание перевода", "Cross transfer")
        compose.onNodeWithContentDescription("Тег перевода Transfer tag").performScrollTo().performClick()
        click("Добавить комиссию перевода")
        click("Счет комиссии: Transfer source · KWD")
        field("Сумма комиссии перевода", "0.501")
        field("Примечание комиссии перевода", "Cross fee")
        click("Добавить часть комиссии")
        field("Сумма части комиссии 1", "0.200")
        field("Сумма части комиссии 2", "0.300")
        click("Категория комиссии 1: Bank fee")
        compose.onNodeWithContentDescription("Тег комиссии Transfer tag").performScrollTo().performClick()
        click("Сохранить перевод")
        waitFor("Проверьте разные счета, положительные суммы, точную сумму частей комиссии, дату и длину текста.")
        assertEquals(1, history().count { it.kind == "transfer" })
        field("Сумма части комиссии 2", "0.301")
        click("Сохранить перевод")
        waitTransfer("Cross transfer")
        val cross = history().single { it.note == "Cross transfer" }
        val fee = history().single { it.id == cross.fee_transaction_id }
        assertEquals(setOf("-12345", "1001"), cross.entries.map { it.amount_minor }.toSet())
        assertEquals(Rate("2002", "2469"), cross.rate)
        assertEquals(listOf(tag.id), cross.tag_ids)
        assertEquals(cross.id, fee.parent_transaction_id)
        assertEquals("expense", fee.kind)
        assertEquals("-501", fee.entries.single().amount_minor)
        assertEquals(listOf("200", "301"), fee.allocations.map { it.amount_minor }.sortedBy { it.toInt() })
        assertEquals(category.id, fee.allocations.single { it.amount_minor == "200" }.category_id)
        assertEquals(listOf(tag.id), fee.tag_ids)
        assertEquals("77154", accounts()[source.id]!!.posted_balance_minor)
        assertEquals("4001", accounts()[target.id]!!.posted_balance_minor)
        assertEquals((beforeCross.balance_version.toLong() + 1).toString(), accounts()[source.id]!!.balance_version)
        assertEquals("2026-10-06T08:05:00.000000Z", cross.occurred_at)
        assertEquals("Europe/Nicosia", fee.occurred_timezone)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
        val replayFee = FeeInput(UUID.randomUUID().toString(), source.id, "101", listOf(AllocationInput(UUID.randomUUID().toString(), category.id, "40"), AllocationInput(UUID.randomUUID().toString(), null, "61")), "Replay fee", listOf(tag.id))
        val replay = TransferCreate(UUID.randomUUID().toString(), "2026-10-06T09:15:30.123456Z", "Europe/Nicosia", "Replay transfer", "Replay recipient", listOf(tag.id), "transfer", source.id, target.id, "1000", "334", Rate("167", "50"), replayFee)
        val body = ApiClient.json.encodeToString(replay)
        val command = runBlocking { CommandRunner(app.database.commands()).prepare(auth.credential(origin), "transactions", "POST", body) }
        runBlocking { api.request(command.path, command.method, command.body, auth.credential(origin), command.commandId, command.generationId) }
        val appliedCount = history().size
        compose.activityRule.scenario.recreate()
        click("Обновить счета")
        waitFor("Сохраненная команда: ${command.commandId}")
        compose.onNodeWithText("Сумма списания перевода").assertTextContains("1,000")
        compose.onNodeWithText("Сумма зачисления перевода").assertTextContains("3,34")
        compose.onNodeWithText("Примечание комиссии перевода").assertTextContains("Replay fee")
        compose.onNodeWithText("Дата перевода (YYYY-MM-DDTHH:MM:SS)").assertTextContains("2026-10-06T12:15:30")
        compose.onNodeWithText("Выйти").assertIsNotEnabled()
        assertEquals(body, runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!!.body })
        click("Повторить сохраненную команду")
        waitTransfer("Replay transfer")
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        assertEquals(appliedCount, history().size)
        assertEquals(3, history().count { it.kind == "transfer" })
        assertEquals(2, history().count { it.kind == "expense" })
        assertEquals("76053", accounts()[source.id]!!.posted_balance_minor)
        assertEquals("4335", accounts()[target.id]!!.posted_balance_minor)
        assertEquals("2026-10-06T09:15:30.123456Z", history().single { it.id == replay.id }.occurred_at)
        assertEquals(replayFee.allocations.map { it.id }.toSet(), history().single { it.id == replayFee.id }.allocations.map { it.id }.toSet())
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("76,053 KWD").assertExists()
        click("Списать со счета: Transfer source · KWD")
        val latest = accounts()[source.id]!!
        runBlocking { api.request("/workspaces/${auth.workspace.id}/accounts/${source.id}", "PUT", ApiClient.json.encodeToString(AccountUpdate(latest.version, latest.name, latest.type, true)), auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id) }
        click("Обновить счета")
        waitFor("Transfer source · В архиве")
        compose.onNodeWithText("Сохранить перевод").assertIsNotEnabled()
    }
}
