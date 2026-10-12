package com.capital.accounting

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OutboxLifecycleTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private fun id() = UUID.randomUUID().toString()
    private fun open(name: String) = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
    private fun session() = StoredSession("https://accounting.example", "x".repeat(64), id(), id(), id(), id())
    private fun scope(saved: StoredSession) = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
    private suspend fun command(db: CapitalDatabase, saved: StoredSession): OutboxCommand {
        db.snapshots().head(MirrorHead(scope(saved).key, saved.generationId, "0", "cursor"))
        return OutboxStore(db).enqueue(saved, "tags/${id()}", "DELETE", "{}", emptyList(), emptyList(), listOf(OverlayEffect.delete("tag", id())))
    }
    @Test fun interruptedSendReopensAsUncertainAndRetriesOriginalIdentity() = runBlocking<Unit> {
        val name = "outbox-lifecycle-restart.db"; context.deleteDatabase(name); var db = open(name); val saved = session()
        try {
            val first = command(db, saved); val second = command(db, saved)
            val sending = OutboxLifecycle(db).claim(saved)!!; assertEquals(first.copy(state = "sending"), sending)
            assertNull(OutboxLifecycle(db).claim(saved)); db.close(); db = open(name)
            assertEquals(1, OutboxLifecycle(db).recoverInterrupted(scope(saved)))
            val retry = OutboxLifecycle(db).claim(saved)!!; assertEquals(sending, retry)
            assertEquals(second, db.outbox().commands(scope(saved).key)[1])
            val effects = db.outbox().overlays(first.position)
            OutboxLifecycle(db).failed(retry, "uncertain"); assertEquals(effects, db.outbox().overlays(first.position))
            assertEquals(retry, OutboxLifecycle(db).claim(saved))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun terminalHeadBlocksFollowingCommandsAndCannotBeCancelled() = runBlocking<Unit> {
        val name = "outbox-lifecycle-block.db"; context.deleteDatabase(name); val db = open(name); val saved = session()
        try {
            val first = command(db, saved); command(db, saved)
            val sending = OutboxLifecycle(db).claim(saved)!!; OutboxLifecycle(db).failed(sending, "reconcile")
            assertNull(OutboxLifecycle(db).claim(saved))
            try { OutboxStore(db).cancelQueued(scope(saved), first.actionId); fail("Uncertain action cancelled") } catch (_: IllegalArgumentException) { }
            try { OutboxLifecycle(db).failed(sending, "rejected"); fail("Stale transition accepted") } catch (_: IllegalArgumentException) { }
            assertEquals("reconcile", db.outbox().commands(scope(saved).key)[0].state)
            assertEquals(1, db.outbox().overlays(first.position).size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun claimRollbackAndForeignSessionOrGenerationNeverSend() = runBlocking<Unit> {
        val name = "outbox-lifecycle-scope.db"; context.deleteDatabase(name); val db = open(name); val saved = session()
        try {
            val original = command(db, saved)
            try { db.withTransaction { OutboxLifecycle(db).claim(saved); throw IOException("Synthetic interruption") }; fail("Missing fault") } catch (_: IOException) { }
            assertEquals(original, db.outbox().commands(scope(saved).key).single())
            for (other in listOf(
                StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, saved.generationId, id()),
                StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, id(), saved.sessionId),
                StoredSession(saved.origin, saved.token, saved.ownerId, saved.workspaceId, saved.generationId, saved.sessionId, logoutPending = true))) {
                try { OutboxLifecycle(db).claim(other); fail("Foreign authority accepted") } catch (_: IllegalArgumentException) { }
            }
            assertEquals(original, db.outbox().commands(scope(saved).key).single())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
