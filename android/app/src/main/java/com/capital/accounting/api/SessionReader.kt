package com.capital.accounting.api

import com.capital.accounting.auth.StoredSession
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

suspend fun ApiClient.sessions(credential: StoredSession, limit: Int = 100): List<Session> {
    require(limit in 1..100)
    val result = linkedMapOf<String, Session>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val query = "/sessions?limit=$limit" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val page = decodeResponse<SessionList>(request(query, session = credential))
        page.items.forEach {
            require(UUID.fromString(it.id).toString().equals(it.id, ignoreCase = true))
            require(it.is_current == (it.id == credential.sessionId))
            result[it.id] = it
        }
        cursor = page.next_cursor
        if (cursor != null && !seen.add(cursor)) throw IOException("Repeated session cursor")
    } while (cursor != null)
    return result.values.toList()
}
