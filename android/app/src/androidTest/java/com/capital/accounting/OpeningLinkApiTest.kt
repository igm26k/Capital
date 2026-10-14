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
class OpeningLinkApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun sameTimestampZeroOpeningsPersistWithoutGuessingAndMigrationKeepsMirror() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(
            Register("opening-link-${id()}@example.test", "synthetic opening password", "Opening", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "opening-link-api.db"
        app.deleteDatabase(name)
        fun open() = Room.databaseBuilder(app, CapitalDatabase::class.java, name).addMigrations(CapitalDatabase.MIGRATION_11_12).build()
        var db = open()
        try {
            IdentityStore(db).save(auth, origin)
            val expected = linkedMapOf<String, Transaction>()
            for (amount in listOf("0", "0", "1000")) {
                val account = AccountCreate(id(), "Opening $amount", "cash", "EUR", "2026-10-13T12:00:00Z", "UTC", amount)
                val result = decodeResponse<MutationResult>(api.request("/workspaces/${saved.workspaceId}/accounts", "POST",
                    ApiClient.json.encodeToString(account), saved, id(), saved.generationId))
                expected[account.id] = result.transactions.single()
            }
            SnapshotBootstrap(db).load(saved)
            val confirmed = SnapshotStore(db).read(scope)!!
            db.close()
            app.openOrCreateDatabase(name, 0, null).use { old ->
                old.execSQL("DROP TABLE opening_links"); old.execSQL("DROP TABLE room_master_table"); old.execSQL("PRAGMA user_version=11")
            }
            db = open(); assertEquals(confirmed, SnapshotStore(db).read(scope)); assertNotNull(IdentityStore(db).read(saved))
            OpeningLinkStore(db).refresh(saved)
            val links = db.openingLinks().all(scope.key, saved.generationId)
            assertEquals(3, links.size); assertEquals(3, links.map { it.transactionId }.toSet().size)
            for (link in links) assertEquals(expected[link.accountId]!!.id, link.transactionId)
            assertTrue(db.openingLinks().all(scope.key, id()).isEmpty())
            db.close(); db = open(); assertEquals(links.toSet(), db.openingLinks().all(scope.key, saved.generationId).toSet())
            OpeningLinkStore(db).refresh(saved); assertEquals(3, db.openingLinks().all(scope.key, saved.generationId).size)
            val nonzero = expected.entries.last()
            try { OpeningLinkStore(db).accept(saved, expected.keys.first(), nonzero.value); fail("Wrong account accepted") } catch (_: IllegalArgumentException) { }
            try { OpeningLinkStore(db).accept(StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, id(), saved.sessionId), expected.keys.first(), expected.values.first()); fail("Wrong generation accepted") } catch (_: IllegalArgumentException) { }
            try { OpeningLinkStore(db).accept(StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, saved.generationId, saved.sessionId, logoutPending = true), expected.keys.first(), expected.values.first()); fail("Logout intent accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(confirmed, SnapshotStore(db).read(scope)); assertTrue(db.outbox().commands(scope.key).isEmpty())
        } finally { db.close(); app.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
}
