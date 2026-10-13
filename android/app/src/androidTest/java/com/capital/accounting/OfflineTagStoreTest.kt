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
class OfflineTagStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private fun id() = UUID.randomUUID().toString()
    private val date = "2026-10-13T12:00:00Z"
    private fun open(name: String) = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
    private fun auth() = BearerAuth(Profile(id(), "offline-tag@example.test", "UTC"), Workspace(id(), "Offline tags", "1", "owner", id()), Session(id(), "Device", true, date, date, "2030-10-12T12:00:00Z", "2030-10-13T12:00:00Z"), "bearer", "x".repeat(64), "Bearer")
    private suspend fun seed(db: CapitalDatabase, auth: BearerAuth): Tag {
        val saved = auth.credential("https://accounting.example"); IdentityStore(db).save(auth, saved.origin)
        val tag = Tag(id(), saved.workspaceId, "1", date, date, null, "Existing", null)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId); val meta = Snapshot(id(), scope.workspace, "0", "cursor", date, date, 1, "first", saved.generationId)
        val store = SnapshotStore(db); store.begin(scope, meta); store.append(scope, "first", SnapshotPage(meta.id, scope.workspace, "0", listOf(SnapshotItemTag("tag", tag)), null, saved.generationId)); store.install(scope); return tag
    }
    @Test fun multiCreateAndObservedArchiveReopenWithoutChangingConfirmed() = runBlocking<Unit> {
        val name = "offline-tags.db"; context.deleteDatabase(name); var db = open(name); val auth = auth(); val saved = auth.credential("https://accounting.example")
        try {
            val tag = seed(db, auth); val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId); val original = SnapshotStore(db).read(scope)
            val store = OfflineTagStore(db)
            val first = store.enqueue(saved, ApiClient.json.encodeToString(TagCreate(id(), "First")))
            val second = store.enqueue(saved, ApiClient.json.encodeToString(TagCreate(id(), "Second")))
            val body = ApiClient.json.encodeToString(TagUpdate("1", "Archived", true)); val archived = store.enqueue(saved, body, tag.id)
            db.close(); db = open(name)
            assertEquals(listOf(first, second, archived), db.outbox().commands(scope.key)); assertEquals(body, archived.body)
            val projection = LocalProjectionStore(db).read(scope)!!; assertEquals(3, projection.visible.tags.size); assertNotNull(projection.visible.tags.first { it.id == tag.id }.archived_at)
            assertEquals(original, projection.confirmed); assertEquals(3, projection.pending.size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun rollbackStaleVersionRepeatedEditAndMissingIdentityNeverQueue() = runBlocking<Unit> {
        val name = "offline-tags-guards.db"; context.deleteDatabase(name); val db = open(name); val auth = auth(); val saved = auth.credential("https://accounting.example")
        try {
            val tag = seed(db, auth); val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
            try { db.withTransaction { OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagCreate(id(), "Rollback"))); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertTrue(db.outbox().commands(scope.key).isEmpty())
            try { OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagUpdate("2", "Stale", false)), tag.id); fail("Stale accepted") } catch (_: IllegalArgumentException) { }
            val first = OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagUpdate("1", "First", false)), tag.id)
            try { OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagUpdate("1", "Again", false)), tag.id); fail("Pending entity changed") } catch (_: IllegalArgumentException) { }
            db.identities().remove(scope.key)
            try { OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagCreate(id(), "No identity"))); fail("Missing identity accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(listOf(first), db.outbox().commands(scope.key))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
