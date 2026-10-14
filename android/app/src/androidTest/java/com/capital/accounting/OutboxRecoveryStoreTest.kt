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
class OutboxRecoveryStoreTest {
    private fun id() = UUID.randomUUID().toString()
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private suspend fun seed(db: CapitalDatabase): com.capital.accounting.auth.StoredSession {
        val auth = BearerAuth(Profile(id(), "recovery@example.test", "UTC"), Workspace(id(), "Recovery", "1", "owner", id()),
            Session(id(), "Device", true, "2026-10-13T12:00:00Z", "2026-10-13T12:00:00Z", "2030-10-13T12:00:00Z", "2030-10-14T12:00:00Z"), "bearer", "x".repeat(64), "Bearer")
        val saved = auth.credential("https://accounting.example"); IdentityStore(db).save(auth, saved.origin)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        db.snapshots().head(MirrorHead(scope.key, saved.generationId, "0", "cursor")); return saved
    }
    @Test fun previewChangesAbortWholeBranchAndDismissalSurvivesReopenWithOriginalBodies() = runBlocking<Unit> {
        val name = "recovery-preview.db"; app.deleteDatabase(name); fun open() = Room.databaseBuilder(app, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            val saved = seed(db); val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId); val tag = id()
            val root = OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagCreate(tag, "Root")))
            val child = OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagUpdate("1", "Child", false)), tag)
            val old = OutboxRecoveryStore(db).preview(saved, root.actionId)
            val leaf = OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagUpdate("2", "Leaf", false)), tag)
            val independent = OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagCreate(id(), "Independent")))
            val before = db.outbox().commands(scope.key)
            try { OutboxRecoveryStore(db).dismiss(saved, old); fail("Stale preview accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(before, db.outbox().commands(scope.key))
            val plan = OutboxRecoveryStore(db).preview(saved, root.actionId)
            assertEquals(listOf(root, child, leaf), plan.commands)
            try { db.withTransaction { OutboxRecoveryStore(db).dismiss(saved, plan); throw IOException("Synthetic interruption") }; fail("Missing rollback") } catch (_: IOException) { }
            assertEquals(before, db.outbox().commands(scope.key))
            OutboxRecoveryStore(db).dismiss(saved, OutboxRecoveryStore(db).preview(saved, leaf.actionId))
            val parentPlan = OutboxRecoveryStore(db).preview(saved, root.actionId)
            assertEquals(listOf(root, child), parentPlan.commands)
            OutboxRecoveryStore(db).dismiss(saved, parentPlan); db.close(); db = open()
            assertEquals(before.map { if (it == independent) it else it.copy(state = "dismissed") }, db.outbox().commands(scope.key))
            val projection = LocalProjectionStore(db).read(scope)!!
            assertTrue(projection.blocked.isEmpty()); assertEquals(listOf(independent), projection.pending); assertEquals("Independent", projection.visible.tags.single().name)
            assertEquals(independent.copy(state = "sending"), OutboxLifecycle(db).claim(saved))
        } finally { db.close(); app.deleteDatabase(name) }
    }
    @Test fun unknownReceiptTransientAndSpecialConflictEvidenceCannotBeDismissed() = runBlocking<Unit> {
        val name = "recovery-unsafe.db"; app.deleteDatabase(name); val db = Room.databaseBuilder(app, CapitalDatabase::class.java, name).build()
        try {
            val saved = seed(db); val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
            val root = OfflineTagStore(db).enqueue(saved, ApiClient.json.encodeToString(TagCreate(id(), "Unknown")))
            val sending = OutboxLifecycle(db).claim(saved)!!; OutboxLifecycle(db).failed(sending, "uncertain")
            try { OutboxRecoveryStore(db).preview(saved, root.actionId); fail("Unknown accepted") } catch (_: IllegalArgumentException) { }
            db.outbox().transition(scope.key, root.actionId, "uncertain", "rejected")
            for ((status, code) in listOf(401 to "unauthenticated", 408 to "timeout", 429 to "rate_limited", 500 to "internal_error", 409 to "action_result_expired", 409 to "idempotency_conflict", 409 to "sync_generation_conflict")) {
                db.failures().save(OutboxFailure(root.position, status, code, null))
                try { OutboxRecoveryStore(db).preview(saved, root.actionId); fail("Unproven rejection accepted") } catch (_: IllegalArgumentException) { }
            }
            db.failures().save(OutboxFailure(root.position, 409, "version_conflict", null))
            assertEquals(1, OutboxRecoveryStore(db).preview(saved, root.actionId).commands.size)
            db.receipts().insert(OutboxReceipt(root.position, saved.generationId, "1", ""))
            try { OutboxRecoveryStore(db).preview(saved, root.actionId); fail("Applied evidence ignored") } catch (_: IllegalArgumentException) { }
            assertEquals(root.copy(state = "rejected"), db.outbox().commands(scope.key).single())
        } finally { db.close(); app.deleteDatabase(name) }
    }
}
