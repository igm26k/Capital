package com.capital.accounting.api

import com.capital.accounting.auth.StoredSession
import java.io.IOException

suspend fun ApiClient.transactionPage(session: StoredSession, filter: TransactionFilter = TransactionFilter(), limit: Int = 50, cursor: String? = null): TransactionList {
    val page = decodeResponse<TransactionList>(request("/workspaces/${session.workspaceId}/transactions?${filter.query(limit, cursor)}", session = session))
    require(page.items.all { it.workspace_id == session.workspaceId } && page.items.map { it.id }.distinct().size == page.items.size)
    if (cursor != null && page.next_cursor == cursor) throw IOException("Repeated transaction cursor")
    return page
}

suspend fun ApiClient.transactions(session: StoredSession, limit: Int = 100, filter: TransactionFilter = TransactionFilter()): List<Transaction> {
    require(limit in 1..100)
    val items = linkedMapOf<String, Transaction>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val page = transactionPage(session, filter, limit, cursor)
        page.items.forEach { items[it.id] = it }
        cursor = page.next_cursor
        if (cursor != null && !seen.add(cursor)) throw IOException("Repeated transaction cursor")
    } while (cursor != null)
    return items.values.toList()
}
