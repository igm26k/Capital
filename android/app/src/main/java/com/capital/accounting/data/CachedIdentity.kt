package com.capital.accounting.data

import androidx.room.*
import com.capital.accounting.api.*
import com.capital.accounting.auth.StoredSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Deliberately excludes transport credentials and passwords. */
@Serializable
data class PublicIdentity(val profile: Profile, val workspace: Workspace, val session: Session) {
    override fun toString() = "PublicIdentity([redacted])"
}
@Entity(tableName = "cached_identities")
data class CachedIdentity(@PrimaryKey val scope: String, val payload: String, val validatedAt: Long) {
    override fun toString() = "CachedIdentity([redacted])"
}
@Dao
interface IdentityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(identity: CachedIdentity)
    @Query("SELECT * FROM cached_identities WHERE scope=:scope") suspend fun get(scope: String): CachedIdentity?
    @Query("DELETE FROM cached_identities WHERE scope=:scope") suspend fun remove(scope: String)
}
class IdentityStore(private val db: CapitalDatabase) {
    suspend fun save(auth: BearerAuth, origin: String, now: Long = System.currentTimeMillis()) {
        require(auth.session.is_current)
        val scope = MirrorScope(origin, auth.profile.id, auth.workspace.id)
        db.identities().save(CachedIdentity(scope.key, ApiClient.json.encodeToString(PublicIdentity(auth.profile, auth.workspace, auth.session)), now))
    }
    suspend fun read(saved: StoredSession, now: Long = System.currentTimeMillis()): PublicIdentity? {
        if (saved.logoutPending || saved.revokeSessionId != null) return null
        val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
        val cached = db.identities().get(scope.key) ?: return null
        return usableIdentity(cached, saved, now)
    }
}
internal fun usableIdentity(cached: CachedIdentity, saved: StoredSession, now: Long): PublicIdentity? = try {
    val scope = MirrorScope(saved.origin, saved.ownerId, saved.workspaceId)
    val identity = decodeResponse<PublicIdentity>(cached.payload)
    if (cached.scope != scope.key || saved.logoutPending || saved.revokeSessionId != null || now < cached.validatedAt ||
        identity.profile.id != saved.ownerId || identity.workspace.id != saved.workspaceId || identity.session.id != saved.sessionId ||
        identity.workspace.sync_generation_id != saved.generationId || !identity.session.is_current ||
        now >= utcMillis(identity.session.expires_at) || now >= utcMillis(identity.session.absolute_expires_at)) null else identity
} catch (_: Exception) { null }
private fun utcMillis(value: String): Long {
    require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")))
    val base = value.take(19)
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val position = ParsePosition(0)
    val date = requireNotNull(parser.parse(base, position)); require(position.index == base.length)
    val fraction = value.drop(19).removeSuffix("Z").removePrefix(".").padEnd(6, '0')
    return date.time + fraction.take(3).toLong()
}
