package com.capital.accounting

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.data.*
import com.capital.accounting.sync.MirrorSynchronizer
import com.capital.accounting.sync.OutboxReconciler
import com.capital.accounting.sync.OutboxSender
import com.capital.accounting.sync.QueueCoordinator
import com.capital.accounting.sync.QueueDrainResult
import com.capital.accounting.sync.QueueDrainStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MirrorCursorRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    private fun file(mode: String) = File(app.filesDir, "mirror-cursor-$mode.json")
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("cursor_host") == "true")
    private suspend fun prepare(mode: String) {
        enabled(); val api = ApiClient(origin)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("cursor-$mode-${UUID.randomUUID()}@example.test", "Synthetic cursor runtime password", "Cursor runtime", "bearer", "UTC"))))
        val saved = auth.credential(origin); app.credentialVault.save(saved)
        val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId); val root = "/workspaces/${scope.workspace}"
        suspend fun command(path: String, body: String): MutationResult = decodeResponse(api.request("$root/$path", "POST", body, saved, UUID.randomUUID().toString(), saved.generationId))
        val account = command("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Cursor cash", "cash", "KWD", "2026-10-01T12:00:00Z", "UTC", "100000"))).accounts.single()
        val mirror = MirrorSynchronizer(app.database).refresh(saved, 1); assertEquals("1", mirror.head.sequence)
        if (mode == "rotation") {
            IdentityStore(app.database).save(auth, origin)
            val tags = OfflineTagStore(app.database)
            val unknown = tags.enqueue(saved, ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "Unknown old generation")))
            assertEquals(1, app.database.outbox().transition(scope.key, unknown.actionId, "queued", "sending"))
            assertEquals(1, app.database.outbox().transition(scope.key, unknown.actionId, "sending", "uncertain"))
            tags.enqueue(saved, ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "Queued old generation")))
            val snapshotId = UUID.randomUUID().toString()
            app.database.snapshots().start(SnapshotAttempt(scope.key, snapshotId))
            val interrupted = decodeResponse<Snapshot>(api.request("$root/sync/snapshots", "POST",
                ApiClient.json.encodeToString(SnapshotCreate(snapshotId)), saved, snapshotId, saved.generationId))
            SnapshotStore(app.database).begin(scope, interrupted)
        }
        command("transactions", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T12:00:00Z", "UTC", "Cursor runtime expense", "", emptyList(), "expense", account.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000")))))
        val pending = FinancialCommand(UUID.randomUUID().toString(), origin, saved.ownerId, saved.workspaceId, saved.sessionId, saved.generationId, "$root/accounts", "POST", "{}")
        app.database.commands().insert(pending)
        file(mode).writeText(JSONObject().put("mode", mode).put("stage", "prepared").put("workspace", saved.workspaceId).put("owner", saved.ownerId).put("command", pending.commandId).put("generation", saved.generationId).put("pid", android.os.Process.myPid()).toString())
    }
    private suspend fun verify(mode: String) {
        enabled(); val proof = JSONObject(file(mode).readText()); assertEquals("prepared", proof.getString("stage"))
        assertNotEquals(proof.getInt("pid"), android.os.Process.myPid())
        val saved = (app.credentialVault.load(origin) as VaultRead.Available).session
        val scope = MirrorScope(origin, saved.ownerId, saved.workspaceId); val db = app.database; val api = ApiClient(origin)
        try {
            val original = SnapshotStore(db).read(scope)!!; assertEquals("1", original.head.sequence)
            val command = db.commands().get(origin, saved.ownerId, saved.workspaceId)!!
            val outbox = db.outbox().commands(scope.key)
            val overlays = outbox.associate { it.position to db.outbox().overlays(it.position) }
            if (mode == "rotation") {
                val interrupted = decodeResponse<Snapshot>(db.snapshots().stage(scope.key)!!.descriptor)
                assertEquals(saved.generationId, interrupted.generation_id)
                assertEquals(interrupted.id, db.snapshots().attempt(scope.key)!!.snapshot)
            }
            assertEquals(proof.getString("command"), command.commandId); assertEquals(proof.getString("generation"), command.generationId)
            if (mode == "expiry") {
                try { api.request("/workspaces/${scope.workspace}/sync/changes?cursor=${java.net.URLEncoder.encode(original.head.cursor, "UTF-8")}", session = saved); fail("Expired cursor accepted") }
                catch (e: ApiFailure) { assertEquals(410, e.status); assertEquals("cursor_expired", e.code) }
            }
            val updated = MirrorSynchronizer(db).refresh(saved, 1)
            assertEquals(if (mode == "expiry") "2" else "0", updated.head.sequence)
            if (mode == "rotation") assertNotEquals(original.head.generation, updated.head.generation)
            else assertEquals(original.head.generation, updated.head.generation)
            assertEquals("99000", updated.items.filterIsInstance<SnapshotItemAccount>().single().data.posted_balance_minor)
            assertEquals(2, updated.items.filterIsInstance<SnapshotItemTransaction>().size)
            assertEquals(command, db.commands().get(origin, saved.ownerId, saved.workspaceId))
            if (mode == "rotation") {
                assertEquals(listOf("uncertain", "queued"), outbox.map { it.state })
                assertEquals(outbox, db.outbox().commands(scope.key))
                val projection = LocalProjectionStore(db).read(scope)!!
                assertTrue(projection.pending.isEmpty()); assertEquals(outbox, projection.blocked)
                assertTrue(projection.visible.tags.isEmpty())
                val auth = decodeResponse<BearerAuth>(api.request("/auth/session", session = saved))
                val current = auth.credential(origin)
                IdentityStore(db).save(auth, origin)
                // Old uncertain actions are inspected with GET only; missing evidence is retained.
                assertNull(OutboxReconciler(db).inspect(current, outbox.first().actionId))
                // Remove only the separately asserted legacy fixture, so it cannot mask the outbox guard.
                assertEquals(1, db.commands().remove(command.commandId, command.state))
                assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(current))
                try {
                    OutboxSender(db).sendNext(current)
                    fail("Old-generation command was claimable")
                } catch (_: IllegalArgumentException) { }
                try {
                    OutboxRecoveryStore(db).preview(current, outbox.first().actionId)
                    fail("Unknown old-generation command was dismissible")
                } catch (_: IllegalArgumentException) { }
                assertEquals(outbox, db.outbox().commands(scope.key))
                for ((position, effects) in overlays) assertEquals(effects, db.outbox().overlays(position))
                val plan = OutboxRecoveryStore(db).preview(current, outbox.last().actionId)
                assertEquals(listOf(outbox.last()), plan.commands)
                OutboxRecoveryStore(db).dismiss(current, plan)
                assertEquals(listOf(outbox.first(), outbox.last().copy(state = "dismissed")), db.outbox().commands(scope.key))
                assertEquals(QueueDrainResult(0, QueueDrainStatus.BLOCKED), QueueCoordinator(db).drain(current))
                assertTrue(api.tags(current).isEmpty())
                db.commands().insert(command)
                assertEquals(command, db.commands().get(origin, saved.ownerId, saved.workspaceId))
                proof.put("outbox_preserved", true).put("old_generation_blocked", true).put("unknown_retained", true)
            }
            assertNull(db.snapshots().stage(scope.key)); assertNull(db.snapshots().attempt(scope.key))
            proof.put("stage", "verified").put("pid", android.os.Process.myPid()).put("recovered", true).put("command_preserved", true).put("exact_minor", true)
            file(mode).writeText(proof.toString())
        } finally { api.request("/auth/logout", "POST", session = saved); app.credentialVault.clear() }
    }
    @Test fun prepareExpiry() = runBlocking<Unit> { prepare("expiry") }
    @Test fun verifyExpiry() = runBlocking<Unit> { verify("expiry") }
    @Test fun prepareRotation() = runBlocking<Unit> { prepare("rotation") }
    @Test fun verifyRotation() = runBlocking<Unit> { verify("rotation") }
}
