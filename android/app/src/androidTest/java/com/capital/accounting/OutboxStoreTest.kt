package com.capital.accounting

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutboxStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private fun id() = UUID.randomUUID().toString()
    private fun open(name: String) = Room.databaseBuilder(context, CapitalDatabase::class.java, name)
        .addMigrations(CapitalDatabase.MIGRATION_7_8).build()
    private val date = "2026-10-12T12:00:00Z"
    private suspend fun baseline(db: CapitalDatabase, saved: StoredSession): Tag {
        val tag = Tag(id(), saved.workspaceId, "1", date, date, null, "Confirmed", null)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val meta = Snapshot(id(), scope.workspace, "1", "cursor", date, date, 1, "first", saved.generationId)
        val store = SnapshotStore(db); store.begin(scope, meta)
        store.append(scope, "first", SnapshotPage(meta.id, scope.workspace, "1", listOf(SnapshotItemTag("tag", tag)), null, meta.generation_id))
        store.install(scope); return tag
    }
    private fun session() = StoredSession("https://accounting.example", "x".repeat(64), id(), id(), id(), id())
    private suspend fun enqueue(db: CapitalDatabase, saved: StoredSession, tag: Tag, deps: List<String> = emptyList(), action: String = id(), version: String = "1") =
        OutboxStore(db).enqueue(saved, "tags/${tag.id}", "PUT", "{\"name\":\"Local\",\"version\":\"1\"}",
            listOf(OutboxBase("tag", tag.id, version)), deps, listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag.copy(name = "Local")))), action)

    @Test fun orderedCommandsAndEffectsSurviveReopenAndSnapshotReplacement() = runBlocking<Unit> {
        val name = "outbox-reopen.db"; context.deleteDatabase(name); var db = open(name); val saved = session()
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        try {
            val tag = baseline(db, saved); val original = SnapshotStore(db).read(scope)
            val first = enqueue(db, saved, tag); val second = enqueue(db, saved, tag, listOf(first.actionId))
            val effects = db.outbox().overlays(first.position)
            assertEquals(original, SnapshotStore(db).read(scope)); assertTrue(first.position < second.position)
            assertTrue(db.outbox().commands(MirrorScope(saved.origin, id(), saved.workspaceId).key).isEmpty())
            db.close(); db = open(name)
            assertEquals(listOf(first, second), db.outbox().commands(scope.key)); assertEquals(effects, db.outbox().overlays(first.position))
            val meta = Snapshot(id(), scope.workspace, "2", "replacement", date, date, 0, "next", id())
            val store = SnapshotStore(db); store.begin(scope, meta)
            store.append(scope, "next", SnapshotPage(meta.id, scope.workspace, "2", emptyList(), null, meta.generation_id)); store.install(scope)
            assertEquals(listOf(first, second), db.outbox().commands(scope.key)); assertEquals(effects, db.outbox().overlays(first.position))
            assertEquals(saved.generationId, first.generation)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun rollbackDuplicateAndInvalidBasesLeaveNoPartialEffects() = runBlocking<Unit> {
        val name = "outbox-atomic.db"; context.deleteDatabase(name); val db = open(name); val saved = session()
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        try {
            val tag = baseline(db, saved)
            try { db.withTransaction { enqueue(db, saved, tag); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertTrue(db.outbox().commands(scope.key).isEmpty())
            assertEquals(0, db.openHelper.readableDatabase.query("SELECT count(*) FROM outbox_overlays").use { it.moveToFirst(); it.getInt(0) })
            val first = enqueue(db, saved, tag)
            try { enqueue(db, saved, tag, action = first.actionId); fail("Duplicate accepted") } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            try { enqueue(db, saved, tag, version = "2"); fail("Stale base accepted") } catch (_: IllegalArgumentException) { }
            try { enqueue(db, saved, tag, deps = listOf(id())); fail("Unknown dependency accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(listOf(first), db.outbox().commands(scope.key)); assertEquals(1, db.outbox().overlays(first.position).size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun dependentCancellationIsBlockedAndLeafRemovalCascades() = runBlocking<Unit> {
        val name = "outbox-cancel.db"; context.deleteDatabase(name); val db = open(name); val saved = session()
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        try {
            val tag = baseline(db, saved); val first = enqueue(db, saved, tag); val second = enqueue(db, saved, tag, listOf(first.actionId))
            try { OutboxStore(db).cancelQueued(scope, first.actionId); fail("Parent removed") } catch (_: IllegalArgumentException) { }
            assertEquals(2, db.outbox().commands(scope.key).size)
            OutboxStore(db).cancelQueued(scope, second.actionId); assertTrue(db.outbox().overlays(second.position).isEmpty())
            OutboxStore(db).cancelQueued(scope, first.actionId); assertTrue(db.outbox().commands(scope.key).isEmpty()); assertTrue(db.outbox().overlays(first.position).isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun migrationFromSevenPreservesConfirmedMirrorAndLegacyCommand() = runBlocking<Unit> {
        val name = "outbox-migration.db"; context.deleteDatabase(name); var db = open(name); val saved = session()
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        try {
            baseline(db, saved); val mirror = SnapshotStore(db).read(scope)
            val command = FinancialCommand(id(), saved.origin, saved.ownerId, saved.workspaceId, saved.sessionId, saved.generationId, "/workspaces/${saved.workspaceId}/tags", "POST", "{}")
            db.commands().insert(command); db.settings().save(ConnectionSettings(origin = saved.origin)); db.close()
            context.openOrCreateDatabase(name, 0, null).use { old ->
                old.execSQL("DROP TABLE outbox_overlays"); old.execSQL("DROP TABLE outbox_commands")
                old.execSQL("DROP TABLE room_master_table"); old.execSQL("PRAGMA user_version=7")
            }
            db = open(name); assertEquals(mirror, SnapshotStore(db).read(scope)); assertEquals(command, db.commands().get(saved.origin, saved.ownerId, saved.workspaceId))
            assertEquals(saved.origin, db.settings().get()?.origin); assertTrue(db.outbox().commands(scope.key).isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
