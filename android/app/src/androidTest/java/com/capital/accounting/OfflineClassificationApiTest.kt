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
class OfflineClassificationApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun hundredPendingItemsFiftyTagsAtomicRollbackReopenAndDependentMetadata() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-bulk-${id()}@example.test", "synthetic bulk password", "Bulk", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "offline-bulk-api.db"
        app.deleteDatabase(name); fun open() = Room.databaseBuilder(app, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val cash = id(); val category = id(); val tags = List(50) { id() }
            OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(cash, "Cash", "cash", "KWD", "2026-10-13T12:00:00Z", "UTC", "100000")))
            OfflineCategoryStore(db).enqueue(saved, ApiClient.json.encodeToString(CategoryCreate(category, "Bulk", null)))
            for ((index, tag) in tags.withIndex()) OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagCreate(tag, "Tag $index")))
            val incomes = List(100) { IncomeCreate(id(), "2026-10-13T12:05:00Z", "UTC", "Income $it", "", emptyList(), "income", cash, "1", listOf(AllocationInput(id(), null, "1"))) }
            for (income in incomes) OfflineBasicStore(db).create(saved, ApiClient.json.encodeToString(income))
            val input = BulkClassification(incomes.map { BulkItem(it.id, "1") }, category, tags)
            val store = OfflineClassificationStore(db)
            for (invalid in listOf(input.copy(items = input.items + input.items.first()), input.copy(items = emptyList()), input.copy(items = input.items.mapIndexed { i, item -> if (i == 99) item.copy(expected_version = "2") else item }), input.copy(tag_ids = tags + tags.first()))) {
                try { store.enqueue(saved, ApiClient.json.encodeToString(invalid)); fail("Invalid package accepted") } catch (_: IllegalArgumentException) { }
            }
            try { db.withTransaction { store.enqueue(saved, ApiClient.json.encodeToString(input)); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertEquals(152, db.outbox().commands(scope.key).size)
            val before = LocalProjectionStore(db).read(scope)!!.visible
            val command = store.enqueue(saved, ApiClient.json.encodeToString(input))
            assertTrue(ApiClient.json.decodeFromString<List<String>>(command.dependencies).size > 100)
            var local = LocalProjectionStore(db).read(scope)!!.visible
            assertEquals(before.accounts, local.accounts)
            for (income in incomes) {
                val old = before.transactions.single { it.id == income.id }; val changed = local.transactions.single { it.id == income.id }
                assertEquals("2", changed.version); assertEquals(old.entries, changed.entries); assertEquals(old.occurred_at, changed.occurred_at)
                assertEquals(old.allocations.single().id, changed.allocations.single().id); assertEquals(category, changed.allocations.single().category_id); assertEquals(tags, changed.tag_ids)
            }
            val first = incomes.first()
            OfflineBasicStore(db).replace(saved, ApiClient.json.encodeToString(IncomeReplace(first.occurred_at, "UTC", "After batch", "", tags, "income", cash, "1", listOf(AllocationInput(first.allocations.single().id, category, "1")), "2")), first.id)
            val queued = db.outbox().commands(scope.key); assertEquals(154, queued.size)
            db.close(); db = open(); assertEquals(queued, db.outbox().commands(scope.key))
            assertEquals(QueueDrainResult(100, QueueDrainStatus.MORE), QueueCoordinator(db).drain(saved, maximum = 100))
            assertEquals(QueueDrainResult(54, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved, maximum = 100))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("154", mirror.head.sequence)
            val account = mirror.finance().accounts.single(); assertEquals("100100", account.posted_balance_minor); assertEquals("101", account.version); assertEquals("101", account.balance_version)
            val createdTransactions = queued.filter { it.method == "POST" && it.path.endsWith("/transactions") }.associate { item ->
                val created = decodeResponse<MutationResult>(db.receipts().get(item.position)!!.payload).transactions.single()
                created.id to created
            }
            for (income in incomes) {
                val transaction = mirror.finance().transactions.single { it.id == income.id }
                val created = createdTransactions.getValue(income.id)
                assertEquals(created.entries, transaction.entries); assertEquals(created.occurred_at, transaction.occurred_at)
                assertEquals(if (income.id == first.id) "3" else "2", transaction.version)
                assertEquals(category, transaction.allocations.single().category_id); assertEquals(tags.toSet(), transaction.tag_ids.toSet())
                assertEquals(income.allocations.single().id, transaction.allocations.single().id); assertEquals("1", transaction.entries.single().amount_minor)
            }
            assertEquals(queued.map { it.copy(state = "applied") }, db.outbox().commands(scope.key))
            assertTrue(db.outbox().commands(scope.key).all { db.outbox().overlays(it.position).isEmpty() })
            assertEquals(QueueDrainResult(0, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
        } finally { db.close(); app.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
    @Test fun eligibilityHistoricalAssignmentsAndOneStaleItemRejectEntirePackage() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-bulk-conflict-${id()}@example.test", "synthetic conflict password", "Bulk conflict", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "offline-bulk-conflict-api.db"
        app.deleteDatabase(name); fun open() = Room.databaseBuilder(app, CapitalDatabase::class.java, name).build()
        val db = open()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val cash = id(); val category = id(); val tag = id()
            OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(cash, "Cash", "cash", "KWD", "2026-10-13T12:00:00Z", "UTC", "100000")))
            OfflineCategoryStore(db).enqueue(saved, ApiClient.json.encodeToString(CategoryCreate(category, "Historical", null)))
            OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagCreate(tag, "Historical")))
            val expense = ExpenseCreate(id(), "2026-10-13T12:05:00Z", "UTC", "Expense", "", listOf(tag), "expense", cash, "8000", listOf(AllocationInput(id(), category, "8000")))
            val income = IncomeCreate(id(), expense.occurred_at, "UTC", "Income", "", listOf(tag), "income", cash, "2000", listOf(AllocationInput(id(), category, "2000")))
            val split = expense.copy(id = id(), amount_minor = "1000", allocations = listOf(AllocationInput(id(), null, "500"), AllocationInput(id(), null, "500")))
            val refunded = expense.copy(id = id(), amount_minor = "1000", allocations = listOf(AllocationInput(id(), null, "1000")))
            val fresh = income.copy(id = id(), amount_minor = "1000", tag_ids = emptyList(), allocations = listOf(AllocationInput(id(), null, "1000")))
            val basic = OfflineBasicStore(db)
            basic.create(saved, ApiClient.json.encodeToString(expense)); basic.create(saved, ApiClient.json.encodeToString(income)); basic.create(saved, ApiClient.json.encodeToString(split)); basic.create(saved, ApiClient.json.encodeToString(refunded))
            OfflineRefundStore(db).create(saved, ApiClient.json.encodeToString(RefundCreate(id(), "2026-10-13T12:10:00Z", "UTC", "Refund", "", emptyList(), "refund", cash, "100", refunded.id, "1", null, listOf(RefundAllocationInput(id(), refunded.allocations.single().id, "100")))))
            basic.create(saved, ApiClient.json.encodeToString(fresh))
            OfflineCategoryStore(db).enqueue(saved, ApiClient.json.encodeToString(CategoryUpdate("1", "Historical", true, null)), category)
            OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagUpdate("1", "Historical", true)), tag)
            val input = BulkClassification(listOf(BulkItem(expense.id, "1"), BulkItem(income.id, "1")), category, listOf(tag))
            val store = OfflineClassificationStore(db)
            for (invalid in listOf(input.copy(items = input.items + BulkItem(split.id, "1")), input.copy(items = input.items + BulkItem(refunded.id, "2")), input.copy(items = input.items + BulkItem(fresh.id, "1")))) {
                try { store.enqueue(saved, ApiClient.json.encodeToString(invalid)); fail("Ineligible package accepted") } catch (_: IllegalArgumentException) { }
            }
            val before = LocalProjectionStore(db).read(scope)!!.visible.accounts
            store.enqueue(saved, ApiClient.json.encodeToString(input)); assertEquals(before, LocalProjectionStore(db).read(scope)!!.visible.accounts)
            assertEquals(QueueDrainResult(12, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("12", mirror.head.sequence)
            assertEquals("93100", mirror.finance().accounts.single().posted_balance_minor); assertEquals("7", mirror.finance().accounts.single().balance_version)
            val pendingInput = input.copy(items = input.items.map { it.copy(expected_version = "2") }, category_id = null, tag_ids = emptyList())
            val pending = store.enqueue(saved, ApiClient.json.encodeToString(pendingInput))
            api.request("/workspaces/${saved.workspaceId}/transactions/${income.id}", "PUT", ApiClient.json.encodeToString(IncomeReplace(income.occurred_at, "UTC", "Other client", "", income.tag_ids, "income", cash, income.amount_minor, income.allocations, "2")), saved, id(), saved.generationId)
            assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(saved))
            val rejected = db.outbox().commands(scope.key).single { it.actionId == pending.actionId }; assertEquals("rejected", rejected.state); assertEquals(pending.body, rejected.body)
            val confirmed = SnapshotStore(db).read(scope)!!; assertEquals("13", confirmed.head.sequence)
            val unchanged = confirmed.finance().transactions.single { it.id == expense.id }; assertEquals("2", unchanged.version); assertEquals(category, unchanged.allocations.single().category_id); assertEquals(listOf(tag), unchanged.tag_ids)
            assertEquals("Other client", LocalProjectionStore(db).read(scope)!!.visible.transactions.single { it.id == income.id }.note)
            assertEquals("93100", confirmed.finance().accounts.single().posted_balance_minor)
        } finally { db.close(); app.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }

}
