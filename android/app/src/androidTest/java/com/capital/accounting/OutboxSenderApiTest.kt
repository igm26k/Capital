package com.capital.accounting

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*
import com.capital.accounting.sync.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutboxSenderApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun lostReplyReplaysOriginalCommandConcurrentSendAndConflictNeverDuplicate() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin")
        assumeTrue("Real API required", origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("outbox-${id()}@example.test", "synthetic outbox password", "Outbox sender", "bearer", "UTC"))))
        val s = auth.credential(origin); val scope = MirrorScope(origin, s.ownerId, s.workspaceId)
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "outbox-sender-api.db"
        context.deleteDatabase(name); fun open() = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            SnapshotBootstrap(db).load(s)
            val date = "2026-10-12T12:00:00Z"; val local = Tag(id(), s.workspaceId, "1", date, date, null, "First offline tag", null)
            val first = OutboxStore(db).enqueue(s, "tags", "POST", ApiClient.json.encodeToString(TagCreate(local.id, local.name)), emptyList(), emptyList(), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", local))))
            val sending = OutboxLifecycle(db).claim(s)!!
            val original = api.request(sending.path, sending.method, sending.body, s, sending.actionId, sending.generation)
            // Commit reached the API, but the process did not save the reply.
            db.close(); db = open()
            val completed = OutboxSender(db).sendNext(s)!!
            assertEquals(first.copy(state = "applied"), completed); assertEquals(decodeResponse<MutationResult>(original), decodeResponse<MutationResult>(db.receipts().get(first.position)!!.payload))
            assertEquals("0", SnapshotStore(db).read(scope)!!.head.sequence); assertEquals(1, db.outbox().overlays(first.position).size)
            val secondTag = local.copy(id = id(), name = "Second offline tag")
            val second = OutboxStore(db).enqueue(s, "tags", "POST", ApiClient.json.encodeToString(TagCreate(secondTag.id, secondTag.name)), emptyList(), listOf(first.actionId), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", secondTag))))
            val results = coroutineScope { val a = async { OutboxSender(db).sendNext(s) }; val b = async { OutboxSender(db).sendNext(s) }; listOf(a.await(), b.await()) }
            assertEquals(1, results.count { it?.actionId == second.actionId }); assertEquals(1, results.count { it == null })
            val mirror = MirrorSynchronizer(db).refresh(s); assertEquals("2", mirror.head.sequence)
            assertEquals(2, mirror.items.filterIsInstance<SnapshotItemTag>().size)
            assertTrue(db.outbox().overlays(first.position).isEmpty()); assertTrue(db.outbox().overlays(second.position).isEmpty())
            val confirmed = mirror.items.filterIsInstance<SnapshotItemTag>().first { it.data.id == local.id }.data
            val rejected = OutboxStore(db).enqueue(s, "tags/${local.id}", "PUT", ApiClient.json.encodeToString(TagUpdate("999", "Stale change", false)), listOf(OutboxBase("tag", local.id, confirmed.version)), emptyList(), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", confirmed.copy(name = "Stale change")))))
            assertEquals(rejected.copy(state = "rejected"), OutboxSender(db).sendNext(s)); assertNull(OutboxSender(db).sendNext(s))
            val failure = db.failures().get(rejected.position)!!
            assertEquals(409, failure.status); assertEquals("version_conflict", failure.code); assertNotNull(failure.requestId)
            db.close(); db = open(); assertEquals(failure, db.failures().get(rejected.position))
            assertNull(db.receipts().get(rejected.position)); assertEquals(1, db.outbox().overlays(rejected.position).size)
            val current = MirrorSynchronizer(db).refresh(s); assertEquals("2", current.head.sequence)
            assertEquals(confirmed, current.items.filterIsInstance<SnapshotItemTag>().first { it.data.id == local.id }.data)
            val wrong = StoredSession(origin, s.token, s.ownerId, s.workspaceId, id(), s.sessionId)
            try { OutboxSender(db).sendNext(wrong); fail("Generation changed") } catch (_: IllegalArgumentException) { }
            api.request("/auth/logout", "POST", session = s)
            try { OutboxSender(db).sendNext(s); fail("Revoked bearer accepted") } catch (e: ApiFailure) { assertEquals(401, e.status) }
            assertEquals(rejected.copy(state = "rejected"), db.outbox().commands(scope.key).last())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
