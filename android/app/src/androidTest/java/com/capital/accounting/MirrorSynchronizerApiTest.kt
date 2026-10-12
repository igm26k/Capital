package com.capital.accounting

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.*
import com.capital.accounting.sync.MirrorSynchronizer
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MirrorSynchronizerApiTest {
    @Test fun refreshBootstrapsDrainsPagesAndSerializesRepeatedCallsWithoutSendingCommand() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("mirror-loop-${UUID.randomUUID()}@example.test", "Synthetic mirror loop password", "Mirror loop", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId); val root = "/workspaces/${scope.workspace}"
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "mirror-loop-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        suspend fun command(path: String, method: String, body: String): MutationResult = decodeResponse(api.request("$root/$path", method, body, saved, UUID.randomUUID().toString(), saved.generationId))
        try {
            val account = command("accounts", "POST", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Loop cash", "cash", "KWD", "2026-10-01T12:00:00Z", "UTC", "100000"))).accounts.single()
            val initial = MirrorSynchronizer(db).refresh(saved, limit = 1); assertEquals("1", initial.head.sequence)
            val expense = command("transactions", "POST", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T12:00:00Z", "UTC", "Loop expense", "", emptyList(), "expense", account.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000"))))).transactions.single { it.kind == "expense" }
            command("transactions/${expense.id}", "PUT", ApiClient.json.encodeToString(ExpenseReplace(expense.occurred_at, expense.occurred_timezone, "Loop replace", "", emptyList(), "expense", account.id, "2000", listOf(AllocationInput(expense.allocations.single().id, null, "2000")), expense.version, null)))
            command("transactions/${expense.id}", "DELETE", ApiClient.json.encodeToString(TransactionDelete("2", emptyList())))
            val pending = FinancialCommand(UUID.randomUUID().toString(), origin, scope.owner, scope.workspace, saved.sessionId, saved.generationId, "$root/accounts", "POST", "{}")
            db.commands().insert(pending)
            val updated = MirrorSynchronizer(db).refresh(saved, limit = 1)
            assertEquals("4", updated.head.sequence); assertEquals("100000", updated.items.filterIsInstance<SnapshotItemAccount>().single().data.posted_balance_minor)
            assertEquals(1, updated.items.filterIsInstance<SnapshotItemTransaction>().size)
            assertEquals(expense.id, db.pull().tombstones(scope.key).single().entityId)
            val repeated = coroutineScope { listOf(async { MirrorSynchronizer(db).refresh(saved, 1) }, async { MirrorSynchronizer(db).refresh(saved, 1) }).awaitAll() }
            assertTrue(repeated.all { it == updated }); assertEquals(pending, db.commands().get(origin, scope.owner, scope.workspace))
            // Only the initial bootstrap exists: the second live materialization is still permitted.
            val probe = UUID.randomUUID().toString()
            assertEquals(2, decodeResponse<Snapshot>(api.request("$root/sync/snapshots", "POST", ApiClient.json.encodeToString(SnapshotCreate(probe)), saved, probe, saved.generationId)).item_count)
            assertEquals(1, api.transactions(saved).size)
        } finally { db.close(); context.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
}
