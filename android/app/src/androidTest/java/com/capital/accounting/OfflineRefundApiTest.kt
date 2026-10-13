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
class OfflineRefundApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun reservationsMetadataReallocationMoveDeleteArchivedReferencesAndConflict() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-refund-${id()}@example.test", "synthetic refund password", "Refund", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "offline-refund-api.db"
        app.deleteDatabase(name); fun open() = Room.databaseBuilder(app, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val cash = id(); val target = id(); val yen = id(); val category = id(); val tag = id()
            for ((account, currency, opening) in listOf(Triple(cash, "KWD", "100000"), Triple(target, "KWD", "20000"), Triple(yen, "JPY", "1000")))
                OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(account, currency, "cash", currency, "2026-10-13T12:00:00Z", "UTC", opening)))
            OfflineCategoryStore(db).enqueue(saved, ApiClient.json.encodeToString(CategoryCreate(category, "Original", null)))
            OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagCreate(tag, "Historical")))
            val expense = ExpenseCreate(id(), "2026-10-13T12:05:00Z", "UTC", "Expense", "", listOf(tag), "expense", cash, "12345", listOf(AllocationInput(id(), category, "2000"), AllocationInput(id(), null, "10345")))
            OfflineBasicStore(db).create(saved, ApiClient.json.encodeToString(expense))
            val refund = RefundCreate(id(), "2026-10-13T12:10:00Z", "UTC", "Refund", "", listOf(tag), "refund", target, "3000", expense.id, "1", null,
                listOf(RefundAllocationInput(id(), expense.allocations[0].id, "1000"), RefundAllocationInput(id(), expense.allocations[1].id, "2000")))
            val store = OfflineRefundStore(db)
            for (invalid in listOf(refund.copy(expected_parent_version = "2"), refund.copy(account_id = yen), refund.copy(occurred_at = "2026-10-13T12:04:00Z"),
                refund.copy(amount_minor = "3001"), refund.copy(expected_transfer_version = "1"), refund.copy(allocations = listOf(RefundAllocationInput(id(), expense.allocations[0].id, "3000"))))) {
                try { store.create(saved, ApiClient.json.encodeToString(invalid)); fail("Invalid refund accepted") } catch (_: IllegalArgumentException) { }
            }
            try { db.withTransaction { store.create(saved, ApiClient.json.encodeToString(refund)); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertEquals(6, db.outbox().commands(scope.key).size)
            store.create(saved, ApiClient.json.encodeToString(refund))
            OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagUpdate("1", "Historical", true)), tag)
            OfflineCategoryStore(db).enqueue(saved, ApiClient.json.encodeToString(CategoryUpdate("1", "Original", true, null)), category)
            val meta = RefundReplace(refund.occurred_at, "UTC", "Metadata", "", refund.tag_ids, "refund", target, "3000", expense.id, "2", null, refund.allocations, "1")
            store.replace(saved, ApiClient.json.encodeToString(meta), refund.id)
            var local = LocalProjectionStore(db).read(scope)!!.visible
            assertEquals("2", local.transactions.single { it.id == expense.id }.version); assertEquals("2", local.accounts.single { it.id == target }.version)
            val redistributed = meta.copy(allocations = refund.allocations.map { it.copy(amount_minor = "1500") }, expected_version = "2")
            store.replace(saved, ApiClient.json.encodeToString(redistributed), refund.id)
            local = LocalProjectionStore(db).read(scope)!!.visible
            assertEquals("3", local.transactions.single { it.id == expense.id }.version); assertEquals("2", local.accounts.single { it.id == target }.version)
            val moved = redistributed.copy(account_id = cash, amount_minor = "4000", allocations = listOf(redistributed.allocations[0], redistributed.allocations[1].copy(amount_minor = "2500")), expected_version = "3", expected_parent_version = "3")
            try { store.replace(saved, ApiClient.json.encodeToString(moved.copy(allocations = moved.allocations.map { it.copy(id = id()) })), refund.id); fail("Changed allocation IDs accepted") } catch (_: IllegalArgumentException) { }
            store.replace(saved, ApiClient.json.encodeToString(moved), refund.id)
            val second = refund.copy(id = id(), amount_minor = "1000", expected_parent_version = "4", tag_ids = emptyList(), allocations = refund.allocations.map { it.copy(id = id(), amount_minor = "500") })
            store.create(saved, ApiClient.json.encodeToString(second))
            try { store.create(saved, ApiClient.json.encodeToString(second.copy(id = id(), expected_parent_version = "5", allocations = second.allocations.map { it.copy(id = id()) }))); fail("Over-reservation accepted") } catch (_: IllegalArgumentException) { }
            store.delete(saved, ApiClient.json.encodeToString(TransactionDelete("4", listOf(VersionExpectation(expense.id, "5")))), refund.id)
            store.delete(saved, ApiClient.json.encodeToString(TransactionDelete("1", listOf(VersionExpectation(expense.id, "6")))), second.id)
            val expenseMeta = ExpenseReplace(expense.occurred_at, "UTC", "Expense metadata", "", expense.tag_ids, "expense", cash, expense.amount_minor, expense.allocations, "7", null)
            OfflineBasicStore(db).replace(saved, ApiClient.json.encodeToString(expenseMeta), expense.id)
            val queued = db.outbox().commands(scope.key); assertEquals(16, queued.size)
            local = LocalProjectionStore(db).read(scope)!!.visible
            assertEquals(mapOf(cash to "87655", target to "20000", yen to "1000"), local.accounts.associate { it.id to it.posted_balance_minor })
            assertEquals(mapOf(cash to "4", target to "5", yen to "1"), local.accounts.associate { it.id to it.balance_version })
            assertEquals("8", local.transactions.single { it.id == expense.id }.version)
            assertEquals(listOf("2000", "10345"), local.transactions.single { it.id == expense.id }.allocations.map { it.remaining_refundable_minor })
            db.close(); db = open(); assertEquals(queued, db.outbox().commands(scope.key))
            assertEquals(QueueDrainResult(16, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("16", mirror.head.sequence)
            assertEquals(local.accounts.associate { it.id to (it.posted_balance_minor to it.version) }, mirror.finance().accounts.associate { it.id to (it.posted_balance_minor to it.version) })
            val original = mirror.finance().transactions.single { it.id == expense.id }
            assertEquals(expense.allocations.associate { it.id to it.amount_minor }, original.allocations.associate { it.id to it.remaining_refundable_minor })
            assertEquals(queued.map { it.copy(state = "applied") }, db.outbox().commands(scope.key))
            assertTrue(db.outbox().commands(scope.key).all { db.outbox().overlays(it.position).isEmpty() })
            val pending = OfflineRefundStore(db).create(saved, ApiClient.json.encodeToString(second.copy(id = id(), expected_parent_version = "8", allocations = second.allocations.map { it.copy(id = id()) })))
            api.request("/workspaces/${saved.workspaceId}/transactions/${expense.id}", "PUT", ApiClient.json.encodeToString(expenseMeta.copy(expected_version = "8", note = "Other client")), saved, id(), saved.generationId)
            assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(saved))
            assertEquals(pending.body, db.outbox().commands(scope.key).single { it.actionId == pending.actionId }.body)
            assertEquals("rejected", db.outbox().commands(scope.key).single { it.actionId == pending.actionId }.state)
            assertEquals("Other client", LocalProjectionStore(db).read(scope)!!.visible.transactions.single { it.id == expense.id }.note)
        } finally { db.close(); app.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
    @Test fun feeRefundVersionsAndDeletionRestoreParentBeforeTransferRemoval() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-fee-refund-${id()}@example.test", "synthetic fee refund password", "Fee refund", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "offline-fee-refund-api.db"
        app.deleteDatabase(name); fun open() = Room.databaseBuilder(app, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val cash = id(); val target = id()
            for ((account, opening) in listOf(cash to "100000", target to "20000")) OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(account, account, "cash", "KWD", "2026-10-13T12:00:00Z", "UTC", opening)))
            val fee = FeeInput(id(), cash, "3333", listOf(AllocationInput(id(), null, "1000"), AllocationInput(id(), null, "2333")), "Fee", emptyList())
            val transfer = TransferCreate(id(), "2026-10-13T12:05:00Z", "UTC", "Transfer", "", emptyList(), "transfer", cash, target, "1000", "1000", null, fee)
            OfflineTransferStore(db).create(saved, ApiClient.json.encodeToString(transfer))
            val refund = RefundCreate(id(), "2026-10-13T12:10:00Z", "UTC", "Fee refund", "", emptyList(), "refund", target, "1111", fee.id, "1", "1",
                listOf(RefundAllocationInput(id(), fee.allocations[0].id, "1000"), RefundAllocationInput(id(), fee.allocations[1].id, "111")))
            val store = OfflineRefundStore(db)
            try { store.create(saved, ApiClient.json.encodeToString(refund.copy(expected_transfer_version = null))); fail("Missing transfer version accepted") } catch (_: IllegalArgumentException) { }
            store.create(saved, ApiClient.json.encodeToString(refund))
            val meta = RefundReplace(refund.occurred_at, "UTC", "Metadata", "", emptyList(), "refund", target, "1111", fee.id, "2", "2", refund.allocations, "1")
            store.replace(saved, ApiClient.json.encodeToString(meta), refund.id)
            val local = LocalProjectionStore(db).read(scope)!!.visible
            assertEquals("2", local.transactions.single { it.id == transfer.id }.version); assertEquals("2", local.transactions.single { it.id == fee.id }.version)
            assertEquals("3", local.accounts.single { it.id == target }.version)
            val changed = meta.copy(amount_minor = "2222", allocations = listOf(meta.allocations[0], meta.allocations[1].copy(amount_minor = "1222")), expected_version = "2")
            store.replace(saved, ApiClient.json.encodeToString(changed), refund.id)
            try { store.delete(saved, ApiClient.json.encodeToString(TransactionDelete("3", listOf(VersionExpectation(fee.id, "3")))), refund.id); fail("Missing linked version accepted") } catch (_: IllegalArgumentException) { }
            store.delete(saved, ApiClient.json.encodeToString(TransactionDelete("3", listOf(VersionExpectation(fee.id, "3"), VersionExpectation(transfer.id, "3")))), refund.id)
            val withoutFee = TransferReplace(transfer.occurred_at, "UTC", transfer.note, "", emptyList(), "transfer", cash, target, "1000", "1000", null, null, "4", "4")
            OfflineTransferStore(db).replace(saved, ApiClient.json.encodeToString(withoutFee), transfer.id)
            OfflineTransferStore(db).delete(saved, ApiClient.json.encodeToString(TransactionDelete("5", emptyList())), transfer.id)
            val queued = db.outbox().commands(scope.key); assertEquals(9, queued.size)
            val projected = LocalProjectionStore(db).read(scope)!!.visible
            assertEquals(mapOf(cash to "100000", target to "20000"), projected.accounts.associate { it.id to it.posted_balance_minor })
            assertEquals(mapOf(cash to "4", target to "6"), projected.accounts.associate { it.id to it.version })
            assertTrue(projected.transactions.all { it.kind == "opening" })
            db.close(); db = open(); assertEquals(queued, db.outbox().commands(scope.key))
            assertEquals(QueueDrainResult(9, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("9", mirror.head.sequence)
            assertEquals(projected.accounts.associate { it.id to (it.posted_balance_minor to it.balance_version) }, mirror.finance().accounts.associate { it.id to (it.posted_balance_minor to it.balance_version) })
            assertTrue(mirror.finance().transactions.all { it.kind == "opening" })
            assertEquals(queued.map { it.copy(state = "applied") }, db.outbox().commands(scope.key))
            assertTrue(db.outbox().commands(scope.key).all { db.outbox().overlays(it.position).isEmpty() })
        } finally { db.close(); app.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
}
