package com.capital.accounting

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.finance.Money
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountApiTest {
    @Test fun exactOpeningIdempotencyVersionsArchiveAndGeneration() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin")
        assumeTrue("Real API required", origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("account-${UUID.randomUUID()}@example.test", "synthetic account password", "Android accounts", "bearer", "UTC"))))
        val credential = auth.credential(origin)
        val base = "/workspaces/${credential.workspaceId}/accounts"
        try {
            val create = AccountCreate(UUID.randomUUID().toString(), "Android KWD", "bank", "KWD", "2026-10-06T08:00:00Z", "UTC", Money.minor("123.456", "KWD"))
            val command = UUID.randomUUID().toString()
            val body = ApiClient.json.encodeToString(create)
            val raw = api.request(base, "POST", body, credential, command, credential.generationId)
            val created = decodeResponse<MutationResult>(raw)
            assertEquals(command, created.action_id)
            assertEquals(credential.generationId, created.generation_id)
            assertEquals(1, created.transactions.size)
            assertEquals("opening", created.transactions.single().kind)
            assertEquals("123456", created.transactions.single().entries.single().amount_minor)
            val account = created.accounts.single()
            assertEquals("123456", account.posted_balance_minor)
            assertEquals("123,456 KWD", Money.display(account.posted_balance_minor, account.currency))
            assertEquals(created, decodeResponse<MutationResult>(api.request(base, "POST", body, credential, command, credential.generationId)))
            val accounts = decodeResponse<AccountList>(api.request("$base?limit=100", session = credential))
            assertEquals(1, accounts.items.size)
            assertEquals("123456", accounts.items.single().posted_balance_minor)
            val history = org.json.JSONObject(api.request("/workspaces/${credential.workspaceId}/transactions?limit=100", session = credential)).getJSONArray("items")
            assertEquals(1, history.length())
            assertEquals("opening", history.getJSONObject(0).getString("kind"))
            val archive = ApiClient.json.encodeToString(AccountUpdate(account.version, "Renamed account", "cash", true))
            val updated = decodeResponse<MutationResult>(api.request("$base/${account.id}", "PUT", archive, credential, UUID.randomUUID().toString(), credential.generationId)).accounts.single()
            assertNotNull(updated.archived_at)
            assertEquals("Renamed account", updated.name)
            assertEquals(account.posted_balance_minor, updated.posted_balance_minor)
            assertEquals(account.balance_version, updated.balance_version)
            assertNotEquals(account.version, updated.version)
            try {
                api.request("$base/${account.id}", "PUT", archive, credential, UUID.randomUUID().toString(), credential.generationId)
                fail("Stale account version must conflict")
            } catch (e: ApiFailure) { assertEquals(409, e.status) }
            val restore = ApiClient.json.encodeToString(AccountUpdate(updated.version, updated.name, updated.type, false))
            try {
                api.request("$base/${account.id}", "PUT", restore, credential, UUID.randomUUID().toString(), UUID.randomUUID().toString())
                fail("Wrong generation must conflict")
            } catch (e: ApiFailure) { assertEquals(409, e.status); assertEquals("sync_generation_conflict", e.code) }
            val restored = decodeResponse<MutationResult>(api.request("$base/${account.id}", "PUT", restore, credential, UUID.randomUUID().toString(), credential.generationId)).accounts.single()
            assertNull(restored.archived_at)
            assertEquals("123456", restored.posted_balance_minor)
            assertEquals(account.balance_version, restored.balance_version)
        } finally { api.request("/auth/logout", "POST", session = credential) }
    }
}
