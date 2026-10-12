package com.capital.accounting.sync

import com.capital.accounting.api.*
import com.capital.accounting.data.MirrorHead
import com.capital.accounting.data.MirrorScope
import kotlinx.serialization.encodeToString
import java.math.BigInteger
import java.util.UUID

/** Validate complete groups against the captured head before any local write. */
fun validatedPullSequence(scope: MirrorScope, head: MirrorHead, page: SyncChanges): String {
    require(head.scope == scope.key && page.workspace_id == scope.workspace && page.generation_id == head.generation)
    uuid(head.generation); require(page.next_cursor.isNotEmpty() && page.groups.size <= 100)
    var last = number(head.sequence)
    val serverHead = number(page.head_sequence); val minimum = number(page.min_available_sequence, positive = true)
    require(last >= minimum - BigInteger.ONE && serverHead >= last && minimum <= serverHead + BigInteger.ONE)
    require(ApiClient.json.encodeToString(page).toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024)
    val changes = mutableSetOf<String>(); val actions = mutableSetOf<String>()
    for (group in page.groups) {
        require(group.generation_id == head.generation && group.workspace_id == scope.workspace)
        uuid(group.action_id); require(actions.add(group.action_id))
        val sequence = number(group.sequence, positive = true); require(sequence == last + BigInteger.ONE && sequence <= serverHead)
        require(group.changes.size in 1..200)
        require(ApiClient.json.encodeToString(group).toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
        val entities = mutableSetOf<Pair<String, String>>()
        group.changes.forEachIndexed { index, change ->
            require(change.schema_version == 1 && change.generation_id == head.generation && change.workspace_id == scope.workspace &&
                change.action_id == group.action_id && change.sequence == group.sequence && change.ordinal == index + 1 && change.group_size == group.changes.size)
            uuid(change.change_id); require(changes.add(change.change_id))
            val identity = pullIdentity(change)
            uuid(identity.id); number(identity.version, positive = true)
            require(identity.workspace == scope.workspace && identity.type == change.entity_type)
            require(change.operation == "delete" || identity.deleted == null)
            require(entities.add(change.entity_type to identity.id))
        }
        last = sequence
    }
    require(page.has_more == (last < serverHead) && (page.groups.isNotEmpty() || last == serverHead))
    return last.toString()
}

internal data class PullIdentity(val id: String, val workspace: String, val version: String, val type: String, val deleted: String?)
internal fun pullIdentity(change: WorkspaceChange): PullIdentity = when (change) {
    is WorkspaceChangeAccountUpsert -> change.data.let { PullIdentity(it.id, it.workspace_id, it.version, "account", it.deleted_at) }
    is WorkspaceChangeTransactionUpsert -> change.data.let { PullIdentity(it.id, it.workspace_id, it.version, "transaction", it.deleted_at) }
    is WorkspaceChangeCategoryUpsert -> change.data.let { PullIdentity(it.id, it.workspace_id, it.version, "category", it.deleted_at) }
    is WorkspaceChangeTagUpsert -> change.data.let { PullIdentity(it.id, it.workspace_id, it.version, "tag", it.deleted_at) }
    is WorkspaceChangeAccountDelete -> change.data.let { PullIdentity(it.id, it.workspace_id, it.version, it.entity_type, it.deleted_at) }
    is WorkspaceChangeTransactionDelete -> change.data.let { PullIdentity(it.id, it.workspace_id, it.version, it.entity_type, it.deleted_at) }
    is WorkspaceChangeCategoryDelete -> change.data.let { PullIdentity(it.id, it.workspace_id, it.version, it.entity_type, it.deleted_at) }
    is WorkspaceChangeTagDelete -> change.data.let { PullIdentity(it.id, it.workspace_id, it.version, it.entity_type, it.deleted_at) }
}
private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
private fun number(value: String, positive: Boolean = false): BigInteger {
    require(value.matches(Regex(if (positive) "[1-9][0-9]*" else "0|[1-9][0-9]*")))
    return BigInteger(value).also { require(it <= BigInteger.valueOf(Long.MAX_VALUE)) }
}
