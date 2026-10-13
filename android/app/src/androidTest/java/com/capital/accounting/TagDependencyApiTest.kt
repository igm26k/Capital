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
class TagDependencyApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun createEditArchiveChainAndOtherClientConflictNeverRewriteBodies() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!); val email = "tag-dependency-${id()}@example.test"; val password = "synthetic dependency password"
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register(email, password, "Primary", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "tag-dependency-api.db"
        context.deleteDatabase(name); val db = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        try {
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val tagId = id(); val store = OfflineTagStore(db)
            val first = store.enqueue(saved, ApiClient.json.encodeToString(TagCreate(tagId, "Created")))
            val second = store.enqueue(saved, ApiClient.json.encodeToString(TagUpdate("1", "Edited", false)), tagId)
            val third = store.enqueue(saved, ApiClient.json.encodeToString(TagUpdate("2", "Archived", true)), tagId)
            assertEquals(QueueDrainResult(3, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            assertEquals(listOf(first, second, third).map { it.copy(state = "applied") }, db.outbox().commands(scope.key))
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("3", mirror.head.sequence)
            val tag = mirror.items.filterIsInstance<SnapshotItemTag>().single().data; assertEquals("3", tag.version); assertEquals("Archived", tag.name); assertNotNull(tag.archived_at)
            val parent = store.enqueue(saved, ApiClient.json.encodeToString(TagUpdate("3", "Local parent", true)), tagId)
            val child = store.enqueue(saved, ApiClient.json.encodeToString(TagUpdate("4", "Local child", false)), tagId)
            val other = decodeResponse<BearerAuth>(api.request("/auth/login", "POST", ApiClient.json.encodeToString(Login(email, password, "Other client", "bearer")))).credential(origin)
            assertNotEquals(saved.sessionId, other.sessionId)
            try {
                api.request("/workspaces/${scope.workspace}/tags/$tagId", "PUT", ApiClient.json.encodeToString(TagUpdate("3", "Other client version", true)), other, id(), other.generationId)
                assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(saved))
                val rows = db.outbox().commands(scope.key)
                assertEquals(parent.copy(state = "rejected"), rows.first { it.actionId == parent.actionId }); assertEquals(child, rows.last())
                assertEquals("version_conflict", db.failures().get(parent.position)!!.code)
                val projected = LocalProjectionStore(db).read(scope)!!; assertEquals(2, projected.blocked.size)
                assertEquals("Other client version", projected.visible.tags.single().name); assertEquals("4", projected.confirmed.head.sequence)
                assertEquals("4", decodeResponse<TagUpdate>(child.body).expected_version)
            } finally { api.request("/auth/logout", "POST", session = other) }
        } finally { db.close(); context.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
}
