package com.capital.accounting

import androidx.room.Room
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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalProjectionTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private fun id() = UUID.randomUUID().toString()
    private val date = "2026-10-12T12:00:00Z"
    private fun open(name: String) = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
    private fun session() = StoredSession("https://accounting.example", "x".repeat(64), id(), id(), id(), id())
    private fun scope(s: StoredSession) = MirrorScope(s.origin, s.ownerId, s.workspaceId)
    private suspend fun baseline(db: CapitalDatabase, s: StoredSession): Tag {
        val tag = Tag(id(), s.workspaceId, "1", date, date, null, "Confirmed", null)
        val meta = Snapshot(id(), s.workspaceId, "0", "cursor", date, date, 1, "first", s.generationId)
        val store = SnapshotStore(db); store.begin(scope(s), meta); store.append(scope(s), "first", SnapshotPage(meta.id, s.workspaceId, "0", listOf(SnapshotItemTag("tag", tag)), null, s.generationId)); store.install(scope(s)); return tag
    }
    private suspend fun up(db: CapitalDatabase, s: StoredSession, tag: Tag, deps: List<String> = emptyList()) = OutboxStore(db).enqueue(s, "tags", "POST", "{}", emptyList(), deps, listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag))))
    @Test fun orderedCreateEditDeleteReopenAndCancelNeverOverwriteConfirmed() = runBlocking<Unit> {
        val name = "local-projection-order.db"; context.deleteDatabase(name); var db = open(name); val s = session()
        try {
            val tag = baseline(db, s); val original = SnapshotStore(db).read(scope(s))!!
            val created = up(db, s, tag.copy(id = id(), name = "Created")); val edited = up(db, s, tag.copy(name = "Edited"))
            val deleted = OutboxStore(db).enqueue(s, "tags/${tag.id}", "DELETE", "{}", emptyList(), listOf(edited.actionId), listOf(OverlayEffect.delete("tag", tag.id)))
            var projection = LocalProjectionStore(db).read(scope(s))!!
            assertEquals(original, projection.confirmed); assertEquals(listOf(created, edited, deleted), projection.pending)
            assertEquals("Created", projection.visible.tags.single().name); assertTrue(projection.blocked.isEmpty())
            db.close(); db = open(name); assertEquals(projection, LocalProjectionStore(db).read(scope(s)))
            OutboxStore(db).cancelQueued(scope(s), deleted.actionId)
            projection = LocalProjectionStore(db).read(scope(s))!!; assertEquals(setOf("Created", "Edited"), projection.visible.tags.map { it.name }.toSet())
            assertEquals(original, SnapshotStore(db).read(scope(s))); assertNull(LocalProjectionStore(db).read(MirrorScope(s.origin, id(), s.workspaceId)))
            val sending = OutboxLifecycle(db).claim(s)!!
            val createdTag = decodeResponse<SnapshotItemTag>(db.outbox().overlays(created.position).single().payload!!).data
            OutboxReceiptStore(db).applied(sending, ApiClient.json.encodeToString(MutationResult(created.actionId, emptyList(), emptyList(), emptyList(), listOf(createdTag), emptyList(), "1", s.generationId)))
            val meta = Snapshot(id(), s.workspaceId, "0", "new-cursor", date, date, 0, "new-first", id())
            val store = SnapshotStore(db); store.begin(scope(s), meta); store.append(scope(s), "new-first", SnapshotPage(meta.id, s.workspaceId, "0", emptyList(), null, meta.generation_id)); store.install(scope(s))
            projection = LocalProjectionStore(db).read(scope(s))!!
            assertEquals(listOf(edited.actionId), projection.blocked.map { it.actionId }); assertTrue(projection.pending.isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun rejectedParentBlocksDependentEffectsAndOldGenerationStaysSeparate() = runBlocking<Unit> {
        val name = "local-projection-blocked.db"; context.deleteDatabase(name); val db = open(name); val s = session()
        try {
            val tag = baseline(db, s); val first = up(db, s, tag.copy(name = "Parent")); val second = up(db, s, tag.copy(name = "Dependent"), listOf(first.actionId))
            val sending = OutboxLifecycle(db).claim(s)!!; OutboxLifecycle(db).failed(sending, "rejected")
            var projection = LocalProjectionStore(db).read(scope(s))!!
            assertEquals("Confirmed", projection.visible.tags.single().name); assertTrue(projection.pending.isEmpty()); assertEquals(2, projection.blocked.size)
            assertEquals(1, db.outbox().overlays(second.position).size)
            val newHead = projection.confirmed.head.copy(generation = id()); db.snapshots().head(newHead)
            projection = LocalProjectionStore(db).read(scope(s))!!; assertEquals("Confirmed", projection.visible.tags.single().name)
            assertEquals(listOf(first.actionId, second.actionId), projection.blocked.map { it.actionId }); assertTrue(projection.affected.isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
