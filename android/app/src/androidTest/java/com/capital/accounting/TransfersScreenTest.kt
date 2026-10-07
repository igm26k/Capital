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
    @Test fun aggregateEditConflictFeeRemovalAdditionAndConfirmedDelete() {
        fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
        fun field(label: String, value: String) {
            compose.onNodeWithText(label).performScrollTo().performTextClearance()
            compose.onNodeWithText(label).performTextInput(value)
        }
        fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        fun history() = runBlocking { api.transactions(auth.credential(origin)) }
        fun account(id: String) = runBlocking { api.accounts(auth.credential(origin)).single { it.id == id } }
        fun waitTransfer(note: String) = compose.waitUntil(30000) { compose.onAllNodes(hasText("Перевод ·", substring = true) and hasText(note, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val initialFee = FeeInput(UUID.randomUUID().toString(), source.id, "501", listOf(AllocationInput(UUID.randomUUID().toString(), category.id, "200"), AllocationInput(UUID.randomUUID().toString(), null, "301")), "Original fee", listOf(tag.id))
        val initial = TransferCreate(UUID.randomUUID().toString(), "2026-10-06T08:20:30.123456Z", "Europe/Nicosia", "Original aggregate", "Recipient", listOf(tag.id), "transfer", source.id, target.id, "10000", "300", Rate("3", "10"), initialFee)
        runBlocking { api.request("/workspaces/${auth.workspace.id}/transactions", "POST", ApiClient.json.encodeToString(initial), auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id) }
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета")
        waitFor("Списать со счета: Transfer source · KWD")
        click("Обновить категории и теги")
        waitFor("Тег: Transfer tag")
        click("Обновить историю")
        waitTransfer("Original aggregate")
        val original = history().single { it.id == initial.id }
        val changingFeePart = history().single { it.id == initialFee.id }.allocations.indexOfFirst { it.id == initialFee.allocations.last().id } + 1
        click("Изменить перевод Original aggregate")
        compose.onNodeWithText("Списать со счета: Transfer target · USD").assertIsNotEnabled()
        compose.onNodeWithText("Зачислить на счет: Transfer same · KWD").assertIsNotEnabled()
        compose.onNodeWithText("Счет комиссии: Transfer target · USD").assertIsNotEnabled()
        field("Сумма списания перевода", "12.000")
        field("Сумма зачисления перевода", "4.00")
        field("Сумма комиссии перевода", "0.601")
        field("Сумма части комиссии $changingFeePart", "0.401")
        field("Примечание перевода", "Edited aggregate")
        val beforeEdit = account(source.id)
        click("Сохранить изменения перевода")
        waitTransfer("Edited aggregate")
        val edited = history().single { it.id == initial.id }
        val editedFee = history().single { it.id == initialFee.id }
        assertEquals("87399", account(source.id).posted_balance_minor)
        assertEquals("3400", account(target.id).posted_balance_minor)
        assertEquals((beforeEdit.balance_version.toLong() + 1).toString(), account(source.id).balance_version)
        assertEquals(original.entries.map { it.id }.toSet(), edited.entries.map { it.id }.toSet())
        assertEquals(initialFee.allocations.map { it.id }.toSet(), editedFee.allocations.map { it.id }.toSet())
        assertEquals(initial.occurred_at, edited.occurred_at)
        assertEquals(listOf(tag.id), edited.tag_ids)
        assertEquals(listOf(tag.id), editedFee.tag_ids)
        assertEquals(category.id, editedFee.allocations.single { it.amount_minor == "200" }.category_id)
        click("Изменить перевод Edited aggregate")
        field("Сумма списания перевода", "13.000")
        field("Сумма зачисления перевода", "4.50")
        field("Сумма комиссии перевода", "0.701")
        field("Сумма части комиссии $changingFeePart", "0.501")
        field("Примечание перевода", "Local aggregate")
        val remote = TransferReplace(initial.occurred_at, initial.occurred_timezone, "Server aggregate", initial.payee, initial.tag_ids, "transfer", source.id, target.id, "11000", "350", null, initialFee.copy(amount_minor = "601", allocations = editedFee.allocations.map { AllocationInput(it.id, it.category_id, it.amount_minor) }), edited.version, editedFee.version)
        runBlocking { api.request("/workspaces/${auth.workspace.id}/transactions/${initial.id}", "PUT", ApiClient.json.encodeToString(remote), auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id) }
        click("Сохранить изменения перевода")
        waitFor("Перевод на сервере: Server aggregate; версия 3")
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
        val rejected = runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!! }
        val draft = decodeResponse<TransferReplace>(rejected.body)
        assertEquals("rejected", rejected.state)
        assertEquals("2", draft.expected_version)
        assertEquals("2", draft.expected_fee_version)
        assertEquals(initialFee.id, draft.fee!!.id)
        assertEquals("Local aggregate", draft.note)
        compose.onNodeWithText("Выйти").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Примечание перевода").assertTextContains("Local aggregate")
        compose.onNodeWithText("Сумма комиссии перевода").assertTextContains("0,701")
        click("Обновить данные для новой команды")
        waitFor("Данные обновлены. Проверьте поля перед новой командой.")
        assertEquals("Server aggregate", history().single { it.id == initial.id }.note)
        assertEquals("3", history().single { it.id == initial.id }.version)
        click("Использовать обновленные версии перевода и комиссии")
        click("Сохранить изменения перевода")
        waitTransfer("Local aggregate")
        assertEquals("86299", account(source.id).posted_balance_minor)
        assertEquals("3450", account(target.id).posted_balance_minor)
        assertEquals("4", history().single { it.id == initial.id }.version)
        assertEquals("4", history().single { it.id == initialFee.id }.version)
        click("Изменить перевод Local aggregate")
        click("Убрать комиссию перевода")
        click("Сохранить изменения перевода")
        compose.waitUntil(30000) { history().none { it.id == initialFee.id } && compose.onAllNodesWithText("Изменить перевод Local aggregate").fetchSemanticsNodes().isNotEmpty() && runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) } == null }
        assertEquals("87000", account(source.id).posted_balance_minor)
        assertNull(history().single { it.id == initial.id }.fee_transaction_id)
        click("Изменить перевод Local aggregate")
        click("Добавить комиссию перевода")
        click("Счет комиссии: Transfer source · KWD")
        field("Сумма комиссии перевода", "0.101")
        field("Примечание комиссии перевода", "Replacement fee")
        click("Категория комиссии 1: Bank fee")
        click("Сохранить изменения перевода")
        compose.waitUntil(30000) { history().any { it.note == "Replacement fee" } && runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) } == null }
        val replacement = history().single { it.note == "Replacement fee" }
        assertNotEquals(initialFee.id, replacement.id)
        assertTrue(replacement.allocations.none { part -> initialFee.allocations.any { it.id == part.id } })
        assertEquals("86899", account(source.id).posted_balance_minor)
        assertEquals(initial.occurred_at, history().single { it.id == initial.id }.occurred_at)
        click("Удалить перевод Local aggregate")
        compose.onNodeWithText("Вместе с переводом будет удалена комиссия: Replacement fee; −0,101 KWD").assertExists()
        click("Отменить удаление перевода")
        assertEquals(5, history().size)
        click("Удалить перевод Local aggregate")
        click("Подтвердить удаление перевода")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Изменить перевод Local aggregate").fetchSemanticsNodes().isEmpty() }
        assertEquals(3, history().size)
        assertEquals("100000", account(source.id).posted_balance_minor)
        assertEquals("3000", account(target.id).posted_balance_minor)
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("100,000 KWD").assertExists()
    }

    @Test fun staleDeleteAndFeeRefundDependencyNeverRemoveFinancialMovements() {
        fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
        fun waitFor(text: String) = compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        fun history() = runBlocking { api.transactions(auth.credential(origin)) }
        fun balance(id: String) = runBlocking { api.accounts(auth.credential(origin)).single { it.id == id }.posted_balance_minor }
        val fee = FeeInput(UUID.randomUUID().toString(), source.id, "501", listOf(AllocationInput(UUID.randomUUID().toString(), category.id, "501")), "Dependency fee", emptyList())
        val transfer = TransferCreate(UUID.randomUUID().toString(), "2026-10-06T08:10:00Z", "UTC", "Dependency transfer", "", emptyList(), "transfer", source.id, target.id, "10000", "300", null, fee)
        runBlocking { api.request("/workspaces/${auth.workspace.id}/transactions", "POST", ApiClient.json.encodeToString(transfer), auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id) }
        waitFor("Вы вошли: ${auth.profile.email}")
        click("Обновить счета")
        waitFor("Списать со счета: Transfer source · KWD")
        click("Обновить историю")
        waitFor("Изменить перевод Dependency transfer")
        click("Удалить перевод Dependency transfer")
        val before = history().associateBy { it.id }
        val refund = RefundCreate(UUID.randomUUID().toString(), "2026-10-06T08:11:00Z", "UTC", "Fee refund", "", emptyList(), "refund", source.id, "100", fee.id, before[fee.id]!!.version, before[transfer.id]!!.version, listOf(RefundAllocationInput(UUID.randomUUID().toString(), fee.allocations.single().id, "100")))
        runBlocking { api.request("/workspaces/${auth.workspace.id}/transactions", "POST", ApiClient.json.encodeToString(refund), auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id) }
        click("Подтвердить удаление перевода")
        waitFor("Изменение отклонено: version_conflict. Обновите данные и проверьте черновик.")
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>()
        val stale = runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!! }
        val deletion = decodeResponse<TransactionDelete>(stale.body)
        assertEquals("1", deletion.expected_version)
        assertEquals(listOf(VersionExpectation(fee.id, "1")), deletion.related_versions)
        assertEquals(6, history().size)
        assertEquals("89599", balance(source.id))
        click("Обновить данные для новой команды")
        waitFor("Данные обновлены. Проверьте поля перед новой командой.")
        click("Удалить перевод Dependency transfer")
        compose.onNodeWithText("Зависимый возврат: Fee refund. Сначала удалите возврат.").assertExists()
        click("Подтвердить удаление перевода")
        waitFor("Изменение отклонено: dependent_transactions. Обновите данные и проверьте черновик.")
        assertEquals(6, history().size)
        assertEquals("89599", balance(source.id))
        click("Обновить данные для новой команды")
        waitFor("Данные обновлены. Проверьте поля перед новой командой.")
        click("Изменить перевод Dependency transfer")
        click("Убрать комиссию перевода")
        click("Сохранить изменения перевода")
        waitFor("Изменение отклонено: dependent_transactions. Обновите данные и проверьте черновик.")
        val blocked = decodeResponse<TransferReplace>(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id)!!.body })
        assertNull(blocked.fee)
        assertEquals("2", blocked.expected_fee_version)
        assertEquals(fee.id, history().single { it.id == transfer.id }.fee_transaction_id)
        assertEquals("89599", balance(source.id))
        click("Обновить данные для новой команды")
        waitFor("Данные обновлены. Проверьте поля перед новой командой.")
        click("Отменить изменение перевода")
        val latest = history().associateBy { it.id }
        runBlocking {
            api.request("/workspaces/${auth.workspace.id}/transactions/${refund.id}", "DELETE", ApiClient.json.encodeToString(TransactionDelete(latest[refund.id]!!.version, listOf(VersionExpectation(fee.id, latest[fee.id]!!.version), VersionExpectation(transfer.id, latest[transfer.id]!!.version)))), auth.credential(origin), UUID.randomUUID().toString(), auth.workspace.sync_generation_id)
        }
        click("Обновить историю")
        // Wait for the actual UI refresh, not just the already-updated API.
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Возврат ·", substring = true) and hasText("Fee refund", substring = true)).fetchSemanticsNodes().isEmpty() }
        click("Удалить перевод Dependency transfer")
        click("Подтвердить удаление перевода")
        compose.waitUntil(30000) { compose.onAllNodesWithText("Изменить перевод Dependency transfer").fetchSemanticsNodes().isEmpty() }
        assertEquals(3, history().size)
        assertEquals("100000", balance(source.id))
        assertEquals("3000", balance(target.id))
        assertNull(runBlocking { app.database.commands().get(origin, auth.profile.id, auth.workspace.id) })
    }

}
