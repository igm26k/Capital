package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.*
import com.capital.accounting.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QueueRecoveryScreenTest {
    private fun id() = UUID.randomUUID().toString()
    private lateinit var api: ApiClient
    private lateinit var auth: BearerAuth
    private lateinit var issuer: BearerAuth
    private lateinit var root: OutboxCommand
    private lateinit var child: OutboxCommand
    private lateinit var independent: OutboxCommand
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    @get:Rule(order = 0) val name = TestName()
    @get:Rule(order = 1) val setup = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            assumeTrue(InstrumentationRegistry.getArguments().getString("api_origin") != null)
            api = ApiClient(origin); val email = "recovery-screen-${id()}@example.test"; val password = "synthetic recovery screen password"
            auth = decodeResponse(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register(email, password, "Recovery screen", "bearer", "UTC"))))
            issuer = auth; val saved = auth.credential(origin)
            IdentityStore(app.database).save(auth, origin)
            if (name.methodName == "unknownOldIssuerResultCannotBeDismissedOrRebound") {
                SnapshotBootstrap(app.database).load(saved)
                root = OfflineTagStore(app.database).enqueue(saved, ApiClient.json.encodeToString(TagCreate(id(), "Unknown proposed")))
                val sending = OutboxLifecycle(app.database).claim(saved)!!
                OutboxLifecycle(app.database).failed(sending, "uncertain")
                auth = decodeResponse(api.request("/auth/login", "POST", ApiClient.json.encodeToString(Login(email, password, "Recovery second session", "bearer"))))
                IdentityStore(app.database).save(auth, origin)
                assertEquals(QueueDrainStatus.BLOCKED, QueueCoordinator(app.database).drain(auth.credential(origin)).status)
            } else if (name.methodName == "balanceConflictDismissalNeverReappliesFixedTargetOrChangesConfirmedMoney") {
                val cash = decodeResponse<MutationResult>(api.request("/workspaces/${saved.workspaceId}/accounts", "POST", ApiClient.json.encodeToString(AccountCreate(id(), "Recovery cash", "cash", "KWD", "2026-10-13T12:00:00Z", "UTC", "100000")), saved, id(), saved.generationId)).accounts.single()
                SnapshotBootstrap(app.database).load(saved)
                val adjustment = AdjustmentCreate(id(), "1", "90000", "Count", "Proposed adjustment", "UTC")
                root = OfflineAdjustmentStore(app.database).create(saved, ApiClient.json.encodeToString(adjustment), cash.id)
                child = OfflineAdjustmentStore(app.database).replace(saved, ApiClient.json.encodeToString(AdjustmentReplace("adjustment", "1", "Dependent adjustment note")), adjustment.id)
                independent = OfflineTagStore(app.database).enqueue(saved, ApiClient.json.encodeToString(TagCreate(id(), "Independent")))
                api.request("/workspaces/${saved.workspaceId}/transactions", "POST", ApiClient.json.encodeToString(IncomeCreate(id(), "2026-10-13T12:01:00Z", "UTC", "Other client income", "", emptyList(), "income", cash.id, "1000", listOf(AllocationInput(id(), null, "1000")))), saved, id(), saved.generationId)
                assertEquals(QueueDrainStatus.BLOCKED, QueueCoordinator(app.database).drain(saved).status)
            } else {
                val tag = decodeResponse<MutationResult>(api.request("/workspaces/${saved.workspaceId}/tags", "POST", ApiClient.json.encodeToString(TagCreate(id(), "Original")), saved, id(), saved.generationId)).tags.single()
                SnapshotBootstrap(app.database).load(saved)
                root = OfflineTagStore(app.database).enqueue(saved, ApiClient.json.encodeToString(TagUpdate("1", "Proposed", false)), tag.id)
                child = OfflineTagStore(app.database).enqueue(saved, ApiClient.json.encodeToString(TagUpdate("2", "Dependent", false)), tag.id)
                independent = OfflineTagStore(app.database).enqueue(saved, ApiClient.json.encodeToString(TagCreate(id(), "Independent")))
                api.request("/workspaces/${saved.workspaceId}/tags/${tag.id}", "PUT", ApiClient.json.encodeToString(TagUpdate("1", "Other client", false)), saved, id(), saved.generationId)
                assertEquals(QueueDrainStatus.BLOCKED, QueueCoordinator(app.database).drain(saved).status)
            }
            app.database.settings().save(ConnectionSettings(origin = origin)); app.credentialVault.save(auth.credential(origin))
        }
        override fun after() = runBlocking<Unit> {
            if (::auth.isInitialized) api.request("/auth/logout", "POST", session = auth.credential(origin))
            if (::issuer.isInitialized && issuer.session.id != auth.session.id) api.request("/auth/logout", "POST", session = issuer.credential(origin))
            app.credentialVault.clear()
        }
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) = compose.waitUntil(60000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String) {
        compose.waitUntil(60000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    @Test fun rejectedBranchRequiresPreviewAndExplicitConfirmationBeforeIndependentSync() {
        waitFor("Вы вошли: ${auth.profile.email}"); waitFor("Сохраненные изменения требуют проверки: 2.")
        click("Разобрать сохранённые изменения"); waitFor("Состояние: Отклонена")
        compose.onNodeWithText("Предложено: Proposed").assertExists()
        compose.onNodeWithText("Текущее значение: Other client").assertExists()
        compose.onNodeWithText("Наблюдаемая версия: 1").assertExists()
        compose.onNodeWithText("Версия в показанных данных: 2").assertExists()
        click("Сверить результат с сервером"); waitFor("Результат сверён. Новая команда не отправлялась.")
        click("Подготовить исключение команды"); waitFor("Исключить команд из отправки: 2")
        click("Отменить исключение")
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        runBlocking { assertEquals(listOf("rejected", "queued", "queued"), app.database.outbox().commands(scope.key).map { it.state }) }
        click("Подготовить исключение команды"); click("Подтвердить исключение из отправки")
        waitFor("Команд ожидает обработки: 1")
        runBlocking {
            val commands = app.database.outbox().commands(scope.key)
            assertEquals(root.copy(state = "dismissed"), commands[0]); assertEquals(child.copy(state = "dismissed"), commands[1]); assertEquals(independent, commands[2])
            assertNull(app.database.receipts().get(independent.position)); assertNotNull(app.database.outcomes().get(root.position))
            assertEquals(setOf("Other client"), api.tags(saved).map { it.name }.toSet())
        }
        click("Закрыть проверку очереди"); click("Синхронизировать изменения"); waitFor("Синхронизация завершена.")
        runBlocking {
            assertEquals(setOf("Other client", "Independent"), api.tags(saved).map { it.name }.toSet())
            assertEquals(independent.copy(state = "applied"), app.database.outbox().commands(scope.key).last())
            assertEquals("3", SnapshotStore(app.database).read(scope)!!.head.sequence)
        }
    }
    @Test fun balanceConflictDismissalNeverReappliesFixedTargetOrChangesConfirmedMoney() {
        waitFor("Вы вошли: ${auth.profile.email}"); waitFor("Сохраненные изменения требуют проверки: 2.")
        click("Разобрать сохранённые изменения"); waitFor("Состояние: Отклонена")
        compose.onNodeWithText("Изменение: Корректировка остатка · Создание").assertExists()
        compose.onNodeWithText("Предложенный остаток: 90,000 KWD").assertExists()
        compose.onNodeWithText("Текущий остаток: 101,000 KWD").assertExists()
        compose.onNodeWithText("Наблюдаемая версия остатка: 1").assertExists()
        compose.onNodeWithText("Версия остатка в показанных данных: 2").assertExists()
        click("Подготовить исключение команды"); waitFor("Исключить команд из отправки: 2")
        click("Подтвердить исключение из отправки"); waitFor("Команд ожидает обработки: 1")
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        runBlocking {
            assertEquals("101000", api.accounts(saved).single().posted_balance_minor)
            assertTrue(api.transactions(saved).none { it.kind == "adjustment" })
            assertEquals(root.copy(state = "dismissed"), app.database.outbox().commands(scope.key).first())
        }
        click("Закрыть проверку очереди"); click("Синхронизировать изменения"); waitFor("Синхронизация завершена.")
        runBlocking {
            assertEquals("101000", api.accounts(saved).single().posted_balance_minor)
            assertTrue(api.transactions(saved).none { it.kind == "adjustment" })
            assertEquals("3", SnapshotStore(app.database).read(scope)!!.head.sequence)
            assertEquals(independent.copy(state = "applied"), app.database.outbox().commands(scope.key).last())
        }
    }
    @Test fun unknownOldIssuerResultCannotBeDismissedOrRebound() {
        waitFor("Вы вошли: ${auth.profile.email}"); click("Разобрать сохранённые изменения")
        waitFor("Состояние: Результат неизвестен")
        compose.onNodeWithText("Подготовить исключение команды").assertIsNotEnabled()
        click("Сверить результат с сервером"); waitFor("Результат неизвестен. Исходная команда сохранена; исключение недоступно.")
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        runBlocking {
            assertEquals(listOf(root.copy(state = "uncertain")), app.database.outbox().commands(scope.key))
            assertNull(app.database.receipts().get(root.position)); assertTrue(api.tags(saved).isEmpty())
            assertEquals("0", SnapshotStore(app.database).read(scope)!!.head.sequence)
        }
    }
}
