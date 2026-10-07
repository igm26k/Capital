package com.capital.accounting.api

import com.capital.accounting.auth.StoredSession
import java.io.IOException
import java.net.URLEncoder

private suspend fun <T> ApiClient.catalog(session: StoredSession, name: String, limit: Int,
    decode: (String) -> Pair<List<T>, String?>, scope: (T) -> Pair<String, String>): List<T> {
    require(limit in 1..100)
    val items = linkedMapOf<String, T>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val page = decode(request("/workspaces/${session.workspaceId}/$name?archived=include&limit=$limit" +
            (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: ""), session = session))
        page.first.forEach { val (id, workspace) = scope(it); require(workspace == session.workspaceId); items[id] = it }
        cursor = page.second
        if (cursor != null && !seen.add(cursor)) throw IOException("Repeated catalog cursor")
    } while (cursor != null)
    return items.values.toList()
}

suspend fun ApiClient.categories(session: StoredSession, limit: Int = 100): List<Category> =
    catalog(session, "categories", limit, { decodeResponse<CategoryList>(it).let { page -> page.items to page.next_cursor } }, { it.id to it.workspace_id })
suspend fun ApiClient.tags(session: StoredSession, limit: Int = 100): List<Tag> =
    catalog(session, "tags", limit, { decodeResponse<TagList>(it).let { page -> page.items to page.next_cursor } }, { it.id to it.workspace_id })
