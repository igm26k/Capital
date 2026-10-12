package com.capital.accounting

import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class CachedIdentityTest {
    private fun id() = UUID.randomUUID().toString()
    private val saved = StoredSession("https://accounting.example", "x".repeat(64), id(), id(), id(), id())
    private val identity = PublicIdentity(Profile(saved.ownerId, "identity@example.test", "UTC"), Workspace(saved.workspaceId, "Private", "1", "owner", saved.generationId),
        Session(saved.sessionId, "Device", true, "2026-10-12T11:00:00Z", "2026-10-12T12:00:00Z", "2026-10-12T13:00:00.000001Z", "2026-10-13T12:00:00Z"))
    private val now = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli()
    private fun cache(value: PublicIdentity = identity) = CachedIdentity(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId).key, ApiClient.json.encodeToString(value), now)
    @Test fun publicPayloadExcludesCredentialAndRespectsExpiryAndClockRollback() {
        val cached = cache()
        assertFalse(cached.payload.contains(saved.token)); assertFalse(cached.payload.contains("access_token"))
        assertEquals(identity, usableIdentity(cached, saved, now))
        assertNull(usableIdentity(cached, saved, now - 1))
        assertNull(usableIdentity(cached, saved, Instant.parse("2026-10-12T13:00:00Z").toEpochMilli()))
        assertNull(usableIdentity(cache(identity.copy(session = identity.session.copy(absolute_expires_at = "2026-10-12T12:00:00Z"))), saved, now))
    }
    @Test fun scopeSessionGenerationAndPendingIntentsNeverReuseAnotherIdentity() {
        fun session(origin: String = saved.origin, owner: String = saved.ownerId, workspace: String = saved.workspaceId, generation: String = saved.generationId, sid: String = saved.sessionId, logout: Boolean = false, revoke: String? = null) = StoredSession(origin, saved.token, owner, workspace, generation, sid, logout, revoke)
        for (other in listOf(session(origin = "https://other.example"), session(owner = id()), session(workspace = id()), session(generation = id()), session(sid = id()), session(logout = true), session(revoke = id()))) assertNull(usableIdentity(cache(), other, now))
    }
    @Test fun corruptMetadataNonCurrentSessionAndInvalidUtcDatesFailClosed() {
        assertNull(usableIdentity(cache().copy(payload = "invalid"), saved, now))
        assertNull(usableIdentity(cache(identity.copy(session = identity.session.copy(is_current = false))), saved, now))
        assertNull(usableIdentity(cache(identity.copy(session = identity.session.copy(expires_at = "2026-02-30T12:00:00Z"))), saved, now))
        assertFalse(identity.toString().contains("identity@example.test"))
    }
}
