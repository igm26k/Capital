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
class OfflineAdjustmentApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun pendingBalanceProofFixedTargetsMetadataAndReversalPreserveLaterMovements() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-adjust-${id()}@example.test", "synthetic adjustment password", "Adjustment", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "offline-adjustment-api.db"
        app.deleteDatabase(name); fun open() = Room.databaseBuilder(app, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val cash = id()
            val opening = OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(cash, "Cash", "cash", "KWD", "2026-10-13T12:00:00Z", "UTC", "100000")))
            try { db.withTransaction {
                val account = LocalProjectionStore(db).read(scope)!!.visible.accounts.single()
                OutboxStore(db).enqueue(saved, "accounts/$cash", "PUT", ApiClient.json.encodeToString(AccountUpdate("1", "Fake", "cash", false)), ledgerAccountBases(listOf(account)), listOf(opening.actionId),
                    listOf(OverlayEffect.upsert(SnapshotItemAccount("account", account.copy(version = "2", balance_version = "2")))))
                try { OfflineAdjustmentStore(db).create(saved, ApiClient.json.encodeToString(AdjustmentCreate(id(), "2", "200000", "Count", "", "UTC")), cash); fail("Metadata invented a balance version") } catch (_: IllegalArgumentException) { }
                throw IOException("Synthetic rollback")
            }; fail("Missing rollback") } catch (_: IOException) { }
            assertEquals(1, db.outbox().commands(scope.key).size)
            OfflineAccountStore(db).update(saved, ApiClient.json.encodeToString(AccountUpdate("1", "Renamed", "cash", false)), cash)
            val expense = ExpenseCreate(id(), "2026-10-13T12:05:00Z", "UTC", "Expense", "", emptyList(), "expense", cash, "12345", listOf(AllocationInput(id(), null, "12345")))
            OfflineBasicStore(db).create(saved, ApiClient.json.encodeToString(expense))
            val adjustment = AdjustmentCreate(id(), "2", "90000", "Count", "Adjustment", "UTC")
            val store = OfflineAdjustmentStore(db)
            for (invalid in listOf(adjustment.copy(expected_balance_version = "1"), adjustment.copy(target_balance_minor = "87655"), adjustment.copy(target_balance_minor = "01"),
                adjustment.copy(target_balance_minor = "-9000000000000000"), adjustment.copy(reason = ""), adjustment.copy(occurred_timezone = "Invalid/Zone"))) {
                try { store.create(saved, ApiClient.json.encodeToString(invalid), cash); fail("Invalid adjustment accepted") } catch (_: IllegalArgumentException) { }
            }
            try { db.withTransaction { store.create(saved, ApiClient.json.encodeToString(adjustment), cash); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertEquals(3, db.outbox().commands(scope.key).size)
            val first = store.create(saved, ApiClient.json.encodeToString(adjustment), cash)
            val metadata = store.replace(saved, ApiClient.json.encodeToString(AdjustmentReplace("adjustment", "1", "Metadata")), adjustment.id)
            assertEquals("3", LocalProjectionStore(db).read(scope)!!.visible.accounts.single().balance_version)
            OfflineBasicStore(db).create(saved, ApiClient.json.encodeToString(IncomeCreate(id(), "2026-10-13T12:10:00Z", "UTC", "Later income", "", emptyList(), "income", cash, "3333", listOf(AllocationInput(id(), null, "3333")))))
            store.delete(saved, ApiClient.json.encodeToString(TransactionDelete("2", emptyList())), adjustment.id)
            assertEquals("90988", LocalProjectionStore(db).read(scope)!!.visible.accounts.single().posted_balance_minor)
            val negative = AdjustmentCreate(id(), "5", "-1000", "Negative count", "Negative", "UTC")
            store.create(saved, ApiClient.json.encodeToString(negative), cash)
            val zero = AdjustmentCreate(id(), "6", "0", "Zero count", "Zero", "UTC")
            store.create(saved, ApiClient.json.encodeToString(zero), cash)
            store.delete(saved, ApiClient.json.encodeToString(TransactionDelete("1", emptyList())), zero.id)
            store.delete(saved, ApiClient.json.encodeToString(TransactionDelete("1", emptyList())), negative.id)
            OfflineAccountStore(db).update(saved, ApiClient.json.encodeToString(AccountUpdate("10", "Final", "card", false)), cash)
            val finalAdjustment = AdjustmentCreate(id(), "9", "100000", "Final count", "Final adjustment", "UTC")
            store.create(saved, ApiClient.json.encodeToString(finalAdjustment), cash)
            val queued = db.outbox().commands(scope.key); assertEquals(13, queued.size)
            val local = LocalProjectionStore(db).read(scope)!!.visible
            assertEquals("100000", local.accounts.single().posted_balance_minor); assertEquals("12", local.accounts.single().version); assertEquals("10", local.accounts.single().balance_version)
            assertEquals("9012", local.transactions.single { it.id == finalAdjustment.id }.entries.single().amount_minor)
            db.close(); db = open(); assertEquals(queued, db.outbox().commands(scope.key))
            assertEquals(QueueDrainResult(13, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("13", mirror.head.sequence)
            assertEquals("100000", mirror.finance().accounts.single().posted_balance_minor); assertEquals("10", mirror.finance().accounts.single().balance_version)
            val created = decodeResponse<MutationResult>(db.receipts().get(first.position)!!.payload).transactions.single()
            val edited = decodeResponse<MutationResult>(db.receipts().get(metadata.position)!!.payload).transactions.single()
            assertEquals("2345", created.entries.single().amount_minor); assertEquals(created.entries, edited.entries); assertEquals(created.occurred_at, edited.occurred_at); assertEquals(created.reason, edited.reason)
            assertEquals(queued.map { it.copy(state = "applied") }, db.outbox().commands(scope.key)); assertTrue(db.outbox().commands(scope.key).all { db.outbox().overlays(it.position).isEmpty() })
            val pending = OfflineAdjustmentStore(db).create(saved, ApiClient.json.encodeToString(AdjustmentCreate(id(), "10", "200000", "Observed count", "Conflict", "UTC")), cash)
            api.request("/workspaces/${saved.workspaceId}/transactions", "POST", ApiClient.json.encodeToString(IncomeCreate(id(), expense.occurred_at, "UTC", "Other client", "", emptyList(), "income", cash, "1000", listOf(AllocationInput(id(), null, "1000")))), saved, id(), saved.generationId)
            assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(saved))
            val rejected = db.outbox().commands(scope.key).single { it.actionId == pending.actionId }; assertEquals("rejected", rejected.state); assertEquals(pending.body, rejected.body)
            assertEquals("101000", LocalProjectionStore(db).read(scope)!!.visible.accounts.single().posted_balance_minor)
            assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(saved))
        } finally { db.close(); app.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
}
