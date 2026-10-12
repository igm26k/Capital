package com.capital.accounting

import androidx.room.Room
import androidx.room.withTransaction
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
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutboxFailureTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private fun id() = UUID.randomUUID().toString()
    private fun open(name: String) = Room.databaseBuilder(context, CapitalDatabase::class.java, name).addMigrations(CapitalDatabase.MIGRATION_9_10, CapitalDatabase.MIGRATION_10_11).build()
    private fun saved() = StoredSession("https://accounting.example", "x".repeat(64), id(), id(), id(), id())
    private suspend fun prepare(db: CapitalDatabase, s: StoredSession): Pair<OutboxCommand, Tag> {
        val scope = MirrorScope(s.origin, s.ownerId, s.workspaceId)
        db.snapshots().head(MirrorHead(scope.key, s.generationId, "0", "cursor"))
        val tag = Tag(id(), s.workspaceId, "1", "2026-10-12T12:00:00Z", "2026-10-12T12:00:00Z", null, "Local", null)
        OutboxStore(db).enqueue(s, "tags", "POST", "{}", emptyList(), emptyList(), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag))))
        return OutboxLifecycle(db).claim(s)!! to tag
    }
    @Test fun failureAndStateRollbackTogetherReopenAndSuccessClearsError() = runBlocking<Unit> {
        val name = "outbox-failure-atomic.db"; context.deleteDatabase(name); var db = open(name); val s = saved()
        try {
            val (command, tag) = prepare(db, s); val requestId = id(); val error = ApiFailure(503, "temporarily_unavailable", requestId)
            try { db.withTransaction { OutboxLifecycle(db).failed(command, "uncertain", error); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertEquals(command, db.outbox().commands(command.scope).single()); assertNull(db.failures().get(command.position))
            OutboxLifecycle(db).failed(command, "uncertain", error); db.close(); db = open(name)
            assertEquals(OutboxFailure(command.position, 503, "temporarily_unavailable", requestId), db.failures().get(command.position))
            val retry = OutboxLifecycle(db).claim(s)!!; assertEquals(command, retry)
            val response = ApiClient.json.encodeToString(MutationResult(command.actionId, emptyList(), emptyList(), emptyList(), listOf(tag), emptyList(), "1", command.generation))
            try { db.withTransaction { OutboxReceiptStore(db).applied(retry, response); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertNotNull(db.failures().get(command.position)); assertNull(db.receipts().get(command.position))
            OutboxReceiptStore(db).applied(retry, response); assertNull(db.failures().get(command.position)); assertEquals("applied", db.outbox().commands(command.scope).single().state)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun migrationPreservesSendingAndUntrustedDetailsAreDiscarded() = runBlocking<Unit> {
        val name = "outbox-failure-migration.db"; context.deleteDatabase(name); var db = open(name); val s = saved()
        try {
            val (command, _) = prepare(db, s); val effects = db.outbox().overlays(command.position); db.close()
            context.openOrCreateDatabase(name, 0, null).use { old -> old.execSQL("DROP TABLE outbox_outcomes"); old.execSQL("DROP TABLE outbox_failures"); old.execSQL("DROP TABLE room_master_table"); old.execSQL("PRAGMA user_version=9") }
            db = open(name); assertEquals(command, db.outbox().commands(command.scope).single()); assertEquals(effects, db.outbox().overlays(command.position))
            OutboxLifecycle(db).failed(command, "rejected", ApiFailure(422, "unsafe\nresponse-canary", "untrusted request id"))
            val failure = db.failures().get(command.position)!!; assertEquals(422, failure.status); assertNull(failure.code); assertNull(failure.requestId)
            assertEquals("OutboxFailure([redacted])", failure.toString()); assertEquals(effects, db.outbox().overlays(command.position))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
