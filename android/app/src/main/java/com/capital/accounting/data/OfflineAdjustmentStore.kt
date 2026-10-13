package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.finance.Money
import java.math.BigInteger
import java.util.TimeZone
import java.util.UUID

class OfflineAdjustmentStore(private val db: CapitalDatabase) {
    private suspend fun projection(saved: StoredSession): LocalProjection {
        require(IdentityStore(db).read(saved) != null && db.commands().get(saved.origin, saved.ownerId, saved.workspaceId) == null)
        return requireNotNull(LocalProjectionStore(db).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))).also {
            require(it.confirmed.head.generation == saved.generationId && it.blocked.isEmpty())
        }
    }
    private suspend fun dependencies(projection: LocalProjection, refs: List<Pair<String, String>>): List<String> {
        val result = mutableSetOf<String>()
        for (ref in refs.distinct()) for (parent in projection.pending.asReversed()) {
            if (db.outbox().overlays(parent.position).any { (it.entityType to it.entityId) == ref }) { result.add(parent.actionId); break }
        }
        return result.toList()
    }
    suspend fun create(saved: StoredSession, body: String, accountId: String): OutboxCommand = db.withTransaction {
        val projection = projection(saved); val input = decodeResponse<AdjustmentCreate>(body)
        val account = projection.visible.accounts.single { it.id == accountId }
        require(account.archived_at == null && input.expected_balance_version == account.balance_version)
        require(input.reason.isNotEmpty() && input.reason.length <= 500 && input.note.length <= 2000 && input.occurred_timezone in TimeZone.getAvailableIDs())
        require(BigInteger(input.target_balance_minor).toString() == input.target_balance_minor); Money.display(input.target_balance_minor, account.currency)
        require(projection.visible.transactions.none { it.id == input.id })
        val now = ledgerNow(); require(ledgerInstant(now) >= ledgerInstant(account.opened_at))
        val delta = BigInteger(input.target_balance_minor) - BigInteger(account.posted_balance_minor)
        require(delta != BigInteger.ZERO); Money.display(delta.toString(), account.currency)
        val changed = ledgerAccountDelta(account, delta, now)
        val transaction = Transaction(input.id, saved.workspaceId, "1", now, now, null, "adjustment", "posted", now, input.occurred_timezone, input.note, "", input.reason, null, null, null,
            listOf(Entry(UUID.randomUUID().toString(), account.id, account.currency, delta.toString())), emptyList(), emptyList())
        val command = OutboxStore(db).enqueue(saved, "accounts/$accountId/adjustments", "POST", body, ledgerAccountBases(listOf(account)),
            dependencies(projection, listOf("account" to accountId)), listOf(OverlayEffect.upsert(SnapshotItemAccount("account", changed)), OverlayEffect.upsert(SnapshotItemTransaction("transaction", transaction))))
        LocalProjectionStore(db).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))
        command
    }
    suspend fun replace(saved: StoredSession, body: String, id: String): OutboxCommand = db.withTransaction {
        val projection = projection(saved); val old = projection.visible.transactions.single { it.id == id }
        require(old.kind == "adjustment" && old.status == "posted")
        val input = decodeResponse<AdjustmentReplace>(body); require(input.expected_version == old.version && input.note.length <= 2000)
        val account = projection.visible.accounts.single { it.id == old.entries.single().account_id }; require(account.archived_at == null)
        val command = OutboxStore(db).enqueue(saved, "transactions/$id", "PUT", body, listOf(OutboxBase("transaction", id, old.version)) + ledgerAccountBases(listOf(account)),
            dependencies(projection, listOf("transaction" to id, "account" to account.id)),
            listOf(OverlayEffect.upsert(SnapshotItemTransaction("transaction", old.copy(version = ledgerBump(old.version), updated_at = ledgerNow(), note = input.note)))))
        LocalProjectionStore(db).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))
        command
    }
    suspend fun delete(saved: StoredSession, body: String, id: String): OutboxCommand = db.withTransaction {
        val projection = projection(saved); val old = projection.visible.transactions.single { it.id == id }
        require(old.kind == "adjustment" && old.status == "posted")
        val input = decodeResponse<TransactionDelete>(body); require(input.expected_version == old.version && input.related_versions.isEmpty()); ledgerBump(old.version)
        val entry = old.entries.single(); val account = projection.visible.accounts.single { it.id == entry.account_id }; require(account.archived_at == null)
        val command = OutboxStore(db).enqueue(saved, "transactions/$id", "DELETE", body, listOf(OutboxBase("transaction", id, old.version)) + ledgerAccountBases(listOf(account)),
            dependencies(projection, listOf("transaction" to id, "account" to account.id)), listOf(OverlayEffect.delete("transaction", id), OverlayEffect.upsert(SnapshotItemAccount("account", ledgerAccountDelta(account, BigInteger(entry.amount_minor).negate(), ledgerNow())))))
        LocalProjectionStore(db).read(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId))
        command
    }
}
