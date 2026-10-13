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
class OfflineTransferApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun exactSameCrossCurrencyFeesDependenciesRollbackReopenAndNoDuplicate() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-transfer-${id()}@example.test", "synthetic transfer password", "Offline transfer", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "offline-transfer-api.db"
        context.deleteDatabase(name); fun open() = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val source = id(); val target = id(); val yen = id(); val euro = id(); val category = id(); val tag = id()
            for ((account, currency, minor) in listOf(Triple(source, "KWD", "100000"), Triple(target, "KWD", "20000"), Triple(yen, "JPY", "1000"), Triple(euro, "EUR", "10000")))
                OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(account, currency, "cash", currency, "2026-10-13T12:00:00Z", "UTC", minor)))
            OfflineCategoryStore(db).enqueue(saved, ApiClient.json.encodeToString(CategoryCreate(category, "Fee", null)))
            OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagCreate(tag, "Transfer")))
            val firstFee = FeeInput(id(), source, "111", listOf(AllocationInput(id(), category, "111")), "Fee", listOf(tag))
            val first = TransferCreate(id(), "2026-10-13T12:05:00.123456Z", "UTC", "Same", "Payee", listOf(tag), "transfer", source, target, "12345", "12345", null, firstFee)
            val store = OfflineTransferStore(db)
            for (invalid in listOf(first.copy(source_account_id = target), first.copy(target_amount_minor = "12346"), first.copy(rate = Rate("2", "1")),
                first.copy(rate = Rate("01", "1")), first.copy(fee = firstFee.copy(id = first.id)), first.copy(fee = firstFee.copy(amount_minor = "112")),
                first.copy(occurred_at = "2026-10-13T11:00:00Z"), first.copy(tag_ids = listOf(id())),
                first.copy(source_amount_minor = "9000000000000000", target_amount_minor = "9000000000000000"))) {
                try { store.create(saved, ApiClient.json.encodeToString(invalid)); fail("Invalid transfer accepted") } catch (_: IllegalArgumentException) { }
            }
            try { db.withTransaction { store.create(saved, ApiClient.json.encodeToString(first)); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertEquals(6, db.outbox().commands(scope.key).size)
            store.create(saved, ApiClient.json.encodeToString(first))
            val second = first.copy(id = id(), note = "Cross", target_account_id = yen, source_amount_minor = "1000", target_amount_minor = "300", rate = Rate("600", "2"),
                fee = FeeInput(id(), euro, "55", listOf(AllocationInput(id(), category, "55")), "Third account fee", listOf(tag)))
            try { store.create(saved, ApiClient.json.encodeToString(second.copy(fee = second.fee!!.copy(allocations = listOf(AllocationInput(firstFee.allocations.single().id, null, "55")))))); fail("Reused allocation accepted") } catch (_: IllegalArgumentException) { }
            store.create(saved, ApiClient.json.encodeToString(second))
            val third = first.copy(id = id(), note = "Reverse", source_account_id = target, target_account_id = source, source_amount_minor = "1000", target_amount_minor = "1000",
                fee = FeeInput(id(), yen, "5", listOf(AllocationInput(id(), null, "5")), "JPY fee", emptyList()))
            store.create(saved, ApiClient.json.encodeToString(third))
            OfflineBasicStore(db).create(saved, ApiClient.json.encodeToString(IncomeCreate(id(), first.occurred_at, "UTC", "After transfer", "", emptyList(), "income", source, "999", listOf(AllocationInput(id(), null, "999")))))
            val queued = db.outbox().commands(scope.key); assertEquals(10, queued.size)
            val local = LocalProjectionStore(db).read(scope)!!.visible
            val expected = mapOf(source to "88543", target to "31345", yen to "1295", euro to "9945")
            assertEquals(expected, local.accounts.associate { it.id to it.posted_balance_minor })
            assertEquals(mapOf(source to "5", target to "3", yen to "3", euro to "2"), local.accounts.associate { it.id to it.version })
            assertEquals(Rate("300", "1"), local.transactions.single { it.id == second.id }.rate)
            assertEquals(firstFee.id, local.transactions.single { it.id == first.id }.fee_transaction_id)
            assertEquals(first.id, local.transactions.single { it.id == firstFee.id }.parent_transaction_id)
            db.close(); db = open(); assertEquals(queued, db.outbox().commands(scope.key))
            assertEquals(QueueDrainResult(10, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("10", mirror.head.sequence)
            assertEquals(expected, mirror.finance().accounts.associate { it.id to it.posted_balance_minor })
            assertEquals(local.accounts.associate { it.id to it.balance_version }, mirror.finance().accounts.associate { it.id to it.balance_version })
            for (transfer in listOf(first, second, third)) {
                val confirmed = mirror.finance().transactions.single { it.id == transfer.id }
                assertEquals(setOf("-${transfer.source_amount_minor}", transfer.target_amount_minor), confirmed.entries.map { it.amount_minor }.toSet())
                assertEquals(transfer.fee!!.id, confirmed.fee_transaction_id)
                val fee = mirror.finance().transactions.single { it.id == transfer.fee.id }
                assertEquals(transfer.id, fee.parent_transaction_id); assertEquals("-${transfer.fee.amount_minor}", fee.entries.single().amount_minor)
                assertEquals(transfer.fee.allocations.associate { it.id to it.amount_minor }, fee.allocations.associate { it.id to it.amount_minor })
            }
            assertEquals(queued.map { it.copy(state = "applied") }, db.outbox().commands(scope.key))
            assertTrue(db.outbox().commands(scope.key).all { db.outbox().overlays(it.position).isEmpty() })
            assertEquals(QueueDrainResult(0, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
        } finally { db.close(); context.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
    @Test fun metadataSwapFeeRemovalAdditionDeleteOriginalVersionsAndRefundConflict() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-transfer-edit-${id()}@example.test", "synthetic edit password", "Transfer edit", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "offline-transfer-edit-api.db"
        context.deleteDatabase(name); fun open() = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val source = id(); val target = id(); val third = id(); val yen = id()
            for (account in listOf(source, target, third, yen)) OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(account, account, "cash", if (account == yen) "JPY" else "KWD", "2026-10-13T12:00:00Z", "UTC", "100000")))
            val fee = FeeInput(id(), source, "100", listOf(AllocationInput(id(), null, "100")), "Fee", emptyList())
            val first = TransferCreate(id(), "2026-10-13T12:05:00Z", "UTC", "Original", "", emptyList(), "transfer", source, target, "1000", "1000", null, fee)
            OfflineTransferStore(db).create(saved, ApiClient.json.encodeToString(first))
            assertEquals(QueueDrainResult(5, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val original = SnapshotStore(db).read(scope)!!.finance().transactions.single { it.id == first.id }
            val entryIds = original.entries.map { it.id }.toSet()
            val meta = TransferReplace(first.occurred_at, "UTC", "Metadata", "", emptyList(), "transfer", source, target, "1000", "1000", null, fee, "1", "1")
            val store = OfflineTransferStore(db)
            for (invalid in listOf(meta.copy(expected_version = "2"), meta.copy(expected_fee_version = null), meta.copy(source_account_id = yen), meta.copy(fee = fee.copy(id = id())))) {
                try { store.replace(saved, ApiClient.json.encodeToString(invalid), first.id); fail("Invalid replacement accepted") } catch (_: IllegalArgumentException) { }
            }
            try { db.withTransaction { store.replace(saved, ApiClient.json.encodeToString(meta), first.id); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertEquals(5, db.outbox().commands(scope.key).size)
            store.replace(saved, ApiClient.json.encodeToString(meta), first.id)
            assertEquals("2", LocalProjectionStore(db).read(scope)!!.visible.accounts.single { it.id == source }.version)
            val swappedFee = fee.copy(account_id = third, amount_minor = "200", allocations = listOf(AllocationInput(fee.allocations.single().id, null, "200")))
            val swap = meta.copy(source_account_id = target, target_account_id = source, source_amount_minor = "2000", target_amount_minor = "2000", fee = swappedFee, expected_version = "2", expected_fee_version = "2")
            store.replace(saved, ApiClient.json.encodeToString(swap), first.id)
            assertEquals(entryIds, LocalProjectionStore(db).read(scope)!!.visible.transactions.single { it.id == first.id }.entries.map { it.id }.toSet())
            store.replace(saved, ApiClient.json.encodeToString(swap.copy(fee = null, expected_version = "3", expected_fee_version = "3")), first.id)
            val newFee = fee.copy(id = id(), account_id = target, allocations = listOf(AllocationInput(id(), null, "100")))
            store.replace(saved, ApiClient.json.encodeToString(swap.copy(fee = newFee, expected_version = "4", expected_fee_version = null)), first.id)
            try { store.delete(saved, ApiClient.json.encodeToString(TransactionDelete("5", emptyList())), first.id); fail("Missing fee version accepted") } catch (_: IllegalArgumentException) { }
            store.delete(saved, ApiClient.json.encodeToString(TransactionDelete("5", listOf(VersionExpectation(newFee.id, "1")))), first.id)
            OfflineBasicStore(db).create(saved, ApiClient.json.encodeToString(IncomeCreate(id(), first.occurred_at, "UTC", "After deletion", "", emptyList(), "income", source, "999", listOf(AllocationInput(id(), null, "999")))))
            val queued = db.outbox().commands(scope.key); assertEquals(11, queued.size)
            val local = LocalProjectionStore(db).read(scope)!!.visible
            assertEquals(mapOf(source to "100999", target to "100000", third to "100000", yen to "100000"), local.accounts.associate { it.id to it.posted_balance_minor })
            assertEquals(mapOf(source to "5", target to "5", third to "3", yen to "1"), local.accounts.associate { it.id to it.version })
            db.close(); db = open(); assertEquals(queued, db.outbox().commands(scope.key))
            assertEquals(QueueDrainResult(6, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("11", mirror.head.sequence)
            assertEquals(local.accounts.associate { it.id to (it.posted_balance_minor to it.balance_version) }, mirror.finance().accounts.associate { it.id to (it.posted_balance_minor to it.balance_version) })
            for (command in queued.filter { it.method == "PUT" }) {
                val result = decodeResponse<MutationResult>(db.receipts().get(command.position)!!.payload)
                assertEquals(entryIds, result.transactions.single { it.id == first.id }.entries.map { it.id }.toSet())
            }
            assertEquals(queued.map { it.copy(state = "applied") }, db.outbox().commands(scope.key))
            assertTrue(db.outbox().commands(scope.key).all { db.outbox().overlays(it.position).isEmpty() })
            val guardedTransfer = first.copy(id = id(), fee = fee.copy(id = id(), allocations = listOf(AllocationInput(id(), null, "100"))))
            api.request("/workspaces/${saved.workspaceId}/transactions", "POST", ApiClient.json.encodeToString(guardedTransfer), saved, id(), saved.generationId)
            val guardedFee = guardedTransfer.fee!!
            api.request("/workspaces/${saved.workspaceId}/transactions", "POST", ApiClient.json.encodeToString(RefundCreate(id(), "2026-10-13T12:10:00Z", "UTC", "Refund", "", emptyList(), "refund", source, "10", guardedFee.id, "1", "1", listOf(RefundAllocationInput(id(), guardedFee.allocations.single().id, "10")))), saved, id(), saved.generationId)
            MirrorSynchronizer(db).refresh(saved)
            val guarded = meta.copy(fee = guardedFee, expected_version = "2", expected_fee_version = "2")
            for (invalid in listOf(guarded.copy(fee = null), guarded.copy(occurred_at = "2026-10-13T12:06:00Z"), guarded.copy(fee = guardedFee.copy(amount_minor = "120", allocations = listOf(AllocationInput(guardedFee.allocations.single().id, null, "120")))))) {
                try { OfflineTransferStore(db).replace(saved, ApiClient.json.encodeToString(invalid), guardedTransfer.id); fail("Refund guard bypassed") } catch (_: IllegalArgumentException) { }
            }
            try { OfflineTransferStore(db).delete(saved, ApiClient.json.encodeToString(TransactionDelete("2", listOf(VersionExpectation(guardedFee.id, "2")))), guardedTransfer.id); fail("Refunded delete accepted") } catch (_: IllegalArgumentException) { }
            val pending = OfflineTransferStore(db).replace(saved, ApiClient.json.encodeToString(guarded), guardedTransfer.id)
            assertEquals("90", LocalProjectionStore(db).read(scope)!!.visible.transactions.single { it.id == guardedFee.id }.allocations.single().remaining_refundable_minor)
            api.request("/workspaces/${saved.workspaceId}/transactions/${guardedTransfer.id}", "PUT", ApiClient.json.encodeToString(guarded.copy(note = "Other client")), saved, id(), saved.generationId)
            assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(saved))
            assertEquals(pending.body, db.outbox().commands(scope.key).single { it.actionId == pending.actionId }.body)
            assertEquals("rejected", db.outbox().commands(scope.key).single { it.actionId == pending.actionId }.state)
            assertEquals("Other client", LocalProjectionStore(db).read(scope)!!.visible.transactions.single { it.id == guardedTransfer.id }.note)
        } finally { db.close(); context.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }

}
