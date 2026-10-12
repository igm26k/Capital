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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutboxReceiptTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private fun id() = UUID.randomUUID().toString()
    private val date = "2026-10-12T12:00:00Z"
    private fun open(name: String) = Room.databaseBuilder(context, CapitalDatabase::class.java, name).addMigrations(CapitalDatabase.MIGRATION_8_9, CapitalDatabase.MIGRATION_9_10, CapitalDatabase.MIGRATION_10_11).build()
    private fun saved() = StoredSession("https://accounting.example", "x".repeat(64), id(), id(), id(), id())
    private fun scope(s: StoredSession) = MirrorScope(s.origin, s.ownerId, s.workspaceId)
    private suspend fun snapshot(db: CapitalDatabase, s: StoredSession, sequence: String, tag: Tag?, generation: String = s.generationId) {
        val store = SnapshotStore(db); val meta = Snapshot(id(), s.workspaceId, sequence, "cursor$sequence", date, date, if (tag == null) 0 else 1, "first", generation)
        store.begin(scope(s), meta); store.append(scope(s), "first", SnapshotPage(meta.id, s.workspaceId, sequence, tag?.let { listOf(SnapshotItemTag("tag", it)) } ?: emptyList(), null, generation)); store.install(scope(s))
    }
    private suspend fun prepare(db: CapitalDatabase, s: StoredSession, tag: Tag): OutboxCommand {
        snapshot(db, s, "0", null)
        OutboxStore(db).enqueue(s, "tags", "POST", "{\"name\":\"Local\"}", emptyList(), emptyList(), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag))))
        return OutboxLifecycle(db).claim(s)!!
    }
    private fun response(c: OutboxCommand, tag: Tag) = ApiClient.json.encodeToString(MutationResult(c.actionId, emptyList(), emptyList(), emptyList(), listOf(tag), emptyList(), "1", c.generation))
    private fun tag(s: StoredSession) = Tag(id(), s.workspaceId, "1", date, date, null, "Local", null)

    @Test fun receiptRollbackReopenAndSnapshotCoverageAreAtomic() = runBlocking<Unit> {
        val name = "outbox-receipt-snapshot.db"; context.deleteDatabase(name); var db = open(name); val s = saved()
        try {
            val tag = tag(s); val c = prepare(db, s, tag); val body = response(c, tag)
            try { db.withTransaction { OutboxReceiptStore(db).applied(c, body); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertNull(db.receipts().get(c.position)); assertEquals(c, db.outbox().commands(c.scope).single())
            val receipt = OutboxReceiptStore(db).applied(c, body); assertEquals("0", SnapshotStore(db).read(scope(s))!!.head.sequence)
            assertEquals(1, db.outbox().overlays(c.position).size)
            db.close(); db = open(name); assertEquals(receipt, db.receipts().get(c.position))
            assertEquals(receipt, OutboxReceiptStore(db).applied(c, body))
            val reordered = JsonObject(ApiClient.json.parseToJsonElement(body).jsonObject.entries.reversed().associate { it.toPair() }).toString()
            assertEquals(receipt, OutboxReceiptStore(db).applied(c, reordered)); assertEquals(body, db.receipts().get(c.position)!!.payload)
            snapshot(db, s, "0", null); assertEquals(1, db.outbox().overlays(c.position).size)
            try { db.withTransaction { snapshot(db, s, "1", tag); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertEquals("0", SnapshotStore(db).read(scope(s))!!.head.sequence); assertEquals(1, db.outbox().overlays(c.position).size)
            snapshot(db, s, "1", tag); assertTrue(db.outbox().overlays(c.position).isEmpty()); assertEquals(receipt, db.receipts().get(c.position))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun pullCoverageAndLateReceiptPreserveCursorAndNewGeneration() = runBlocking<Unit> {
        val name = "outbox-receipt-pull.db"; context.deleteDatabase(name); val db = open(name); val s = saved()
        try {
            val tag = tag(s); val c = prepare(db, s, tag); OutboxReceiptStore(db).applied(c, response(c, tag))
            val head = SnapshotStore(db).read(scope(s))!!.head
            val change = WorkspaceChangeTagUpsert(1, id(), c.actionId, s.workspaceId, "1", 1, 1, date, "upsert", "tag", tag, s.generationId)
            val page = SyncChanges(s.workspaceId, listOf(SyncGroup("1", c.actionId, s.workspaceId, listOf(change), s.generationId)), "pull-cursor", false, "1", "1", s.generationId)
            try { db.withTransaction { PullStore(db).apply(scope(s), head, page); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertEquals(head, SnapshotStore(db).read(scope(s))!!.head); assertEquals(1, db.outbox().overlays(c.position).size)
            assertEquals(0, db.pull().seen(c.scope, change.change_id))
            PullStore(db).apply(scope(s), head, page); assertTrue(db.outbox().overlays(c.position).isEmpty())
            assertEquals("pull-cursor", SnapshotStore(db).read(scope(s))!!.head.cursor)
            val another = OutboxStore(db).enqueue(s, "tags", "POST", "{}", emptyList(), emptyList(), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag.copy(id = id())))))
            val sending = OutboxLifecycle(db).claim(s)!!; assertEquals(another.actionId, sending.actionId)
            val effect = db.outbox().overlays(sending.position).single(); val local = decodeResponse<SnapshotItemTag>(effect.payload!!).data
            val generation = id(); snapshot(db, s, "0", null, generation)
            OutboxReceiptStore(db).applied(sending, response(sending, local))
            assertTrue(db.outbox().overlays(sending.position).isEmpty()); assertEquals(generation, SnapshotStore(db).read(scope(s))!!.head.generation)
            assertTrue(SnapshotStore(db).read(scope(s))!!.items.isEmpty()); assertEquals("0", SnapshotStore(db).read(scope(s))!!.head.sequence)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun foreignOrMalformedReceiptCannotConfirmCommand() = runBlocking<Unit> {
        val name = "outbox-receipt-invalid.db"; context.deleteDatabase(name); val db = open(name); val s = saved()
        try {
            val tag = tag(s); val c = prepare(db, s, tag); val valid = decodeResponse<MutationResult>(response(c, tag))
            for (bad in listOf(valid.copy(action_id = id()), valid.copy(generation_id = id()), valid.copy(sync_group_sequence = "01"), valid.copy(tags = listOf(tag.copy(workspace_id = id()))), valid.copy(tags = listOf(tag, tag)), valid.copy(tags = listOf(tag.copy(id = id()))))) {
                try { OutboxReceiptStore(db).applied(c, ApiClient.json.encodeToString(bad)); fail("Invalid receipt accepted") } catch (_: IllegalArgumentException) { }
            }
            assertEquals(c, db.outbox().commands(c.scope).single()); assertNull(db.receipts().get(c.position)); assertEquals(1, db.outbox().overlays(c.position).size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun migrationEightToNineKeepsSendingBodyAndOverlay() = runBlocking<Unit> {
        val name = "outbox-receipt-migration.db"; context.deleteDatabase(name); var db = open(name); val s = saved()
        try {
            val tag = tag(s); val c = prepare(db, s, tag); val overlays = db.outbox().overlays(c.position); db.close()
            context.openOrCreateDatabase(name, 0, null).use { old -> old.execSQL("DROP TABLE outbox_outcomes"); old.execSQL("DROP TABLE outbox_failures"); old.execSQL("DROP TABLE outbox_receipts"); old.execSQL("DROP TABLE room_master_table"); old.execSQL("PRAGMA user_version=8") }
            db = open(name); assertEquals(c, db.outbox().commands(c.scope).single()); assertEquals(overlays, db.outbox().overlays(c.position))
            assertNull(db.receipts().get(c.position)); OutboxReceiptStore(db).applied(c, response(c, tag)); assertNotNull(db.receipts().get(c.position))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
