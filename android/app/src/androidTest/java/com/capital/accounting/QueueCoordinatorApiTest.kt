package com.capital.accounting

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.data.*
import com.capital.accounting.sync.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QueueCoordinatorApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun boundedConcurrentDrainResolvesLostReplyPullsAndStopsAtConflict() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("drain-${id()}@example.test", "synthetic drain password", "Queue drain", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "queue-drain-api.db"
        context.deleteDatabase(name); val db = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        try {
            SnapshotBootstrap(db).load(saved)
            val date = "2026-10-13T12:00:00Z"
            suspend fun enqueue(label: String, dependencies: List<String> = emptyList()): OutboxCommand {
                val tag = Tag(id(), scope.workspace, "1", date, date, null, label, null)
                return OutboxStore(db).enqueue(saved, "tags", "POST", ApiClient.json.encodeToString(TagCreate(tag.id, label)), emptyList(), dependencies, listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag))))
            }
            val first = enqueue("First"); val second = enqueue("Second", listOf(first.actionId)); enqueue("Third", listOf(second.actionId))
            val legacy = FinancialCommand(id(), origin, saved.ownerId, saved.workspaceId, saved.sessionId, saved.generationId, "/workspaces/${saved.workspaceId}/tags", "POST", "{}")
            db.commands().insert(legacy)
            assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(saved))
            assertEquals(first, db.outbox().commands(scope.key).first()); assertEquals("0", SnapshotStore(db).read(scope)!!.head.sequence)
            assertEquals(1, db.commands().remove(legacy.commandId, "pending"))
            val sending = OutboxLifecycle(db).claim(saved)!!
            api.request(sending.path, sending.method, sending.body, saved, sending.actionId, sending.generation)
            val bounded = QueueCoordinator(db).drain(saved, maximum = 1)
            assertEquals(QueueDrainResult(1, QueueDrainStatus.MORE), bounded)
            assertEquals("applied", db.outbox().commands(scope.key).first().state); assertNotNull(db.outcomes().get(first.position))
            assertTrue(db.outbox().overlays(first.position).isEmpty()); assertEquals("1", SnapshotStore(db).read(scope)!!.head.sequence)
            val results = coroutineScope { val a = async { QueueCoordinator(db).drain(saved) }; val b = async { QueueCoordinator(db).drain(saved) }; listOf(a.await(), b.await()) }
            assertEquals(2, results.sumOf { it.completed }); assertTrue(results.all { it.status == QueueDrainStatus.IDLE })
            val mirror = SnapshotStore(db).read(scope)!!; assertEquals("3", mirror.head.sequence); assertEquals(3, mirror.items.size)
            assertTrue(db.outbox().commands(scope.key).all { it.state == "applied" && db.outbox().overlays(it.position).isEmpty() })
            val tag = mirror.items.filterIsInstance<SnapshotItemTag>().first().data
            val stale = OutboxStore(db).enqueue(saved, "tags/${tag.id}", "PUT", ApiClient.json.encodeToString(TagUpdate("999", "Rejected", false)), listOf(OutboxBase("tag", tag.id, tag.version)), emptyList(), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag.copy(name = "Rejected")))))
            val after = enqueue("Must wait")
            assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(saved))
            assertEquals("rejected", db.outbox().commands(scope.key).find { it.actionId == stale.actionId }!!.state)
            assertEquals(after, db.outbox().commands(scope.key).last()); assertEquals("3", MirrorSynchronizer(db).refresh(saved).head.sequence)
            assertEquals(3, SnapshotStore(db).read(scope)!!.items.size)
        } finally { db.close(); context.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
}
