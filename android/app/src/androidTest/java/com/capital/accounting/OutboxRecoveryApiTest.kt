package com.capital.accounting

import androidx.room.Room
import androidx.room.withTransaction
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
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutboxRecoveryApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun rejectedBranchDismissalRetainsEvidenceAndUnblocksIndependentOriginalCommand() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(
            Register("queue-recovery-${id()}@example.test", "synthetic recovery password", "Recovery", "bearer", "UTC"))))
        val saved = auth.credential(origin); val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId)
        val app = ApplicationProvider.getApplicationContext<CapitalApplication>(); val name = "outbox-recovery-api.db"
        app.deleteDatabase(name); fun open() = Room.databaseBuilder(app, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            val tag = decodeResponse<MutationResult>(api.request("/workspaces/${saved.workspaceId}/tags", "POST",
                ApiClient.json.encodeToString(TagCreate(id(), "Original")), saved, id(), saved.generationId)).tags.single()
            IdentityStore(db).save(auth, origin); SnapshotBootstrap(db).load(saved)
            val tags = OfflineTagStore(db)
            val root = tags.enqueue(saved, ApiClient.json.encodeToString(TagUpdate("1", "Proposed", false)), tag.id)
            val child = tags.enqueue(saved, ApiClient.json.encodeToString(TagUpdate("2", "Dependent", false)), tag.id)
            val independent = tags.enqueue(saved, ApiClient.json.encodeToString(TagCreate(id(), "Independent")))
            api.request("/workspaces/${saved.workspaceId}/tags/${tag.id}", "PUT", ApiClient.json.encodeToString(TagUpdate("1", "Other client", false)), saved, id(), saved.generationId)
            assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(saved))
            val rejected = db.outbox().commands(scope.key).first(); assertEquals("rejected", rejected.state)
            assertEquals("version_conflict", db.failures().get(rejected.position)!!.code)
            assertEquals("rejected", OutboxReconciler(db).inspect(saved, root.actionId)!!.state)
            val recovery = OutboxRecoveryStore(db); val plan = recovery.preview(saved, root.actionId)
            assertEquals(listOf(root.actionId, child.actionId), plan.commands.map { it.actionId })
            val effects = plan.commands.associate { it.position to db.outbox().overlays(it.position) }
            val before = db.outbox().commands(scope.key); val failure = db.failures().get(root.position); val outcome = db.outcomes().get(root.position)
            try { db.withTransaction { recovery.dismiss(saved, plan); throw IOException("Synthetic interruption") }; fail("Missing rollback") } catch (_: IOException) { }
            assertEquals(before, db.outbox().commands(scope.key))
            recovery.dismiss(saved, plan)
            assertEquals(before.map { if (it.actionId in setOf(root.actionId, child.actionId)) it.copy(state = "dismissed") else it }, db.outbox().commands(scope.key))
            for ((position, original) in effects) assertEquals(original, db.outbox().overlays(position))
            assertEquals(failure, db.failures().get(root.position)); assertEquals(outcome, db.outcomes().get(root.position))
            assertNull(db.receipts().get(independent.position))
            val projection = LocalProjectionStore(db).read(scope)!!; assertTrue(projection.blocked.isEmpty()); assertEquals(1, projection.pending.size)
            assertEquals("Other client", projection.visible.tags.single { it.id == tag.id }.name)
            db.close(); db = open()
            assertEquals(QueueDrainResult(1, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            assertEquals(setOf("Other client", "Independent"), api.tags(saved).map { it.name }.toSet())
            assertEquals(independent.copy(state = "applied"), db.outbox().commands(scope.key).single { it.actionId == independent.actionId })
            assertEquals("3", SnapshotStore(db).read(scope)!!.head.sequence)
            assertEquals(QueueDrainResult(0, QueueDrainStatus.IDLE), QueueCoordinator(db).drain(saved))
            val next = OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagCreate(id(), "Never sent")))
            val pendingPlan = OutboxRecoveryStore(db).preview(saved, next.actionId)
            val sending = OutboxLifecycle(db).claim(saved)!!
            OutboxLifecycle(db).failed(sending, "uncertain")
            try { OutboxRecoveryStore(db).dismiss(saved, pendingPlan); fail("Sending race accepted") } catch (_: IllegalArgumentException) { }
            assertNull(OutboxReconciler(db).inspect(saved, next.actionId))
            try { OutboxRecoveryStore(db).preview(saved, next.actionId); fail("Unknown result dismissed") } catch (_: IllegalArgumentException) { }
        } finally { db.close(); app.deleteDatabase(name); api.request("/auth/logout", "POST", session = saved) }
    }
}
