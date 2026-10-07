package com.capital.accounting.api

import com.capital.accounting.auth.StoredSession
import java.io.IOException
import java.net.URLEncoder

suspend fun ApiClient.transactions(session: StoredSession, limit: Int = 100): List<Transaction> {
    require(limit in 1..100)
    val items = linkedMapOf<String, Transaction>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val page = decodeResponse<TransactionList>(request("/workspaces/${session.workspaceId}/transactions?limit=$limit" +
            (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: ""), session = session))
        page.items.forEach { require(it.workspace_id == session.workspaceId); items[it.id] = it }
        cursor = page.next_cursor
        if (cursor != null && !seen.add(cursor)) throw IOException("Repeated transaction cursor")
    } while (cursor != null)
    return items.values.toList()
}
