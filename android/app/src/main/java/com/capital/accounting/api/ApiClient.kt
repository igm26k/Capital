package com.capital.accounting.api

import com.capital.accounting.auth.StoredSession
import com.capital.accounting.normalizedOrigin
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(val status: Int, val code: String) : IOException("HTTP $status")

class ApiClient(origin: String) {
    val origin = normalizedOrigin(origin)
    suspend fun request(path: String, method: String = "GET", body: String? = null, session: StoredSession? = null): String {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\'))
        require(session == null || session.origin == origin)
        val request = Request.Builder().url("$origin/api/v1$path")
            .header("Accept", "application/json").header("Cache-Control", "no-store")
        if (session != null) request.header("Authorization", "Bearer ${session.token}")
        request.method(method, if (method == "GET" || method == "DELETE") null else (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = client.newCall(request.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val content = response.body?.byteStream()?.use { input ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val size = input.read(buffer)
                                    if (size == -1) break
                                    if (out.size() + size > 2 * 1024 * 1024) throw IOException("Response too large")
                                    out.write(buffer, 0, size)
                                }
                                out.toString("UTF-8")
                            } ?: throw IOException("Empty response")
                            if (!response.isSuccessful) {
                                val code = try { JSONObject(content).optString("code", "http_error") } catch (_: Exception) { "http_error" }
                                throw ApiFailure(response.code, code)
                            }
                            if (continuation.isActive) continuation.resume(content)
                        }
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
    companion object {
        val json = Json { ignoreUnknownKeys = false }
        private val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    }
}

fun BearerAuth.credential(origin: String, logoutPending: Boolean = false): StoredSession {
    require(transport == "bearer" && token_type == "Bearer" && session.is_current)
    return StoredSession(origin, access_token, profile.id, workspace.id, workspace.sync_generation_id, session.id, logoutPending)
}

inline fun <reified T> decodeResponse(value: String): T = try {
    ApiClient.json.decodeFromString<T>(value)
} catch (_: Exception) {
    // JSON decoder diagnostics can contain the input, including bearer tokens.
    throw IOException("Invalid API response")
}
