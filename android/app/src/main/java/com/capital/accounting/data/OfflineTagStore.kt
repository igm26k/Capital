package com.capital.accounting.data

import androidx.room.withTransaction
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Local intent only: cached identity does not authorize an HTTP mutation. */
class OfflineTagStore(private val db: CapitalDatabase) {
    suspend fun enqueue(saved: StoredSession, body: String, id: String? = null): OutboxCommand = db.withTransaction {
        require(IdentityStore(db).read(saved) != null)
        require(db.commands().get(saved.origin, saved.ownerId, saved.workspaceId) == null)
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val projection = requireNotNull(LocalProjectionStore(db).read(scope))
        require(projection.confirmed.head.generation == saved.generationId && projection.blocked.isEmpty())
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val tag: Tag; val bases: List<OutboxBase>; var dependencies = emptyList<String>()
        if (id == null) {
            val input = decodeResponse<TagCreate>(body); require(input.name.isNotBlank() && input.name.length <= 50)
            require(projection.visible.tags.none { it.id == input.id })
            tag = Tag(input.id, saved.workspaceId, "1", now, now, null, input.name, null); bases = emptyList()
        } else {
            val input = decodeResponse<TagUpdate>(body); require(input.name.isNotBlank() && input.name.length <= 50)
            val visible = requireNotNull(projection.visible.tags.find { it.id == id })
            require(input.expected_version == visible.version)
            val nextVersion = BigInteger(visible.version) + BigInteger.ONE; require(nextVersion <= BigInteger.valueOf(Long.MAX_VALUE))
            for (parent in projection.pending.asReversed()) {
                if (db.outbox().overlays(parent.position).any { it.entityType == "tag" && it.entityId == id }) {
                    dependencies = listOf(parent.actionId); break
                }
            }
            tag = visible.copy(version = nextVersion.toString(), name = input.name, updated_at = now, archived_at = if (input.archived) visible.archived_at ?: now else null)
            bases = listOf(OutboxBase("tag", id, input.expected_version))
        }
        val command = OutboxStore(db).enqueue(saved, if (id == null) "tags" else "tags/$id", if (id == null) "POST" else "PUT", body, bases, dependencies, listOf(OverlayEffect.upsert(SnapshotItemTag("tag", tag))))
        LocalProjectionStore(db).read(scope) // Graph validation participates in the same commit.
        command
    }
}
