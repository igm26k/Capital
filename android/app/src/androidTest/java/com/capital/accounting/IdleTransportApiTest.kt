package com.capital.accounting

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IdleTransportApiTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun readAndExplicitSameKeyWriteAfterServerIdleDeadlinePreserveExactLedger() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin"); assumeTrue(origin != null)
        val api = ApiClient(origin!!)
        val auth = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(
            Register("idle-transport-${id()}@example.test", "synthetic idle password", "Idle transport", "bearer", "UTC"))))
        val saved = auth.credential(origin)
        try {
            val account = decodeResponse<MutationResult>(api.request("/workspaces/${saved.workspaceId}/accounts", "POST",
                ApiClient.json.encodeToString(AccountCreate(id(), "Idle cash", "cash", "KWD", "2026-10-13T12:00:00Z", "UTC", "100000")), saved, id(), saved.generationId)).accounts.single()
            // Both configured API (60s) and nginx default (75s) idle deadlines have elapsed.
            delay(90_000)
            val live = decodeResponse<BearerAuth>(api.request("/auth/session", session = saved))
            assertEquals(saved.sessionId, live.session.id)
            assertEquals("100000", api.accounts(saved).single().posted_balance_minor)
            val action = id()
            val input = IncomeCreate(id(), "2026-10-13T12:01:00Z", "UTC", "After idle", "", emptyList(), "income", account.id, "1000", listOf(AllocationInput(id(), null, "1000")))
            val body = ApiClient.json.encodeToString(input)
            val path = "/workspaces/${saved.workspaceId}/transactions"
            val first = decodeResponse<MutationResult>(api.request(path, "POST", body, saved, action, saved.generationId))
            val replay = decodeResponse<MutationResult>(api.request(path, "POST", body, saved, action, saved.generationId))
            assertEquals(first, replay); assertEquals("2", first.sync_group_sequence)
            assertEquals("101000", api.accounts(saved).single().posted_balance_minor)
            assertEquals(1, api.transactions(saved).count { it.id == input.id })
        } finally { api.request("/auth/logout", "POST", session = saved) }
    }
}
