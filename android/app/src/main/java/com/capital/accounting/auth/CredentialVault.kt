package com.capital.accounting.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.capital.accounting.normalizedOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class StoredSession(
    val origin: String,
    val token: String,
    val ownerId: String,
    val workspaceId: String,
    val generationId: String,
    val sessionId: String,
    val logoutPending: Boolean = false,
) {
    init {
        require(normalizedOrigin(origin) == origin)
        require(token.length in 32..256 && token.all { it.code in 33..126 })
        listOf(ownerId, workspaceId, generationId, sessionId).forEach {
            require(UUID.fromString(it).toString().equals(it, ignoreCase = true))
        }
    }
    override fun toString() = "StoredSession([redacted])"
}

sealed interface VaultRead {
    data object Empty : VaultRead
    data object OtherOrigin : VaultRead
    data object Invalid : VaultRead
    class Available(val session: StoredSession) : VaultRead {
        override fun toString() = "VaultRead.Available([redacted])"
    }
}

/** Contains no password; all calls perform disk/Keystore work on IO, outside the UI thread. */
class CredentialVault internal constructor(context: Context, storageName: String, private val keyAlias: String) {
    constructor(context: Context) : this(context, "bearer-session-v1", "capital.bearer-session.v1")
    private val file = AtomicFile(File(context.noBackupFilesDir, storageName))

    suspend fun save(session: StoredSession) = withContext(Dispatchers.IO) {
        lock.withLock {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(aad(session.origin))
            val plain = JSONObject().put("token", session.token).put("owner_id", session.ownerId)
                .put("workspace_id", session.workspaceId).put("generation_id", session.generationId)
                .put("session_id", session.sessionId).put("logout_pending", session.logoutPending).toString().toByteArray(Charsets.UTF_8)
            val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
            val packet = JSONObject().put("format", 1).put("origin", session.origin)
                .put("iv", encode(cipher.iv)).put("ciphertext", encode(encrypted))
                .toString().toByteArray(Charsets.UTF_8)
            val output = file.startWrite()
            try { output.write(packet); file.finishWrite(output) }
            catch (e: Exception) { file.failWrite(output); throw e }
        }
    }

    suspend fun load(origin: String): VaultRead = withContext(Dispatchers.IO) {
        val expected = normalizedOrigin(origin)
        lock.withLock {
            try {
                val packetBytes = try {
                    file.openRead().use { input ->
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        while (true) {
                            val size = input.read(buffer)
                            if (size == -1) break
                            require(bytes.size() + size <= 16384)
                            bytes.write(buffer, 0, size)
                        }
                        bytes.toByteArray()
                    }
                } catch (_: java.io.FileNotFoundException) { return@withLock VaultRead.Empty }
                val packet = JSONObject(String(packetBytes, Charsets.UTF_8))
                require(packet.getInt("format") == 1)
                val storedOrigin = packet.getString("origin")
                if (storedOrigin != expected) return@withLock VaultRead.OtherOrigin
                val secret = key(create = false) ?: return@withLock VaultRead.Invalid
                val iv = Base64.decode(packet.getString("iv"), Base64.NO_WRAP)
                require(iv.size == 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
                cipher.updateAAD(aad(expected))
                val plain = cipher.doFinal(Base64.decode(packet.getString("ciphertext"), Base64.NO_WRAP))
                try {
                    val body = JSONObject(String(plain, Charsets.UTF_8))
                    VaultRead.Available(StoredSession(expected, body.getString("token"),
                        body.getString("owner_id"), body.getString("workspace_id"),
                        body.getString("generation_id"), body.getString("session_id"), body.optBoolean("logout_pending", false)))
                } finally { plain.fill(0) }
            } catch (_: Exception) { VaultRead.Invalid }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            file.delete()
            check(listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new")).none { it.exists() }) {
                "Session credential removal failed"
            }
        }
    }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(keyAlias, null)
        if (existing != null) return existing as SecretKey
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun aad(origin: String) = ("capital/bearer-session/v1\u0000" + origin).toByteArray(Charsets.UTF_8)

    companion object {
        private val lock = Mutex()
    }
}
