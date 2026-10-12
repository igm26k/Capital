package com.capital.accounting

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.capital.accounting.api.*
import com.capital.accounting.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SnapshotStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private fun id() = UUID.randomUUID().toString()
    private fun open(name: String) = Room.databaseBuilder(context, CapitalDatabase::class.java, name)
        .addMigrations(CapitalDatabase.MIGRATION_1_2, CapitalDatabase.MIGRATION_2_3, CapitalDatabase.MIGRATION_3_4, CapitalDatabase.MIGRATION_4_5, CapitalDatabase.MIGRATION_5_6, CapitalDatabase.MIGRATION_6_7, CapitalDatabase.MIGRATION_7_8).build()
    private fun snapshot(scope: MirrorScope, count: Int, sequence: String) = Snapshot(id(), scope.workspace, sequence, "cursor-$sequence", "2026-10-12T12:00:00Z", "2026-10-12T12:15:00Z", count, "first-${id()}", id())
    private fun tag(scope: MirrorScope, name: String, key: String = id()): SnapshotItem = SnapshotItemTag("tag", Tag(key, scope.workspace, "1", "2026-10-12T12:00:00Z", "2026-10-12T12:00:00Z", null, name, null))
    private fun page(meta: Snapshot, items: List<SnapshotItem>, next: String? = null) = SnapshotPage(meta.id, meta.workspace_id, meta.base_sequence, items, next, meta.generation_id)

    @Test fun stagingReopenRollbackAtomicReplacementAndScopeIsolationPreserveCommand() = runBlocking<Unit> {
        val name = "snapshot-atomic-test.db"; context.deleteDatabase(name)
        val scope = MirrorScope("https://accounting.example", id(), id())
        var db = open(name); var store = SnapshotStore(db)
        try {
            val command = FinancialCommand(id(), scope.origin, scope.owner, scope.workspace, id(), id(), "/workspaces/${scope.workspace}/accounts", "POST", "{}")
            db.commands().insert(command)
            val old = snapshot(scope, 1, "1"); store.begin(scope, old); store.append(scope, old.first_page_token, page(old, listOf(tag(scope, "Old")))); store.install(scope)
            val baseline = store.read(scope)!!
            val next = snapshot(scope, 2, "2")
            val keys = listOf(id(), id()).sorted(); val first = tag(scope, "First", keys[0]); val second = tag(scope, "Second", keys[1])
            store.begin(scope, next); store.append(scope, next.first_page_token, page(next, listOf(first), "second-token"))
            db.close(); db = open(name); store = SnapshotStore(db)
            assertEquals(baseline, store.read(scope)); assertEquals(1, db.snapshots().stage(scope.key)?.received)
            assertNull(store.read(scope.copy(owner = id()))); assertNull(store.read(scope.copy(origin = "https://other.example")))
            try { store.append(scope, "second-token", page(next, listOf(second)).copy(workspace_id = id())); fail("Foreign page accepted") } catch (_: IllegalArgumentException) { }
            try { store.append(scope, "second-token", page(next, listOf(first))); fail("Duplicate accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(1, db.snapshots().stage(scope.key)?.received); assertEquals(baseline, store.read(scope))
            store.append(scope, "second-token", page(next, listOf(second)))
            try { db.withTransaction { store.install(scope); throw IOException("Synthetic commit interruption") }; fail("Fault missing") } catch (_: IOException) { }
            assertEquals(baseline, store.read(scope)); assertTrue(db.snapshots().stage(scope.key)!!.complete)
            assertEquals(command, db.commands().get(scope.origin, scope.owner, scope.workspace))
            store.install(scope)
            assertEquals(next.generation_id, store.read(scope)?.head?.generation); assertEquals(next.resume_cursor, store.read(scope)?.head?.cursor)
            assertEquals(setOf("First", "Second"), store.read(scope)!!.items.filterIsInstance<SnapshotItemTag>().map { it.data.name }.toSet())
            assertNull(db.snapshots().stage(scope.key)); assertTrue(db.snapshots().staged(scope.key).isEmpty())
            val broken = snapshot(scope, 1, "3")
            val category = Category(id(), scope.workspace, "1", "2026-10-12T12:00:00Z", "2026-10-12T12:00:00Z", null, "Orphan", null, id())
            store.begin(scope, broken); store.append(scope, broken.first_page_token, page(broken, listOf(SnapshotItemCategory("category", category))))
            try { store.install(scope); fail("Orphan graph accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(next.generation_id, store.read(scope)?.head?.generation)
            store.discard(scope, id()); assertNotNull(db.snapshots().stage(scope.key))
            store.discard(scope, broken.id); assertNull(db.snapshots().stage(scope.key))
            val empty = snapshot(scope, 0, "0"); store.begin(scope, empty); store.append(scope, empty.first_page_token, page(empty, emptyList())); store.install(scope)
            assertTrue(store.read(scope)!!.items.isEmpty()); assertEquals(empty.generation_id, store.read(scope)?.head?.generation)
            assertEquals(command, db.commands().get(scope.origin, scope.owner, scope.workspace))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun versionThreeMigrationPreservesRejectedCommandAndRequestReference() = runBlocking<Unit> {
        val name = "snapshot-v3-migration.db"; context.deleteDatabase(name)
        val scope = MirrorScope("https://accounting.example", id(), id()); val command = id(); val reference = id()
        context.openOrCreateDatabase(name, 0, null).use { old ->
            old.execSQL("CREATE TABLE connection_settings (id INTEGER NOT NULL PRIMARY KEY, origin TEXT NOT NULL)")
            old.execSQL("INSERT INTO connection_settings VALUES (1,'https://accounting.example')")
            old.execSQL("CREATE TABLE financial_commands (commandId TEXT NOT NULL PRIMARY KEY, origin TEXT NOT NULL, ownerId TEXT NOT NULL, workspaceId TEXT NOT NULL, sessionId TEXT NOT NULL, generationId TEXT NOT NULL, path TEXT NOT NULL, method TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL, result TEXT, errorCode TEXT, requestId TEXT)")
            old.execSQL("CREATE UNIQUE INDEX index_financial_commands_origin_ownerId_workspaceId ON financial_commands(origin,ownerId,workspaceId)")
            old.execSQL("INSERT INTO financial_commands VALUES (?, ?, ?, ?, ?, ?, ?, 'POST', '{}', 'rejected', NULL, 'version_conflict', ?)", arrayOf(command, scope.origin, scope.owner, scope.workspace, id(), id(), "/workspaces/${scope.workspace}/accounts", reference))
            old.execSQL("PRAGMA user_version=3")
        }
        val db = open(name)
        try {
            val saved = db.commands().get(scope.origin, scope.owner, scope.workspace)!!
            assertEquals(command, saved.commandId); assertEquals(reference, saved.requestId); assertEquals("{}", saved.body)
            assertEquals(scope.origin, db.settings().get()?.origin)
            assertNull(SnapshotStore(db).read(scope)); assertNull(db.snapshots().stage(scope.key))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun invalidPageEnvelopesCountsAndTokenCyclesKeepPersistedProgress() = runBlocking<Unit> {
        val name = "snapshot-page-guards.db"; context.deleteDatabase(name)
        val scope = MirrorScope("https://accounting.example", id(), id()); val db = open(name)
        try {
            val store = SnapshotStore(db); val meta = snapshot(scope, 2, "1")
            val keys = listOf(id(), id()).sorted()
            store.begin(scope, meta); store.append(scope, meta.first_page_token, page(meta, listOf(tag(scope, "One", keys[0])), "next"))
            store.begin(scope, meta)
            val baseline = db.snapshots().stage(scope.key)
            val good = page(meta, listOf(tag(scope, "Two", keys[1])))
            val bad = listOf(good.copy(snapshot_id = id()), good.copy(workspace_id = id()), good.copy(generation_id = id()), good.copy(base_sequence = "2"),
                good.copy(items = emptyList()), good.copy(next_page_token = meta.first_page_token), good.copy(next_page_token = "next"))
            for (invalid in bad) {
                try { store.append(scope, "next", invalid); fail("Invalid page accepted") } catch (_: IllegalArgumentException) { }
                assertEquals(baseline, db.snapshots().stage(scope.key)); assertEquals(1, db.snapshots().staged(scope.key).size)
                assertNull(store.read(scope))
            }
            try { store.begin(scope, meta.copy(id = id(), item_count = 50001)); fail("Oversized snapshot accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(baseline, db.snapshots().stage(scope.key))
            store.append(scope, "next", good); store.install(scope); assertEquals(2, store.read(scope)?.items?.size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun versionFourMigrationKeepsConfirmedMirrorAndPartialStaging() = runBlocking<Unit> {
        val name = "snapshot-v4-migration.db"; context.deleteDatabase(name)
        val scope = MirrorScope("https://accounting.example", id(), id()); var db = open(name)
        try {
            val store = SnapshotStore(db); val old = snapshot(scope, 1, "1")
            store.begin(scope, old); store.append(scope, old.first_page_token, page(old, listOf(tag(scope, "Confirmed")))); store.install(scope)
            val confirmed = store.read(scope); val next = snapshot(scope, 2, "2")
            store.begin(scope, next); store.append(scope, next.first_page_token, page(next, listOf(tag(scope, "Staged")), "next-token"))
            val staged = db.snapshots().stage(scope.key)
            db.close()
            // The v4 tables are unchanged in v5; remove only the added table from this private fixture.
            context.openOrCreateDatabase(name, 0, null).use { legacy ->
                legacy.execSQL("DROP TABLE snapshot_attempts")
                legacy.execSQL("DROP TABLE mirror_tombstones")
                legacy.execSQL("DROP TABLE mirror_changes")
                legacy.execSQL("DROP TABLE cached_identities")
                legacy.execSQL("DROP TABLE outbox_overlays"); legacy.execSQL("DROP TABLE outbox_commands")
                legacy.execSQL("DROP TABLE room_master_table")
                legacy.execSQL("PRAGMA user_version=4")
            }
            db = open(name)
            assertEquals(confirmed, SnapshotStore(db).read(scope)); assertEquals(staged, db.snapshots().stage(scope.key))
            assertEquals(1, db.snapshots().staged(scope.key).size); assertNull(db.snapshots().attempt(scope.key))
            db.snapshots().start(SnapshotAttempt(scope.key, next.id))
            db.close()
            context.openOrCreateDatabase(name, 0, null).use { legacy ->
                legacy.execSQL("DROP TABLE mirror_tombstones"); legacy.execSQL("DROP TABLE mirror_changes")
                legacy.execSQL("DROP TABLE cached_identities")
                legacy.execSQL("DROP TABLE outbox_overlays"); legacy.execSQL("DROP TABLE outbox_commands")
                legacy.execSQL("DROP TABLE room_master_table"); legacy.execSQL("PRAGMA user_version=5")
            }
            db = open(name)
            assertEquals(confirmed, SnapshotStore(db).read(scope)); assertEquals(staged, db.snapshots().stage(scope.key))
            assertEquals(next.id, db.snapshots().attempt(scope.key)?.snapshot)
            assertTrue(db.pull().tombstones(scope.key).isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

}
