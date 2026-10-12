package com.capital.accounting

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SnapshotApiTest {
    @Test fun typedMultiPageSnapshotStaysImmutableWhileLiveLedgerChanges() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin")
        assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("snapshot-${UUID.randomUUID()}@example.test", "Synthetic snapshot password", "Snapshot API", "bearer", "UTC"))))
        val session = auth.credential(origin)
        val root = "/workspaces/${auth.workspace.id}"
        suspend fun command(suffix: String, body: String): MutationResult = decodeResponse(api.request("$root/$suffix", "POST", body, session, UUID.randomUUID().toString(), session.generationId))
        try {
            val emptyId = UUID.randomUUID().toString()
            val empty = decodeResponse<Snapshot>(api.request("$root/sync/snapshots", "POST", ApiClient.json.encodeToString(SnapshotCreate(emptyId)), session, emptyId, session.generationId))
            assertEquals(0, empty.item_count); assertEquals("0", empty.base_sequence)
            val emptyPage = decodeResponse<SnapshotPage>(api.request("$root/sync/snapshots/$emptyId/pages?page_token=${URLEncoder.encode(empty.first_page_token, "UTF-8")}", session = session))
            assertTrue(emptyPage.items.isEmpty()); assertNull(emptyPage.next_page_token)
            val account = command("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Snapshot cash", "cash", "KWD", "2026-10-01T12:00:00Z", "UTC", "100000"))).accounts.single()
            val category = command("categories", ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), "Snapshot category", null))).categories.single()
            repeat(101) { index -> command("tags", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "Snapshot tag $index"))) }
            val id = UUID.randomUUID().toString()
            suspend fun create(): Snapshot = decodeResponse(api.request("$root/sync/snapshots", "POST", ApiClient.json.encodeToString(SnapshotCreate(id)), session, id, session.generationId))
            val snapshot = create()
            assertEquals(104, snapshot.item_count); assertEquals("103", snapshot.base_sequence)
            assertEquals(auth.workspace.id, snapshot.workspace_id); assertEquals(session.generationId, snapshot.generation_id)
            val changed = command("transactions", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T12:00:00Z", "UTC", "After snapshot", "", emptyList(), "expense", account.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), category.id, "1000")))))
            assertEquals("99000", changed.accounts.single().posted_balance_minor)
            assertEquals(snapshot, create())
            assertEquals(snapshot, decodeResponse<Snapshot>(api.request("$root/sync/snapshots/$id", session = session)))
            val items = mutableListOf<SnapshotItem>()
            var token: String? = snapshot.first_page_token
            val seen = mutableSetOf<String>(); var pages = 0
            while (token != null) {
                assertTrue(seen.add(token))
                val page = decodeResponse<SnapshotPage>(api.request("$root/sync/snapshots/$id/pages?page_token=${URLEncoder.encode(token, "UTF-8")}", session = session))
                assertEquals(id, page.snapshot_id); assertEquals(snapshot.workspace_id, page.workspace_id)
                assertEquals(snapshot.base_sequence, page.base_sequence); assertEquals(snapshot.generation_id, page.generation_id)
                assertTrue(page.items.size <= 100)
                items += page.items; token = page.next_page_token; pages++
            }
            assertEquals(2, pages); assertEquals(104, items.size)
            assertEquals("100000", items.filterIsInstance<SnapshotItemAccount>().single().data.posted_balance_minor)
            assertEquals("opening", items.filterIsInstance<SnapshotItemTransaction>().single().data.kind)
            assertEquals(category.id, items.filterIsInstance<SnapshotItemCategory>().single().data.id)
            assertEquals(101, items.filterIsInstance<SnapshotItemTag>().size)
            assertEquals("99000", api.accounts(session).single().posted_balance_minor)
            assertEquals(2, api.transactions(session).size)
        } finally { api.request("/auth/logout", "POST", session = session) }
    }
}
