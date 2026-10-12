package com.capital.accounting

import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.auth.*
import com.capital.accounting.data.*
import com.capital.accounting.sync.*
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
class OutboxExpiryRuntimeTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val vault get() = CredentialVault(context, "outbox-expiry-test", "capital.test.outbox-expiry")
    private val proof get() = File(context.filesDir, "outbox-expiry.json")
    private fun open() = Room.databaseBuilder(context, CapitalDatabase::class.java, "outbox-expiry.db").build()
    private fun origin(): String {
        assumeTrue("Host expiry driver required", InstrumentationRegistry.getArguments().getString("outbox_expiry_host") == "true")
        return requireNotNull(InstrumentationRegistry.getArguments().getString("api_origin"))
    }
    @Test fun prepare() = runBlocking<Unit> {
        val origin = origin(); val api = ApiClient(origin)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("outbox-expiry-${UUID.randomUUID()}@example.test", "synthetic expiry password", "Receipt expiry", "bearer", "UTC"))))
        val saved = auth.credential(origin); vault.save(saved); context.deleteDatabase("outbox-expiry.db"); val db = open()
        try {
            SnapshotBootstrap(db).load(saved)
            val tagId = UUID.randomUUID().toString(); val date = "2026-10-12T12:00:00Z"
            val tag = Tag(tagId, saved.workspaceId, "1", date, date, null, "Expiry tag", null)
            OutboxStore(db).enqueue(saved, "tags", "POST", ApiClient.json.encodeToString(TagCreate(tagId, tag.name)), emptyList(), emptyList(), listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag))))
            val sending = OutboxLifecycle(db).claim(saved)!!
            val result = decodeResponse<MutationResult>(api.request(sending.path, sending.method, sending.body, saved, sending.actionId, sending.generation))
            assertEquals("1", result.sync_group_sequence); assertEquals("sending", db.outbox().commands(sending.scope).single().state)
            assertNull(db.receipts().get(sending.position))
            proof.writeText(JSONObject().put("stage", "prepared").put("pid", Process.myPid()).put("workspace", saved.workspaceId)
                .put("owner", saved.ownerId).put("action", sending.actionId).put("generation", saved.generationId).put("tag", tagId).toString())
        } finally { db.close() }
    }
    @Test fun verify() = runBlocking<Unit> {
        val origin = origin(); val before = JSONObject(proof.readText()); val saved = (vault.load(origin) as VaultRead.Available).session
        val db = open()
        try {
            assertNotEquals(before.getInt("pid"), Process.myPid())
            val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId); val original = db.outbox().commands(scope.key).single()
            assertEquals(before.getString("action"), original.actionId); assertEquals("sending", original.state)
            val reconcile = OutboxSender(db).sendNext(saved)!!
            assertEquals(original.copy(state = "reconcile"), reconcile); assertEquals("action_result_expired", db.failures().get(original.position)!!.code)
            assertNotNull(db.failures().get(original.position)!!.requestId)
            val api = ApiClient(origin); val compact = checkedActionOutcome(reconcile, api.request("/workspaces/${scope.workspace}/actions/${original.actionId}", session = saved))
            assertNull(compact.response_body); assertEquals("applied", compact.state); assertEquals("1", compact.group_sequence)
            assertEquals("applied", OutboxReconciler(db).inspect(saved, original.actionId)!!.state)
            assertEquals(original.copy(state = "applied"), db.outbox().commands(scope.key).single()); assertEquals("", db.receipts().get(original.position)!!.payload)
            assertNull(db.failures().get(original.position)); assertEquals("0", SnapshotStore(db).read(scope)!!.head.sequence)
            assertEquals(1, db.outbox().overlays(original.position).size)
            val mirror = MirrorSynchronizer(db).refresh(saved); assertEquals("1", mirror.head.sequence)
            assertEquals(before.getString("tag"), mirror.items.filterIsInstance<SnapshotItemTag>().single().data.id)
            assertTrue(db.outbox().overlays(original.position).isEmpty()); assertNull(OutboxSender(db).sendNext(saved))
            before.put("stage", "verified").put("pid", Process.myPid()).put("expired_reply", true).put("compact_applied", true)
                .put("cursor_preserved", true).put("command_preserved", true).put("pull_covered", true)
            proof.writeText(before.toString())
            api.request("/auth/logout", "POST", session = saved)
            vault.clear()
        } finally { db.close() }
    }
}
