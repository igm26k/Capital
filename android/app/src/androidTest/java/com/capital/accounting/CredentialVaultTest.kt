package com.capital.accounting

import android.util.AtomicFile
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.capital.accounting.auth.CredentialVault
import com.capital.accounting.auth.StoredSession
import com.capital.accounting.auth.VaultRead
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {
    private val context = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private val id = UUID.randomUUID()
    private val file get() = File(context.noBackupFilesDir, "vault-test-$id")
    private val alias get() = "capital.vault-test.$id"
    private fun vault() = CredentialVault(context, "vault-test-$id", alias)
    private fun removeTestKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    private fun session() = StoredSession("https://accounting.example", "synthetic_token_" + "x".repeat(48),
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString())

    @Test fun encryptedSessionSurvivesReopenAndStaysBoundToOrigin() = runBlocking {
        val vault = vault()
        vault.clear()
        try {
            val expected = session()
            vault.save(expected)
            val first = file.readText()
            assertFalse(first.contains(expected.token))
            assertFalse(first.contains(expected.ownerId))
            val restored = vault().load(expected.origin) as VaultRead.Available
            assertEquals(expected.token, restored.session.token)
            assertEquals(expected.ownerId, restored.session.ownerId)
            assertEquals(expected.workspaceId, restored.session.workspaceId)
            assertEquals(expected.generationId, restored.session.generationId)
            assertEquals(expected.sessionId, restored.session.sessionId)
            assertSame(VaultRead.OtherOrigin, vault.load("https://other.example"))
            assertTrue(vault.load(expected.origin) is VaultRead.Available)
            // Interrupt an atomic replacement before finishWrite: retain the previous complete session.
            AtomicFile(file).startWrite().use { it.write("partial replacement".toByteArray()) }
            assertEquals(expected.token, (vault.load(expected.origin) as VaultRead.Available).session.token)
            vault.save(expected)
            assertNotEquals(first, file.readText())
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
            assertNull(key.encoded)
            assertFalse(expected.toString().contains(expected.token))
            vault.clear()
            assertSame(VaultRead.Empty, vault.load(expected.origin))
        } finally { vault.clear(); removeTestKey() }
    }

    @Test fun rejectsTamperingOriginForgeryAndMissingKeyWithoutErasingEvidence() = runBlocking {
        val vault = vault()
        vault.clear()
        try {
            val expected = session()
            vault.save(expected)
            val packet = JSONObject(file.readText())
            packet.put("origin", "https://other.example")
            file.writeText(packet.toString())
            assertSame(VaultRead.Invalid, vault.load("https://other.example"))
            assertTrue(file.exists())
            vault.save(expected)
            val corrupted = JSONObject(file.readText())
            val ciphertext = Base64.decode(corrupted.getString("ciphertext"), Base64.NO_WRAP)
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            corrupted.put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            file.writeText(corrupted.toString())
            assertSame(VaultRead.Invalid, vault.load(expected.origin))
            vault.save(expected)
            removeTestKey()
            assertSame(VaultRead.Invalid, vault.load(expected.origin))
            assertTrue(file.exists())
            vault.save(expected)
            assertTrue(vault.load(expected.origin) is VaultRead.Available)
        } finally { vault.clear(); removeTestKey() }
    }
}
