package com.capital.accounting

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.auth.VaultRead
import com.capital.accounting.data.*
import com.capital.accounting.sync.SnapshotBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URLEncoder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SnapshotBootstrapRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val origin get() = InstrumentationRegistry.getArguments().getString("api_origin")!!
    private val file get() = File(app.filesDir, "snapshot-bootstrap-runtime.json")
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("snapshot_host") == "true")
    private suspend fun session(): StoredSession = (app.credentialVault.load(origin) as VaultRead.Available).session
    private fun scope(saved: StoredSession) = MirrorScope(origin, saved.ownerId, saved.workspaceId)
    private fun write(proof: JSONObject, stage: String) { proof.put("stage", stage).put("pid", android.os.Process.myPid()); file.writeText(proof.toString()) }
    private suspend fun stage(api: ApiClient, saved: StoredSession): Snapshot {
        val id = UUID.randomUUID().toString(); val scoped = scope(saved); val root = "/workspaces/${saved.workspaceId}/sync/snapshots"
        app.database.snapshots().start(SnapshotAttempt(scoped.key, id))
        val meta = decodeResponse<Snapshot>(api.request(root, "POST", ApiClient.json.encodeToString(SnapshotCreate(id)), saved, id, saved.generationId))
        SnapshotStore(app.database).begin(scoped, meta)
        val page = decodeResponse<SnapshotPage>(api.request("$root/$id/pages?page_token=${URLEncoder.encode(meta.first_page_token, "UTF-8")}", session = saved))
        assertEquals(100, page.items.size); assertNotNull(page.next_page_token)
        SnapshotStore(app.database).append(scoped, meta.first_page_token, page)
        return meta
    }
    @Test fun prepare() = runBlocking<Unit> {
        enabled(); file.delete(); val api = ApiClient(origin)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("bootstrap-runtime-${UUID.randomUUID()}@example.test", "Synthetic bootstrap runtime password", "Bootstrap runtime", "bearer", "UTC"))))
        val saved = auth.credential(origin); app.credentialVault.save(saved); val scoped = scope(saved); val root = "/workspaces/${saved.workspaceId}"
        suspend fun command(suffix: String, body: String): MutationResult = decodeResponse(api.request("$root/$suffix", "POST", body, saved, UUID.randomUUID().toString(), saved.generationId))
        val account = command("accounts", ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Runtime snapshot cash", "cash", "KWD", "2026-10-01T12:00:00Z", "UTC", "100000"))).accounts.single()
        SnapshotBootstrap(app.database).load(saved)
        repeat(101) { command("tags", ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), "Bootstrap tag $it"))) }
        val staged = stage(api, saved)
        command("transactions", ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), "2026-10-06T12:00:00Z", "UTC", "Bootstrap runtime expense", "", emptyList(), "expense", account.id, "1000", listOf(AllocationInput(UUID.randomUUID().toString(), null, "1000")))))
        val pending = FinancialCommand(UUID.randomUUID().toString(), origin, saved.ownerId, saved.workspaceId, saved.sessionId, saved.generationId, "$root/accounts", "POST", "{}")
        app.database.commands().insert(pending)
        assertEquals("1", SnapshotStore(app.database).read(scoped)?.head?.sequence)
        write(JSONObject().put("workspace", saved.workspaceId).put("owner", saved.ownerId).put("snapshot", staged.id).put("command", pending.commandId), "prepared")
    }
    @Test fun resume() = runBlocking<Unit> {
        enabled(); val proof = JSONObject(file.readText()); assertEquals("prepared", proof.getString("stage"))
        assertNotEquals(proof.getInt("pid"), android.os.Process.myPid())
        val saved = session(); val scoped = scope(saved); val db = app.database
        assertEquals(100, db.snapshots().stage(scoped.key)?.received)
        val mirror = SnapshotBootstrap(db).load(saved)
        assertEquals("102", mirror.head.sequence); assertEquals(103, mirror.items.size)
        assertEquals("100000", mirror.items.filterIsInstance<SnapshotItemAccount>().single().data.posted_balance_minor)
        assertNull(db.snapshots().attempt(scoped.key)); assertNull(db.snapshots().stage(scoped.key))
        val api = ApiClient(origin)
        // Keep at most two live snapshots before preparing the next interrupted bootstrap.
        // The host expires the first (base1) snapshot before this phase.
        val next = stage(api, saved)
        proof.put("snapshot", next.id).put("cold_resume", true)
        write(proof, "resumed")
    }
    @Test fun expired() = runBlocking<Unit> {
        enabled(); val proof = JSONObject(file.readText()); assertEquals("resumed", proof.getString("stage"))
        assertNotEquals(proof.getInt("pid"), android.os.Process.myPid())
        val saved = session(); val scoped = scope(saved); val db = app.database; val api = ApiClient(origin)
        try {
            assertEquals(100, db.snapshots().stage(scoped.key)?.received)
            val pending = db.commands().get(origin, saved.ownerId, saved.workspaceId)!!
            assertEquals(proof.getString("command"), pending.commandId); assertEquals("{}", pending.body)
            try { api.request("/workspaces/${saved.workspaceId}/sync/snapshots/${proof.getString("snapshot")}", session = saved); fail("Snapshot should have expired") }
            catch (e: ApiFailure) { assertEquals(410, e.status); assertEquals("snapshot_expired", e.code) }
            val mirror = SnapshotBootstrap(db).load(saved)
            assertEquals("103", mirror.head.sequence); assertEquals(104, mirror.items.size)
            assertEquals("99000", mirror.items.filterIsInstance<SnapshotItemAccount>().single().data.posted_balance_minor)
            assertEquals(pending, db.commands().get(origin, saved.ownerId, saved.workspaceId))
            assertNull(db.snapshots().stage(scoped.key)); assertNull(db.snapshots().attempt(scoped.key))
            proof.put("expiry_rebuilt", true).put("command_preserved", true).put("exact_minor", true)
            write(proof, "verified")
        } finally { api.request("/auth/logout", "POST", session = saved); app.credentialVault.clear() }
    }
}
