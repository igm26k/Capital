package com.capital.accounting.data

import android.content.Context
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import kotlinx.serialization.encodeToString

class AccountOrderStore(context: Context) {
    private val preferences = context.getSharedPreferences("account_display_order", Context.MODE_PRIVATE)
    private fun key(saved: StoredSession) = "${saved.origin}:${saved.ownerId}:${saved.workspaceId}:${saved.generationId}"
    fun read(saved: StoredSession): AccountOrder = runCatching {
        preferences.getString(key(saved), null)?.let { ApiClient.json.decodeFromString<AccountOrder>(it) }
    }.getOrNull() ?: AccountOrder()
    fun erase(saved: StoredSession) {
        val prefix = "${saved.origin}:${saved.ownerId}:"
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        check(editor.commit())
    }
    fun save(saved: StoredSession, order: AccountOrder) {
        check(preferences.edit().putString(key(saved), ApiClient.json.encodeToString(order)).commit())
    }
}
