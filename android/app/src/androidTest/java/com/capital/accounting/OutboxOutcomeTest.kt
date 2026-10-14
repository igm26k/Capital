package com.capital.accounting

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutboxOutcomeTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private fun id() = UUID.randomUUID().toString()
    private val date = "2026-10-12T12:00:00Z"
    private fun open(name: String) = Room.databaseBuilder(context, CapitalDatabase::class.java, name).addMigrations(CapitalDatabase.MIGRATION_10_11, CapitalDatabase.MIGRATION_11_12).build()
    private suspend fun prepare(db: CapitalDatabase): Pair<OutboxCommand, Tag> {
        val s = StoredSession("https://accounting.example", "x".repeat(64), id(), id(), id(), id())
        val scope = MirrorScope(s.origin, s.ownerId, s.workspaceId); db.snapshots().head(MirrorHead(scope.key, s.generationId, "0", "cursor"))
        val tag = Tag(id(), s.workspaceId, "1", date, date, null, "Local", null)
        OutboxStore(db).enqueue(s, "tags", "POST", "{}", emptyList(), emptyList(), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag))))
        val c = OutboxLifecycle(db).claim(s)!!; return OutboxLifecycle(db).failed(c, "reconcile", ApiFailure(409, "action_result_expired", id())) to tag
    }
    private fun outcome(c: OutboxCommand, tag: Tag) = ActionOutcome(c.actionId, c.workspace, "applied", date, date, 201, "1", listOf(ReceiptEntity("tag", tag.id, "1")), null, c.generation)
    @Test fun compactAppliedRollbackReopenAndSnapshotCoverage() = runBlocking<Unit> {
        val name = "outbox-outcome-compact.db"; context.deleteDatabase(name); var db = open(name)
        try {
            val (c, tag) = prepare(db); val body = ApiClient.json.encodeToString(outcome(c, tag))
            try { db.withTransaction { OutboxOutcomeStore(db).accept(c, body); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertNull(db.outcomes().get(c.position)); assertNull(db.receipts().get(c.position)); assertNotNull(db.failures().get(c.position)); assertEquals(c, db.outbox().commands(c.scope).single())
            val record = OutboxOutcomeStore(db).accept(c, body); db.close(); db = open(name)
            assertEquals(record, db.outcomes().get(c.position)); assertEquals("", db.receipts().get(c.position)!!.payload); assertNull(db.failures().get(c.position))
            assertEquals(record, OutboxOutcomeStore(db).accept(c, body)); assertEquals(1, db.outbox().overlays(c.position).size)
            val full = MutationResult(c.actionId, emptyList(), emptyList(), emptyList(), listOf(tag), emptyList(), "1", c.generation)
            assertEquals("", OutboxReceiptStore(db).applied(c, ApiClient.json.encodeToString(full)).payload)
            val scope = MirrorScope(c.origin, c.owner, c.workspace); val meta = Snapshot(id(), c.workspace, "1", "covered", date, date, 1, "first", c.generation)
            val store = SnapshotStore(db); store.begin(scope, meta); store.append(scope, "first", SnapshotPage(meta.id, c.workspace, "1", listOf(SnapshotItemTag("tag", tag)), null, c.generation)); store.install(scope)
            assertTrue(db.outbox().overlays(c.position).isEmpty()); assertEquals(record, db.outcomes().get(c.position))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun migrationAndCompactRejectedPreserveDraftWithoutSavingMessage() = runBlocking<Unit> {
        val name = "outbox-outcome-migration.db"; context.deleteDatabase(name); var db = open(name)
        try {
            val (c, tag) = prepare(db); val effects = db.outbox().overlays(c.position); db.close()
            context.openOrCreateDatabase(name, 0, null).use { old -> old.execSQL("DROP TABLE opening_links"); old.execSQL("DROP TABLE outbox_outcomes"); old.execSQL("DROP TABLE room_master_table"); old.execSQL("PRAGMA user_version=10") }
            db = open(name); assertEquals(c, db.outbox().commands(c.scope).single()); assertEquals(effects, db.outbox().overlays(c.position))
            val rejected = outcome(c, tag).copy(state = "rejected", original_http_status = 409, generation_id = null, group_sequence = null, entity_refs = emptyList())
            val record = OutboxOutcomeStore(db).accept(c, ApiClient.json.encodeToString(rejected))
            assertEquals("rejected", record.state); assertEquals("rejected", db.outbox().commands(c.scope).single().state)
            assertEquals(effects, db.outbox().overlays(c.position)); assertNull(db.receipts().get(c.position)); assertEquals(409, db.failures().get(c.position)!!.status)
            assertEquals(c.body, db.outbox().commands(c.scope).single().body)
            try { OutboxOutcomeStore(db).accept(c, ApiClient.json.encodeToString(outcome(c, tag))); fail("Conflicting evidence accepted") } catch (_: IllegalArgumentException) { }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
