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
}
