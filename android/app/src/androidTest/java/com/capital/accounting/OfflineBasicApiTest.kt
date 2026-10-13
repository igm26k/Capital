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
class OfflineBasicApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun dependentAccountCatalogExpenseIncomeExactMinorRollbackAndOriginalBodies() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-basic-${id()}@example.test", "synthetic ledger password", "Offline ledger", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "offline-basic-api.db"
        context.deleteDatabase(name); fun open() = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val accountId = id(); val categoryId = id(); val tagId = id()
            val account = OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(accountId, "KWD", "cash", "KWD", "2026-10-13T12:00:00Z", "UTC", "100000")))
            val category = OfflineCategoryStore(db).enqueue(saved, ApiClient.json.encodeToString(CategoryCreate(categoryId, "Food", null)))
            val tag = OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagCreate(tagId, "Exact")))
            val expense = ExpenseCreate(id(), "2026-10-13T12:05:00.123456Z", "UTC", "Expense", "Payee", listOf(tagId), "expense", accountId, "12345", listOf(AllocationInput(id(), categoryId, "2000"), AllocationInput(id(), null, "10345")))
            val store = OfflineBasicStore(db)
            for (invalid in listOf(expense.copy(allocations = listOf(AllocationInput(id(), null, "1"))), expense.copy(occurred_at = "2026-10-13T11:00:00Z"), expense.copy(tag_ids = listOf(id())), expense.copy(amount_minor = "01"))) {
                try { store.create(saved, ApiClient.json.encodeToString(invalid)); fail("Invalid input accepted") } catch (_: IllegalArgumentException) { }
            }
            try { db.withTransaction { store.create(saved, ApiClient.json.encodeToString(expense)); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertEquals(3, db.outbox().commands(scope.key).size)
            val expenseCommand = store.create(saved, ApiClient.json.encodeToString(expense))
            assertEquals(setOf(account.actionId, category.actionId, tag.actionId), ApiClient.json.decodeFromString<List<String>>(expenseCommand.dependencies).toSet())
            val income = IncomeCreate(id(), "2026-10-13T12:10:00.000001Z", "UTC", "Income", "Sender", listOf(tagId), "income", accountId, "3333", listOf(AllocationInput(id(), null, "3333")))
            try { store.create(saved, ApiClient.json.encodeToString(income.copy(allocations = listOf(AllocationInput(expense.allocations.first().id, null, "3333"))))); fail("Reused allocation accepted") } catch (_: IllegalArgumentException) { }
            try { store.create(saved, ApiClient.json.encodeToString(income.copy(amount_minor = "9000000000000000", allocations = listOf(AllocationInput(id(), null, "9000000000000000"))))); fail("Balance overflow accepted") } catch (_: IllegalArgumentException) { }
            val incomeCommand = store.create(saved, ApiClient.json.encodeToString(income))
            val pending = db.outbox().commands(scope.key); assertEquals(5, pending.size)
            val projected = LocalProjectionStore(db).read(scope)!!; assertTrue(projected.confirmed.items.isEmpty())
            assertEquals("90988", projected.visible.accounts.single().posted_balance_minor)
            assertEquals("3", projected.visible.accounts.single().version); assertEquals("3", projected.visible.accounts.single().balance_version)
            db.close(); db = open(); assertEquals(pending, db.outbox().commands(scope.key))
            assertEquals(QueueDrainResult(5, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("5", mirror.head.sequence)
            val finance = mirror.finance(); assertEquals("90988", finance.accounts.single().posted_balance_minor); assertEquals("3", finance.accounts.single().balance_version)
            val realExpense = finance.transactions.single { it.id == expense.id }; assertEquals("-12345", realExpense.entries.single().amount_minor)
            assertEquals(setOf("2000", "10345"), realExpense.allocations.map { it.amount_minor }.toSet())
            assertEquals("3333", finance.transactions.single { it.id == income.id }.entries.single().amount_minor)
            assertEquals(pending.map { it.copy(state = "applied") }, db.outbox().commands(scope.key))
            assertTrue(db.outbox().commands(scope.key).all { db.outbox().overlays(it.position).isEmpty() })
            assertEquals(expenseCommand.body, db.outbox().commands(scope.key).first { it.actionId == expenseCommand.actionId }.body)
            assertEquals(incomeCommand.body, db.outbox().commands(scope.key).last().body)
        } finally { db.close(); context.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
    @Test fun replaceMoveMetadataDeleteForecastsSurviveRollbackRestartAndExactServerCoverage() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("offline-edit-${id()}@example.test", "synthetic edit password", "Offline edit", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "offline-edit-api.db"
        context.deleteDatabase(name); fun open() = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val first = id(); val second = id()
            for (accountId in listOf(first, second)) OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(accountId, accountId, "cash", "KWD", "2026-10-13T12:00:00Z", "UTC", "100000")))
            val expense = ExpenseCreate(id(), "2026-10-13T12:05:00Z", "UTC", "Expense", "", emptyList(), "expense", first, "12345", listOf(AllocationInput(id(), null, "12345")))
            val store = OfflineBasicStore(db); store.create(saved, ApiClient.json.encodeToString(expense))
            val edit = ExpenseReplace(expense.occurred_at, "UTC", "Edited expense", "", emptyList(), "expense", first, "12345", expense.allocations, "1", null)
            try { store.replace(saved, ApiClient.json.encodeToString(edit.copy(expected_version = "2")), expense.id); fail("Stale version accepted") } catch (_: IllegalArgumentException) { }
            try { db.withTransaction { store.replace(saved, ApiClient.json.encodeToString(edit), expense.id); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertEquals(3, db.outbox().commands(scope.key).size)
            store.replace(saved, ApiClient.json.encodeToString(edit), expense.id)
            var projected = LocalProjectionStore(db).read(scope)!!.visible
            assertEquals("2", projected.accounts.single { it.id == first }.version)
            assertEquals("2", projected.transactions.single { it.id == expense.id }.version)
            val moved = edit.copy(account_id = second, amount_minor = "20000", allocations = listOf(AllocationInput(expense.allocations.single().id, null, "20000")), expected_version = "2")
            store.replace(saved, ApiClient.json.encodeToString(moved), expense.id)
            projected = LocalProjectionStore(db).read(scope)!!.visible
            assertEquals("100000", projected.accounts.single { it.id == first }.posted_balance_minor)
            assertEquals("80000", projected.accounts.single { it.id == second }.posted_balance_minor)
            store.delete(saved, ApiClient.json.encodeToString(TransactionDelete("3", emptyList())), expense.id)
            val income = IncomeCreate(id(), expense.occurred_at, "UTC", "Income", "", emptyList(), "income", second, "3333", listOf(AllocationInput(id(), null, "3333")))
            store.create(saved, ApiClient.json.encodeToString(income))
            store.replace(saved, ApiClient.json.encodeToString(IncomeReplace(income.occurred_at, "UTC", "Changed income", "", emptyList(), "income", second, "4444", listOf(AllocationInput(income.allocations.single().id, null, "4444")), "1")), income.id)
            store.delete(saved, ApiClient.json.encodeToString(TransactionDelete("2", emptyList())), income.id)
            val pending = db.outbox().commands(scope.key); assertEquals(9, pending.size)
            projected = LocalProjectionStore(db).read(scope)!!.visible
            assertTrue(projected.transactions.all { it.kind == "opening" }); assertTrue(projected.accounts.all { it.posted_balance_minor == "100000" })
            assertEquals("3", projected.accounts.single { it.id == first }.version); assertEquals("6", projected.accounts.single { it.id == second }.version)
            db.close(); db = open(); assertEquals(pending, db.outbox().commands(scope.key))
            assertEquals(QueueDrainResult(9, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("9", mirror.head.sequence)
            val finance = mirror.finance(); assertTrue(finance.transactions.all { it.kind == "opening" })
            assertTrue(finance.accounts.all { it.posted_balance_minor == "100000" }); assertEquals("6", finance.accounts.single { it.id == second }.balance_version)
            assertEquals(pending.map { it.copy(state = "applied") }, db.outbox().commands(scope.key))
            assertTrue(db.outbox().commands(scope.key).all { db.outbox().overlays(it.position).isEmpty() })
            assertEquals(QueueDrainResult(0, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val refundedExpense = expense.copy(id = id(), account_id = second, allocations = listOf(AllocationInput(id(), null, "12345")))
            api.request("/workspaces/${saved.workspaceId}/transactions", "POST", ApiClient.json.encodeToString(refundedExpense), saved, id(), saved.generationId)
            api.request("/workspaces/${saved.workspaceId}/transactions", "POST", ApiClient.json.encodeToString(RefundCreate(id(), "2026-10-13T12:10:00Z", "UTC", "Refund", "", emptyList(), "refund", second, "1000", refundedExpense.id, "1", null, listOf(RefundAllocationInput(id(), refundedExpense.allocations.single().id, "1000")))), saved, id(), saved.generationId)
            MirrorSynchronizer(db).refresh(saved)
            val guarded = edit.copy(account_id = second, allocations = refundedExpense.allocations, expected_version = "2", note = "Local metadata")
            try { OfflineBasicStore(db).delete(saved, ApiClient.json.encodeToString(TransactionDelete("2", emptyList())), refundedExpense.id); fail("Refunded delete accepted") } catch (_: IllegalArgumentException) { }
            try { OfflineBasicStore(db).replace(saved, ApiClient.json.encodeToString(guarded.copy(amount_minor = "12000", allocations = listOf(AllocationInput(refundedExpense.allocations.single().id, null, "12000")))), refundedExpense.id); fail("Refunded movement accepted") } catch (_: IllegalArgumentException) { }
            val local = OfflineBasicStore(db).replace(saved, ApiClient.json.encodeToString(guarded), refundedExpense.id)
            OfflineBasicStore(db).replace(saved, ApiClient.json.encodeToString(guarded.copy(expected_version = "3", note = "Dependent metadata")), refundedExpense.id)
            assertEquals("11345", LocalProjectionStore(db).read(scope)!!.visible.transactions.single { it.id == refundedExpense.id }.allocations.single().remaining_refundable_minor)
            api.request("/workspaces/${saved.workspaceId}/transactions/${refundedExpense.id}", "PUT", ApiClient.json.encodeToString(guarded.copy(note = "Other client")), saved, id(), saved.generationId)
            assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(saved))
            val rejected = db.outbox().commands(scope.key).single { it.actionId == local.actionId }
            assertEquals("rejected", rejected.state); assertEquals(local.body, rejected.body)
            val conflict = LocalProjectionStore(db).read(scope)!!
            assertEquals(2, conflict.blocked.size); assertEquals("Other client", conflict.visible.transactions.single { it.id == refundedExpense.id }.note)
            assertEquals("88655", conflict.visible.accounts.single { it.id == second }.posted_balance_minor)

        } finally { db.close(); context.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }

}
