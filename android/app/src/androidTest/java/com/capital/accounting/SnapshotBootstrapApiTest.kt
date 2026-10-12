package com.capital.accounting

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.*
import com.capital.accounting.sync.SnapshotBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SnapshotBootstrapApiTest {
    @Test fun unrecordedSnapshotPostReusesPersistedIdAfterDatabaseReopen() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("bootstrap-${UUID.randomUUID()}@example.test", "Synthetic bootstrap password", "Bootstrap API", "bearer", "UTC"))))
        val session = auth.credential(origin); val scope = MirrorScope(origin, auth.profile.id, auth.workspace.id)
        val root = "/workspaces/${scope.workspace}"
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "bootstrap-api-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            val account = decodeResponse<MutationResult>(api.request("$root/accounts", "POST", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Bootstrap cash", "cash", "KWD", "2026-10-01T12:00:00Z", "UTC", "100000")), session, UUID.randomUUID().toString(), session.generationId)).accounts.single()
            val id = UUID.randomUUID().toString(); db.snapshots().start(SnapshotAttempt(scope.key, id))
            val original = decodeResponse<Snapshot>(api.request("$root/sync/snapshots", "POST", ApiClient.json.encodeToString(SnapshotCreate(id)), session, id, session.generationId))
            // The server committed POST; the coordinator never recorded its reply.
            assertNull(db.snapshots().stage(scope.key))
            api.request("$root/transactions", "POST", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T12:00:00Z", "UTC", "After uncertain snapshot", "", emptyList(), "expense", account.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000")))), session, UUID.randomUUID().toString(), session.generationId)
            val pending = FinancialCommand(UUID.randomUUID().toString(), origin, scope.owner, scope.workspace, session.sessionId, session.generationId, "$root/accounts", "POST", "{}")
            db.commands().insert(pending)
            db.close(); db = open()
            val mirror = SnapshotBootstrap(db).load(session)
            assertEquals(original.resume_cursor, mirror.head.cursor); assertEquals("1", mirror.head.sequence)
            assertEquals("100000", mirror.items.filterIsInstance<SnapshotItemAccount>().single().data.posted_balance_minor)
            assertEquals(2, mirror.items.size); assertEquals("99000", api.accounts(session).single().posted_balance_minor)
            assertEquals(pending, db.commands().get(origin, scope.owner, scope.workspace)); assertNull(db.snapshots().attempt(scope.key))
            assertEquals(original, decodeResponse<Snapshot>(api.request("$root/sync/snapshots/$id", session = session)))
        } finally { db.close(); context.deleteDatabase(name); api.request("/auth/logout", "POST", session = session) }
    }
}
