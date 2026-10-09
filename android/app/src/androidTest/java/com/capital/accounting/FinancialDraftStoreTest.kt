package com.capital.accounting

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capital.accounting.api.ApiClient
import com.capital.accounting.finance.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FinancialDraftStoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(scope: FinancialDraftScope) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (scope.saving && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(scope.saving)
        assertFalse(scope.failed)
    }

    @Test fun atomicDiskReloadKeepsRawValuesIdentitiesAndAllScopeBoundaries() = runBlocking<Unit> {
        val origin = "https://drafts.example"
        val owner = UUID.randomUUID().toString()
        val workspace = UUID.randomUUID().toString()
        val drafts = FinancialDrafts(app)
        val scope = drafts.open(origin, owner, workspace)
        val allocations = listOf(AllocationDraft(amount = "1,001"), AllocationDraft(amount = "invalid local input"))
        val refund = listOf(RefundDraft(allocations.first().id, amount = "0.001"))
        instrumentation.runOnMainSync {
            scope.put("transaction.amount", ApiClient.json.encodeToString("not yet valid"))
            scope.put("transaction.note", ApiClient.json.encodeToString("<svg onload=alert(1)> local"))
            scope.put("transaction.parts", ApiClient.json.encodeToString(allocations))
            scope.put("refund.parts", ApiClient.json.encodeToString(refund))
            scope.put("transfer.expectedFeeVersion", ApiClient.json.encodeToString("7"))
            scope.put("transfer.preservedInstant", ApiClient.json.encodeToString("2026-10-06T08:00:00.123456Z"))
            scope.put("adjustment.balanceVersion", ApiClient.json.encodeToString("19"))
            scope.persist()
        }
        await(scope)
        val reload = FinancialDrafts(app).open(origin, owner, workspace)
        assertEquals(allocations, ApiClient.json.decodeFromString<List<AllocationDraft>>(reload.read("transaction.parts")!!))
        assertEquals(refund, ApiClient.json.decodeFromString<List<RefundDraft>>(reload.read("refund.parts")!!))
        assertEquals("not yet valid", ApiClient.json.decodeFromString<String>(reload.read("transaction.amount")!!))
        assertEquals("19", ApiClient.json.decodeFromString<String>(reload.read("adjustment.balanceVersion")!!))
        assertEquals("7", ApiClient.json.decodeFromString<String>(reload.read("transfer.expectedFeeVersion")!!))
        assertEquals("2026-10-06T08:00:00.123456Z", ApiClient.json.decodeFromString<String>(reload.read("transfer.preservedInstant")!!))
        listOf(Triple("https://other.example", owner, workspace), Triple(origin, UUID.randomUUID().toString(), workspace), Triple(origin, owner, UUID.randomUUID().toString())).forEach { (server, user, space) ->
            assertNull(drafts.open(server, user, space).read("transaction.note"))
        }
        assertNull(app.database.commands().get(origin, owner, workspace))
    }

    @Test fun malformedOrUnsupportedDraftIsPreservedAndWriteFailureRequiresExplicitRetry() = runBlocking<Unit> {
        val origin = "https://drafts.example"
        val owner = UUID.randomUUID().toString()
        val workspace = UUID.randomUUID().toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(ApiClient.json.encodeToString(listOf(origin, owner, workspace)).toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(app.noBackupFilesDir, "financial-drafts").apply { mkdirs() }
        val file = File(directory, "$hash.json")
        for (packet in listOf("broken local JSON", "{\"schema\":2,\"scope\":\"$hash\",\"fields\":{}}", "{\"schema\":1,\"scope\":\"$hash\",\"fields\":{\"transaction.parts\":\"false\"}}")) {
            file.writeText(packet)
            try { FinancialDrafts(app).open(origin, owner, workspace); fail("Damaged draft must remain unmodified") }
            catch (_: Exception) { }
            assertEquals(packet, file.readText())
        }
        var attempts = 0
        val scope = FinancialDraftScope(emptyMap()) { _, complete -> attempts++; complete(attempts > 1) }
        instrumentation.runOnMainSync {
            scope.put("transaction.note", ApiClient.json.encodeToString("still in memory"))
            scope.persist()
            assertTrue(scope.failed)
            scope.persist()
            assertEquals(1, attempts)
            scope.persist(retry = true)
            assertFalse(scope.failed)
            assertEquals(2, attempts)
        }
        file.delete()
        // Exercise an actual AtomicFile rename/write failure, not only a simulated callback.
        val disk = FinancialDrafts(app).open(origin, owner, workspace)
        assertTrue(file.mkdir())
        val obstruction = File(file, "keep-original").apply { writeText("original") }
        instrumentation.runOnMainSync {
            disk.put("transaction.note", ApiClient.json.encodeToString("retained after disk error"))
            disk.persist()
        }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (disk.saving && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(disk.saving)
        assertTrue(disk.failed)
        assertEquals("original", obstruction.readText())
        instrumentation.runOnMainSync { disk.persist() }
        assertFalse(disk.saving)
        assertTrue(obstruction.delete())
        assertTrue(file.delete())
        instrumentation.runOnMainSync { disk.persist(retry = true) }
        await(disk)
        assertEquals(disk.read("transaction.note"), FinancialDrafts(app).open(origin, owner, workspace).read("transaction.note"))
    }
}
