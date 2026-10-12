package com.capital.accounting

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.capital.accounting.api.*
import com.capital.accounting.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PullStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private fun id() = UUID.randomUUID().toString()
    private fun open(name: String) = Room.databaseBuilder(context, CapitalDatabase::class.java, name).addMigrations(CapitalDatabase.MIGRATION_1_2, CapitalDatabase.MIGRATION_2_3, CapitalDatabase.MIGRATION_3_4, CapitalDatabase.MIGRATION_4_5, CapitalDatabase.MIGRATION_5_6).build()
    private val date = "2026-10-12T12:00:00Z"
    private suspend fun baseline(db: CapitalDatabase, scope: MirrorScope): Tag {
        val tag = Tag(id(), scope.workspace, "1", date, date, null, "Original", null)
        val meta = Snapshot(id(), scope.workspace, "1", "cursor1", date, date, 1, "first", id())
        val store = SnapshotStore(db); store.begin(scope, meta)
        store.append(scope, "first", SnapshotPage(meta.id, scope.workspace, "1", listOf(SnapshotItemTag("tag", tag)), null, meta.generation_id)); store.install(scope)
        return tag
    }
    private fun up(head: MirrorHead, scope: MirrorScope, tag: Tag, sequence: String, change: String = id()): WorkspaceChangeTagUpsert =
        WorkspaceChangeTagUpsert(1, change, id(), scope.workspace, sequence, 1, 1, date, "upsert", "tag", tag, head.generation)
    private fun deleted(head: MirrorHead, scope: MirrorScope, tag: Tag) = WorkspaceChangeTagDelete(1, id(), id(), scope.workspace, "3", 1, 1, date, "delete", "tag", Tombstone("tag", tag.id, scope.workspace, "3", date), head.generation)
    private fun page(head: MirrorHead, scope: MirrorScope, vararg changes: WorkspaceChange) = SyncChanges(scope.workspace,
        changes.map { SyncGroup(it.sequence, it.action_id, scope.workspace, listOf(it), head.generation) }, "cursor${changes.last().sequence}", false, changes.last().sequence, "1", head.generation)

    @Test fun wholePageRollbackCursorAndSeenIdsSurviveReopenWithoutChangingCommand() = runBlocking<Unit> {
        val name = "pull-atomic.db"; context.deleteDatabase(name); val scope = MirrorScope("https://accounting.example", id(), id()); var db = open(name)
        try {
            val tag = baseline(db, scope); val original = SnapshotStore(db).read(scope)!!
            val command = FinancialCommand(id(), scope.origin, scope.owner, scope.workspace, id(), id(), "/workspaces/${scope.workspace}/accounts", "POST", "{}")
            db.commands().insert(command)
            val updated = up(original.head, scope, tag.copy(version = "2", name = "Changed"), "2")
            val removed = deleted(original.head, scope, tag); val page = page(original.head, scope, updated, removed)
            try { db.withTransaction { PullStore(db).apply(scope, original.head, page); throw IOException("Synthetic commit interruption") }; fail("Fault missing") } catch (_: IOException) { }
            assertEquals(original, SnapshotStore(db).read(scope)); assertEquals(0, db.pull().seen(scope.key, updated.change_id)); assertTrue(db.pull().tombstones(scope.key).isEmpty())
            PullStore(db).apply(scope, original.head, page)
            db.close(); db = open(name)
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("3", mirror.head.sequence); assertEquals("cursor3", mirror.head.cursor); assertTrue(mirror.items.isEmpty())
            assertEquals(1, db.pull().seen(scope.key, updated.change_id)); assertEquals("3", db.pull().tombstones(scope.key).single().version)
            assertEquals(command, db.commands().get(scope.origin, scope.owner, scope.workspace))
            try { PullStore(db).apply(scope, original.head, page); fail("Stale captured head accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(mirror, SnapshotStore(db).read(scope))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun tombstoneBlocksOlderUpsertAndEqualVersionsRequireIdenticalData() = runBlocking<Unit> {
        val name = "pull-versions.db"; context.deleteDatabase(name); val scope = MirrorScope("https://accounting.example", id(), id()); val db = open(name)
        try {
            val tag = baseline(db, scope); val head = SnapshotStore(db).read(scope)!!.head
            val bad = up(head, scope, tag.copy(name = "Different same version"), "2")
            try { PullStore(db).apply(scope, head, page(head, scope, bad)); fail("Unequal data accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(head, SnapshotStore(db).read(scope)?.head); assertEquals(0, db.pull().seen(scope.key, bad.change_id))
            // A newer confirmed deletion may arrive before its pull cursor catches up.
            val removed = deleted(head, scope, tag)
            db.pull().delete(scope.key, "tag", tag.id)
            db.pull().tombstone(MirrorTombstone(scope.key, "tag", tag.id, "3", ApiClient.json.encodeToString(removed.data)))
            val old = up(head, scope, tag.copy(version = "2"), "2")
            PullStore(db).apply(scope, head, page(head, scope, old))
            val current = SnapshotStore(db).read(scope)!!.head
            assertTrue(SnapshotStore(db).read(scope)!!.items.isEmpty())
            PullStore(db).apply(scope, current, page(current, scope, removed))
            assertTrue(SnapshotStore(db).read(scope)!!.items.isEmpty()); assertEquals("3", db.pull().tombstones(scope.key).single().version)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun duplicateChangeIdAndLateSnapshotCannotMoveCursorBackward() = runBlocking<Unit> {
        val name = "pull-duplicates.db"; context.deleteDatabase(name); val scope = MirrorScope("https://accounting.example", id(), id()); val db = open(name)
        try {
            val tag = baseline(db, scope); val original = SnapshotStore(db).read(scope)!!.head
            val change = up(original, scope, tag.copy(version = "2"), "2")
            PullStore(db).apply(scope, original, page(original, scope, change))
            val head = SnapshotStore(db).read(scope)!!.head
            val duplicate = up(head, scope, tag.copy(version = "3"), "3", change.change_id)
            try { PullStore(db).apply(scope, head, page(head, scope, duplicate)); fail("Duplicate change accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(head, SnapshotStore(db).read(scope)?.head)
            val old = Snapshot(id(), scope.workspace, "1", "old-cursor", date, date, 0, "old-first", head.generation)
            val store = SnapshotStore(db); store.begin(scope, old); store.append(scope, old.first_page_token, SnapshotPage(old.id, scope.workspace, "1", emptyList(), null, head.generation))
            try { store.install(scope); fail("Mirror regressed") } catch (_: SnapshotSuperseded) { }
            assertEquals(head, store.read(scope)?.head)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
