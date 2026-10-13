package com.capital.accounting

import androidx.room.Room
import androidx.room.withTransaction
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OfflineAccountApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun exactOpeningProjectionRollbackReopenAndServerIdsNeverDuplicate() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-account-${id()}@example.test", "synthetic account password", "Offline accounts", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "offline-account-api.db"
        context.deleteDatabase(name); fun open() = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val body = ApiClient.json.encodeToString(AccountCreate(id(), "Exact account", "cash", "KWD", "2026-10-13T12:00:00.000000Z", "UTC", "123456"))
            try { db.withTransaction { OfflineAccountStore(db).create(saved, body); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertTrue(db.outbox().commands(scope.key).isEmpty())
            val positive = OfflineAccountStore(db).create(saved, body)
            val negative = OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(id(), "Negative account", "card", "JPY", "2026-10-13T12:00:00Z", "UTC", "-123")))
            val zero = OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(id(), "Zero account", "bank", "EUR", "2026-10-13T12:00:00Z", "UTC", "0")))
            val accountId = decodeResponse<AccountCreate>(body).id
            val edited = OfflineAccountStore(db).update(saved, ApiClient.json.encodeToString(AccountUpdate("1", "Renamed account", "card", false)), accountId)
            val archived = OfflineAccountStore(db).update(saved, ApiClient.json.encodeToString(AccountUpdate("2", "Renamed account", "card", true)), accountId)
            val projected = LocalProjectionStore(db).read(scope)!!
            assertTrue(projected.confirmed.items.isEmpty()); assertEquals(3, projected.visible.accounts.size); assertEquals(3, projected.visible.transactions.size)
            assertEquals("123456", projected.visible.accounts.first { it.currency == "KWD" }.posted_balance_minor)
            assertEquals("-123", projected.visible.accounts.first { it.currency == "JPY" }.posted_balance_minor)
            val localIds = projected.visible.transactions.map { it.id }.toSet()
            db.close(); db = open(); assertEquals(listOf(positive, negative, zero, edited, archived), db.outbox().commands(scope.key))
            assertEquals(projected, LocalProjectionStore(db).read(scope))
            val sending = OutboxLifecycle(db).claim(saved)!!
            val response = api.request(sending.path, sending.method, sending.body, saved, sending.actionId, sending.generation)
            val server = decodeResponse<MutationResult>(response)
            assertTrue(server.transactions.none { it.id in localIds })
            val wrong = server.copy(accounts = listOf(server.accounts.single().copy(posted_balance_minor = "123457", projected_balance_minor = "123457")))
            try { OutboxReceiptStore(db).applied(sending, ApiClient.json.encodeToString(wrong)); fail("Wrong opening accepted") } catch (_: IllegalArgumentException) { }
            assertNull(db.receipts().get(positive.position)); assertEquals("sending", db.outbox().commands(scope.key).first().state)
            OutboxReceiptStore(db).applied(sending, response)
            assertEquals(QueueDrainResult(4, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("5", mirror.head.sequence)
            assertEquals(3, mirror.finance().accounts.size); assertEquals(3, mirror.finance().transactions.size)
            assertTrue(mirror.finance().transactions.none { it.id in localIds })
            assertTrue(db.outbox().commands(scope.key).all { it.state == "applied" && db.outbox().overlays(it.position).isEmpty() })
            assertEquals("123456", mirror.finance().accounts.first { it.currency == "KWD" }.posted_balance_minor)
            assertEquals("3", mirror.finance().accounts.first { it.currency == "KWD" }.version)
            assertEquals("1", mirror.finance().accounts.first { it.currency == "KWD" }.balance_version)
            assertNotNull(mirror.finance().accounts.first { it.currency == "KWD" }.archived_at)
            assertEquals("-123", mirror.finance().accounts.first { it.currency == "JPY" }.posted_balance_minor)
            assertEquals("0", mirror.finance().accounts.first { it.currency == "EUR" }.posted_balance_minor)
            assertEquals(QueueDrainResult(0, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
        } finally { db.close(); context.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
}
