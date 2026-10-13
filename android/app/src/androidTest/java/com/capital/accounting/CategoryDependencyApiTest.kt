package com.capital.accounting

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
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
class CategoryDependencyApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun offlineHierarchyCycleRollbackArchiveAndOtherClientConflict() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!); val email = "category-dependency-${id()}@example.test"; val password = "synthetic category password"
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register(email, password, "Primary", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "category-dependency-api.db"
        context.deleteDatabase(name); fun open() = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val parentId = id(); val childId = id(); var store = OfflineCategoryStore(db)
            val parent = store.enqueue(saved, ApiClient.json.encodeToString(CategoryCreate(parentId, "Parent", null)))
            val child = store.enqueue(saved, ApiClient.json.encodeToString(CategoryCreate(childId, "Child", parentId)))
            assertEquals(listOf(parent.actionId), ApiClient.json.decodeFromString<List<String>>(child.dependencies))
            try { store.enqueue(saved, ApiClient.json.encodeToString(CategoryUpdate("1", "Cycle", false, childId)), parentId); fail("Cycle accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(listOf(parent, child), db.outbox().commands(scope.key))
            val edited = store.enqueue(saved, ApiClient.json.encodeToString(CategoryUpdate("1", "Edited", false, parentId)), childId)
            val archived = store.enqueue(saved, ApiClient.json.encodeToString(CategoryUpdate("1", "Archived parent", true, null)), parentId)
            val retained = store.enqueue(saved, ApiClient.json.encodeToString(CategoryUpdate("2", "Retained child", false, parentId)), childId)
            try { store.enqueue(saved, ApiClient.json.encodeToString(CategoryCreate(id(), "Invalid archived parent", parentId))); fail("Archived parent accepted") } catch (_: IllegalArgumentException) { }
            val commands = listOf(parent, child, edited, archived, retained); db.close(); db = open(); store = OfflineCategoryStore(db)
            assertEquals(commands, db.outbox().commands(scope.key)); assertTrue(SnapshotStore(db).read(scope)!!.items.isEmpty())
            assertEquals(QueueDrainResult(5, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            assertEquals(commands.map { it.copy(state = "applied") }, db.outbox().commands(scope.key))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("5", mirror.head.sequence)
            val categories = mirror.items.filterIsInstance<SnapshotItemCategory>().map { it.data }
            assertNotNull(categories.first { it.id == parentId }.archived_at)
            val current = categories.first { it.id == childId }; assertEquals(parentId, current.parent_id); assertEquals("3", current.version)
            val stale = store.enqueue(saved, ApiClient.json.encodeToString(CategoryUpdate("3", "Local parent", false, parentId)), childId)
            val dependent = store.enqueue(saved, ApiClient.json.encodeToString(CategoryUpdate("4", "Local child", false, parentId)), childId)
            val other = decodeResponse<BearerAuth>(api.request("/auth/login", "POST", ApiClient.json.encodeToString(Login(email, password, "Other client", "bearer")))).credential(origin)
            try {
                api.request("/workspaces/${scope.workspace}/categories/$childId", "PUT", ApiClient.json.encodeToString(CategoryUpdate("3", "Other client", false, parentId)), other, id(), other.generationId)
                assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(saved))
                assertEquals(stale.copy(state = "rejected"), db.outbox().commands(scope.key).first { it.actionId == stale.actionId })
                assertEquals(dependent, db.outbox().commands(scope.key).last())
                val projection = LocalProjectionStore(db).read(scope)!!; assertEquals(2, projection.blocked.size)
                assertEquals("Other client", projection.visible.categories.first { it.id == childId }.name)
                assertEquals("6", projection.confirmed.head.sequence)
            } finally { api.request("/auth/logout", "POST", session = other) }
        } finally { db.close(); context.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
}
