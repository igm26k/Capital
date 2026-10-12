package com.capital.accounting

import androidx.room.Room
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
import java.net.URLEncoder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PullApiTest {
    @Test fun realPullContainsWholeCreateReplaceDeleteGroupsAndStableOpaqueReplay() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("pull-${UUID.randomUUID()}@example.test", "Synthetic pull password", "Pull API", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scoped = MirrorScope(origin, saved.ownerId, saved.workspaceId); val root = "/workspaces/${saved.workspaceId}"
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "pull-api-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        suspend fun command(path: String, method: String, body: String): MutationResult = decodeResponse(api.request("$root/$path", method, body, saved, UUID.randomUUID().toString(), saved.generationId))
        suspend fun pull(cursor: String): SyncChanges = decodeResponse(api.request("$root/sync/changes?cursor=${URLEncoder.encode(cursor, "UTF-8")}&limit=1", session = saved, maxResponseBytes = 4 * 1024 * 1024))
        try {
            val account = command("accounts", "POST", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Pull cash", "cash", "KWD", "2026-10-01T12:00:00Z", "UTC", "200000"))).accounts.single()
            val baseline = SnapshotBootstrap(db).load(saved); assertEquals("1", baseline.head.sequence)
            val expense = command("transactions", "POST", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T12:00:00Z", "UTC", "Pull expense", "", emptyList(), "expense", account.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000"))))).transactions.single { it.kind == "expense" }
            command("transactions/${expense.id}", "PUT", ApiClient.json.encodeToString(ExpenseReplace(expense.occurred_at, expense.occurred_timezone, "Pull replace", "", emptyList(), "expense", account.id, "2000", listOf(AllocationInput(expense.allocations.single().id, null, "2000")), expense.version, null)))
            command("transactions/${expense.id}", "DELETE", ApiClient.json.encodeToString(TransactionDelete("2", emptyList())))
            var captured = baseline.head
            val first = pull(captured.cursor)
            assertEquals(first, pull(captured.cursor))
            for (sequence in listOf("2", "3", "4")) {
                val page = if (sequence == "2") first else pull(captured.cursor)
                assertEquals(1, page.groups.size); assertEquals(2, page.groups.single().changes.size)
                assertEquals(sequence, validatedPullSequence(scoped, captured, page))
                assertEquals("4", page.head_sequence)
                val changes = page.groups.single().changes
                assertEquals(1, changes.filterIsInstance<WorkspaceChangeAccountUpsert>().size)
                if (sequence == "4") {
                    val deleted = changes.filterIsInstance<WorkspaceChangeTransactionDelete>().single()
                    assertEquals(expense.id, deleted.data.id); assertEquals("3", deleted.data.version)
                } else {
                    val changed = changes.filterIsInstance<WorkspaceChangeTransactionUpsert>().single()
                    assertEquals(if (sequence == "2") "1000" else "2000", changed.data.allocations.single().amount_minor)
                }
                captured = captured.copy(sequence = sequence, cursor = page.next_cursor)
            }
            val empty = pull(captured.cursor); assertTrue(empty.groups.isEmpty()); assertFalse(empty.has_more)
            assertEquals("4", validatedPullSequence(scoped, captured, empty))
            assertEquals(baseline, SnapshotStore(db).read(scoped)) // This layer validates; Room application follows separately.
            assertEquals("200000", api.accounts(saved).single().posted_balance_minor)
            assertEquals(1, api.transactions(saved).size)
        } finally { db.close(); context.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
}
