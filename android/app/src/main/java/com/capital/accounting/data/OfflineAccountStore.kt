package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.finance.Money
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class OfflineAccountStore(private val db: CapitalDatabase) {
    suspend fun create(saved: StoredSession, body: String): OutboxCommand = db.withTransaction {
        require(IdentityStore(db).read(saved) != null)
        require(db.commands().get(saved.origin, saved.ownerId, saved.workspaceId) == null)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val projection = requireNotNull(LocalProjectionStore(db).read(scope))
        require(projection.confirmed.head.generation == saved.generationId && projection.blocked.isEmpty())
        val input = decodeResponse<AccountCreate>(body); require(input.name.isNotBlank() && input.name.length <= 100)
        Money.display(input.opening_balance_minor, input.currency)
        require(java.math.BigInteger(input.opening_balance_minor).toString() == input.opening_balance_minor)
        require(input.occurred_timezone in TimeZone.getAvailableIDs())
        require(projection.visible.accounts.none { it.id == input.id })
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val account = Account(input.id, saved.workspaceId, "1", now, now, null, input.name, input.type, input.currency, input.opened_at, null, "1", input.opening_balance_minor, "0", input.opening_balance_minor)
        val opening = Transaction(UUID.randomUUID().toString(), saved.workspaceId, "1", now, now, null, "opening", "posted", input.opened_at, input.occurred_timezone,
            "", "", null, null, null, null,
            if (input.opening_balance_minor == "0") emptyList() else listOf(Entry(UUID.randomUUID().toString(), input.id, input.currency, input.opening_balance_minor)), emptyList(), emptyList())
        val command = OutboxStore(db).enqueue(saved, "accounts", "POST", body, emptyList(), emptyList(), listOf(
            OverlayEffect.upsert(SnapshotItemAccount("account", account)),
            OverlayEffect("transaction", opening.id, "opening", ApiClient.json.encodeToString(SnapshotItemTransaction("transaction", opening)))))
        LocalProjectionStore(db).read(scope)
        command
    }
}
