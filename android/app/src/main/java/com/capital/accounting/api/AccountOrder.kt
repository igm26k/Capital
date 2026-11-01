package com.capital.accounting.api

import kotlinx.serialization.Serializable

@Serializable
data class AccountOrder(val version: String = "0", val account_ids: List<String> = emptyList())

@Serializable
data class AccountOrderUpdate(val expected_version: String, val account_ids: List<String>)

fun orderAccounts(accounts: List<Account>, ids: List<String>): List<Account> {
    val positions = ids.withIndex().associate { it.value to it.index }
    return accounts.sortedBy { positions[it.id] ?: ids.size }
}
