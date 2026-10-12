package com.capital.accounting

import com.capital.accounting.api.*
import com.capital.accounting.data.*
import com.capital.accounting.sync.validatedPullSequence
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PullValidationTest {
    private fun id() = UUID.randomUUID().toString()
    private val scope = MirrorScope("https://accounting.example", id(), id())
    private val head = MirrorHead(scope.key, id(), "0", "opaque-old")
    private fun change(sequence: String = "1", action: String = id()) = WorkspaceChangeTagUpsert(1, id(), action, scope.workspace, sequence, 1, 1,
        "2026-10-12T12:00:00Z", "upsert", "tag", Tag(id(), scope.workspace, "1", "2026-10-12T12:00:00Z", "2026-10-12T12:00:00Z", null, "Pull tag", null), head.generation)
    private fun page(change: WorkspaceChangeTagUpsert = change()) = SyncChanges(scope.workspace, listOf(SyncGroup(change.sequence, change.action_id, scope.workspace, listOf(change), head.generation)), "opaque-next", false, "1", "1", head.generation)
    @Test fun typedUpsertDeleteAndExactInt64SequencesRoundTrip() {
        val original = page(); val decoded = decodeResponse<SyncChanges>(ApiClient.json.encodeToString(original))
        assertEquals(original, decoded); assertEquals("1", validatedPullSequence(scope, head, decoded))
        val action = id()
        val deleted = WorkspaceChangeTagDelete(1, id(), action, scope.workspace, "9223372036854775807", 1, 1, "2026-10-12T12:00:00Z", "delete", "tag", Tombstone("tag", id(), scope.workspace, "2", "2026-10-12T12:00:00Z"), head.generation)
        val last = SyncChanges(scope.workspace, listOf(SyncGroup(deleted.sequence, action, scope.workspace, listOf(deleted), head.generation)), "opaque-last", false, deleted.sequence, "1", head.generation)
        val roundTrip = decodeResponse<SyncChanges>(ApiClient.json.encodeToString(last))
        assertTrue(roundTrip.groups.single().changes.single() is WorkspaceChangeTagDelete)
        assertEquals(deleted.sequence, validatedPullSequence(scope, head.copy(sequence = "9223372036854775806"), roundTrip))
    }
    @Test fun incompleteForeignDuplicateAndInconsistentEnvelopesAreRejected() {
        val c = change(); val valid = page(c)
        val invalidChanges = listOf(c.copy(ordinal = 2), c.copy(group_size = 2), c.copy(action_id = id()), c.copy(generation_id = id()), c.copy(data = c.data.copy(workspace_id = id())))
        for (bad in invalidChanges) reject(valid.copy(groups = listOf(valid.groups.single().copy(changes = listOf(bad)))))
        reject(valid.copy(groups = listOf(valid.groups.single().copy(sequence = "2"))))
        reject(valid.copy(workspace_id = id())); reject(valid.copy(generation_id = id())); reject(valid.copy(has_more = true))
        val duplicate = listOf(c.copy(group_size = 2), c.copy(ordinal = 2, group_size = 2))
        reject(valid.copy(groups = listOf(valid.groups.single().copy(changes = duplicate))))
    }
    @Test fun emptyHeadAndRetentionBoundaryDoNotSkipGroups() {
        val empty = SyncChanges(scope.workspace, emptyList(), "opaque-refreshed", false, "0", "1", head.generation)
        assertEquals("0", validatedPullSequence(scope, head, empty))
        reject(empty.copy(head_sequence = "1")); reject(empty.copy(min_available_sequence = "2"))
        reject(empty.copy(next_cursor = "")); reject(empty.copy(head_sequence = "9223372036854775808"))
    }
    private fun reject(page: SyncChanges) { try { validatedPullSequence(scope, head, page); fail("Invalid pull accepted") } catch (_: IllegalArgumentException) { } }
}
