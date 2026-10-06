package com.capital.accounting

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ApiAuthTest {
    @Test fun realTlsBearerAuthRenewScopeAndLogout() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("api_origin")
        assumeTrue("Real API origin required; no mock acceptance", origin != null)
        val api = ApiClient(origin!!)
        val email = "android-api-${UUID.randomUUID()}@example.test"
        val password = "  exact synthetic 🥨 password  "
        val body = ApiClient.json.encodeToString(Register(email, password, "Android API", "bearer", "UTC"))
        val registered = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", body))
        val credential = registered.credential(origin)
        assertEquals(email, registered.profile.email)
        assertFalse(registered.toString().contains(registered.access_token))
        val restored = decodeResponse<BearerAuth>(api.request("/auth/session", session = credential))
        assertTrue("Restored credential changed", credential.token == restored.access_token)
        assertEquals(credential.sessionId, restored.session.id)
        val renewed = decodeResponse<BearerAuth>(api.request("/auth/renew", "POST", session = credential))
        assertEquals(credential.sessionId, renewed.session.id)
        assertTrue("Renew rotated credential", credential.token == renewed.access_token)
        assertEquals(registered.session.absolute_expires_at, renewed.session.absolute_expires_at)
        try {
            api.request("/auth/login", "POST", ApiClient.json.encodeToString(Login(email, password.trim(), "Wrong password", "bearer")))
            fail("Password whitespace must be preserved")
        } catch (e: ApiFailure) { assertEquals(401, e.status) }
        val login = decodeResponse<BearerAuth>(api.request("/auth/login", "POST", ApiClient.json.encodeToString(Login(email, password, "Second Android", "bearer"))))
        assertNotEquals(credential.sessionId, login.session.id)
        val foreign = decodeResponse<BearerAuth>(api.request("/auth/register", "POST", ApiClient.json.encodeToString(Register("foreign-${UUID.randomUUID()}@example.test", password, "Other", "bearer", "UTC"))))
        try {
            api.request("/workspaces/${credential.workspaceId}/accounts", session = foreign.credential(origin))
            fail("Foreign workspace must be denied")
        } catch (e: ApiFailure) { assertEquals(404, e.status) }
        val otherOrigin = StoredSession("https://other.example", credential.token, credential.ownerId, credential.workspaceId, credential.generationId, credential.sessionId)
        try { api.request("/auth/session", session = otherOrigin); fail("Wrong origin credential") }
        catch (_: IllegalArgumentException) { }
        val sessions = JSONObject(api.request("/sessions?limit=100", session = credential)).getJSONArray("items")
        assertEquals(2, sessions.length())
        val ack = decodeResponse<Acknowledgement>(api.request("/auth/logout", "POST", session = credential))
        assertTrue(ack.ok)
        try { api.request("/auth/session", session = credential); fail("Revoked credential") }
        catch (e: ApiFailure) { assertEquals(401, e.status) }
        api.request("/auth/logout", "POST", session = login.credential(origin))
        api.request("/auth/logout", "POST", session = foreign.credential(origin))
    }
}
