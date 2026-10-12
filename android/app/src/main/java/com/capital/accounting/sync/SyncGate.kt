package com.capital.accounting.sync

import com.capital.accounting.data.MirrorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object SyncGate {
    private val gates = mutableMapOf<String, Mutex>()
    suspend fun <T> serialized(scope: MirrorScope, work: suspend () -> T): T {
        val gate = synchronized(gates) { gates[scope.key] ?: Mutex().also { gates[scope.key] = it } }
        return gate.withLock { work() }
    }
}
