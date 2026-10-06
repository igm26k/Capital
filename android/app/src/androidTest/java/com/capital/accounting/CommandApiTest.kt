package com.capital.accounting

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.CapitalDatabase
import com.capital.accounting.finance.CommandRunner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CommandApiTest {
    @Test fun unrecordedConfirmationReplaysSameCommandAndFinalConflictDoesNotRetry() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin")
        assumeTrue("Real API required", origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("command-${UUID.randomUUID()}@example.test", "synthetic command password", "Durable commands", "bearer", "UTC"))))
        val credential = auth.credential(origin)
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>()
        val name = "command-api-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        var db = open()
        try {
            val body = ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), "Durable account", "bank", "EUR", "2026-10-06T08:00:00Z", "UTC", "12345"))
            val prepared = CommandRunner(db.commands()).prepare(credential, "accounts", "POST", body)
            // Server completed, but local confirmation was not recorded before process/database close.
            val first = decodeResponse<MutationResult>(api.request(prepared.path, prepared.method, prepared.body, credential, prepared.commandId, prepared.generationId))
            db.close(); db = open()
            val completed = CommandRunner(db.commands()).send(credential)
            assertEquals(prepared.commandId, completed.commandId)
            assertEquals("confirmed", completed.state)
            assertEquals(first, decodeResponse<MutationResult>(completed.result!!))
            val history = org.json.JSONObject(api.request("/workspaces/${credential.workspaceId}/transactions?limit=100", session = credential)).getJSONArray("items")
            assertEquals(1, history.length())
            assertEquals("12345", decodeResponse<AccountList>(api.request("/workspaces/${credential.workspaceId}/accounts?limit=100", session = credential)).items.single().posted_balance_minor)
            assertEquals(completed, CommandRunner(db.commands()).send(credential))
            db.commands().remove(completed.commandId, "confirmed")
            val account = first.accounts.single()
            val stale = ApiClient.json.encodeToString(AccountUpdate("999", "Rejected draft", "cash", true))
            val rejected = CommandRunner(db.commands()).prepare(credential, "accounts/${account.id}", "PUT", stale)
            val terminal = CommandRunner(db.commands()).send(credential)
            assertEquals("rejected", terminal.state)
            assertEquals("version_conflict", terminal.errorCode)
            assertEquals(stale, terminal.body)
            assertEquals(rejected.commandId, terminal.commandId)
            assertEquals(terminal, CommandRunner(db.commands()).send(credential))
            val wrong = StoredSession(origin, credential.token, credential.ownerId, credential.workspaceId, credential.generationId, UUID.randomUUID().toString())
            try { CommandRunner(db.commands()).send(wrong); fail("Different session must not send queued command") }
            catch (_: IllegalArgumentException) { }
            db.commands().remove(terminal.commandId, "rejected")
            val pending = CommandRunner(db.commands()).prepare(credential, "accounts/${account.id}", "PUT", ApiClient.json.encodeToString(AccountUpdate(account.version, account.name, account.type, false)))
            api.request("/auth/logout", "POST", session = credential)
            try { CommandRunner(db.commands()).send(credential); fail("Revoked bearer must fail") }
            catch (e: ApiFailure) { assertEquals(401, e.status) }
            assertEquals(pending, db.commands().get(origin, credential.ownerId, credential.workspaceId))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
