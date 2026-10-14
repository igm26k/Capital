package com.capital.accounting

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*
import com.capital.accounting.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalHistoryApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun serverParityLocalEffectsDetailsCursorInvalidationAndColdReopen() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(
            Register("local-history-${id()}@example.test", "synthetic history password", "Local history", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "local-history-api.db"
        app.deleteDatabase(name); fun open() = Room.databaseBuilder(app, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            suspend fun mutate(path: String, body: String) = decodeResponse<MutationResult>(api.request(
                "/workspaces/${saved.workspaceId}/$path", "POST", body, saved, id(), saved.generationId))
            val accounts = (1..2).map { mutate("accounts", ApiClient.json.encodeToString(AccountCreate(id(), "Zero $it", "cash", "EUR", "2026-10-13T12:00:00Z", "UTC", "0"))).accounts.single() }
            val parent = mutate("categories", ApiClient.json.encodeToString(CategoryCreate(id(), "Parent", null))).categories.single()
            val child = mutate("categories", ApiClient.json.encodeToString(CategoryCreate(id(), "Child", parent.id))).categories.single()
            val tag = mutate("tags", ApiClient.json.encodeToString(TagCreate(id(), "Tag"))).tags.single()
            val incomes = listOf("2026-10-13T12:01:00.000001Z", "2026-10-13T12:01:00.000002Z", "2026-10-13T12:01:00.000002Z").map { date ->
                mutate("transactions", ApiClient.json.encodeToString(IncomeCreate(id(), date, "UTC", "Literal %_ Aa", "Payee", listOf(tag.id), "income", accounts.first().id, "100", listOf(AllocationInput(id(), child.id, "100"))))).transactions.single()
            }
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved); OpeningLinkStore(db).refresh(saved)
            val confirmed = SnapshotStore(db).read(scope)!!
            val filters = listOf(TransactionFilter(), TransactionFilter(accountId = accounts.first().id), TransactionFilter(accountId = accounts.last().id),
                TransactionFilter(categoryId = parent.id), TransactionFilter(categoryId = child.id), TransactionFilter(tagId = tag.id),
                TransactionFilter(kind = "opening"), TransactionFilter(status = "pending"), TransactionFilter(search = "%_ aa"), TransactionFilter(search = "payee"),
                TransactionFilter(from = incomes.first().occurred_at, to = incomes[1].occurred_at),
                TransactionFilter(accounts.first().id, child.id, tag.id, incomes.first().occurred_at, "2026-10-13T12:01:00.000003Z", "income", "posted", "%_"))
            for (filter in filters) {
                val expected = api.transactions(saved, 1, filter)
                var cursor: LocalHistoryCursor? = null; val actual = mutableListOf<Transaction>()
                do { val page = LocalHistoryStore(db).page(saved, filter, 1, cursor); actual.addAll(page.items); cursor = page.next } while (cursor != null)
                assertEquals(expected, actual)
            }
            for (account in accounts) {
                val opening = LocalHistoryStore(db).page(saved, TransactionFilter(accountId = account.id, kind = "opening")).items.single()
                assertTrue(opening.entries.isEmpty()); assertEquals(account.id, LocalHistoryStore(db).details(saved, opening.id).openingAccountId)
            }
            val firstPage = LocalHistoryStore(db).page(saved, TransactionFilter(), 1)
            val cursor = requireNotNull(firstPage.next)
            for (filter in listOf(TransactionFilter(accountId = id()), TransactionFilter(categoryId = id()), TransactionFilter(tagId = id()))) {
                try { LocalHistoryStore(db).page(saved, filter); fail("Unknown scoped reference accepted") } catch (_: IllegalArgumentException) { }
            }
            try { LocalHistoryStore(db).page(saved, TransactionFilter(kind = "income"), 1, cursor); fail("Changed filter accepted") } catch (_: IllegalArgumentException) { }
            try { LocalHistoryStore(db).page(saved, TransactionFilter(), 2, cursor); fail("Changed limit accepted") } catch (_: IllegalArgumentException) { }
            for (changed in listOf(cursor.copy(scope = id()), cursor.copy(generation = id()))) {
                try { LocalHistoryStore(db).page(saved, TransactionFilter(), 1, changed); fail("Foreign cursor accepted") } catch (_: IllegalArgumentException) { }
            }
            val localAccount = id()
            OfflineAccountStore(db).create(saved, ApiClient.json.encodeToString(AccountCreate(localAccount, "Pending zero", "cash", "EUR", "2026-10-13T12:00:00Z", "UTC", "0")))
            val localOpening = LocalHistoryStore(db).page(saved, TransactionFilter(accountId = localAccount)).items.single()
            assertEquals(localAccount, LocalHistoryStore(db).details(saved, localOpening.id).openingAccountId)
            val input = IncomeCreate(id(), "2026-10-13T12:02:00Z", "UTC", "Pending detail", "", listOf(tag.id), "income", accounts.first().id, "200", listOf(AllocationInput(id(), child.id, "200")))
            OfflineBasicStore(db).create(saved, ApiClient.json.encodeToString(input))
            assertEquals(input.id, LocalHistoryStore(db).page(saved, TransactionFilter(search = "Pending detail")).items.single().id)
            assertEquals(input.id, LocalHistoryStore(db).details(saved, input.id).transaction!!.id)
            try { LocalHistoryStore(db).page(saved, TransactionFilter(), 1, cursor); fail("Changed dataset accepted") } catch (_: IllegalArgumentException) { }
            val commands = db.outbox().commands(scope.key)
            db.close(); db = open()
            assertEquals(commands, db.outbox().commands(scope.key)); assertEquals(localAccount, LocalHistoryStore(db).details(saved, localOpening.id).openingAccountId)
            OfflineBasicStore(db).delete(saved, ApiClient.json.encodeToString(TransactionDelete("1", emptyList())), input.id)
            assertNull(LocalHistoryStore(db).details(saved, input.id).transaction)
            assertTrue(LocalHistoryStore(db).page(saved, TransactionFilter(search = "Pending detail")).items.isEmpty())
            assertEquals(confirmed, SnapshotStore(db).read(scope))
            val denied = listOf(
                StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, saved.generationId, saved.sessionId, logoutPending = true),
                StoredSession(saved.origin, saved.token, id(), saved.workspaceId, saved.generationId, saved.sessionId),
                StoredSession(saved.origin, saved.token, saved.ownerId, id(), saved.generationId, saved.sessionId),
                StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, id(), saved.sessionId))
            for (session in denied) {
                try { LocalHistoryStore(db).details(session, incomes.first().id); fail("Invalid identity accepted") } catch (e: ApiFailure) { assertEquals(401, e.status) }
            }

        } finally { db.close(); app.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
}
