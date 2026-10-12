package com.capital.accounting.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "connection_settings")
data class ConnectionSettings(@PrimaryKey val id: Int = 1, val origin: String)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM connection_settings WHERE id = 1")
    suspend fun get(): ConnectionSettings?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: ConnectionSettings)
}

@Database(entities = [ConnectionSettings::class, FinancialCommand::class, SnapshotAttempt::class, SnapshotStage::class, SnapshotRecord::class, SnapshotToken::class, MirrorHead::class, MirrorRecord::class, MirrorTombstone::class, MirrorChange::class, CachedIdentity::class, OutboxCommand::class, OutboxOverlay::class, OutboxReceipt::class, OutboxFailure::class], version = 10, exportSchema = true)
abstract class CapitalDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
    abstract fun commands(): CommandDao
    abstract fun snapshots(): SnapshotDao
    abstract fun pull(): PullDao
    abstract fun identities(): IdentityDao
    abstract fun outbox(): OutboxDao
    abstract fun receipts(): OutboxReceiptDao
    abstract fun failures(): OutboxFailureDao

    companion object {
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE outbox_failures (commandPosition INTEGER NOT NULL PRIMARY KEY, status INTEGER, code TEXT, requestId TEXT, FOREIGN KEY(commandPosition) REFERENCES outbox_commands(position) ON DELETE CASCADE)")
            }
        }
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE outbox_receipts (commandPosition INTEGER NOT NULL PRIMARY KEY, generation TEXT NOT NULL, sequence TEXT NOT NULL, payload TEXT NOT NULL, FOREIGN KEY(commandPosition) REFERENCES outbox_commands(position) ON DELETE CASCADE)")
            }
        }
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE outbox_commands (position INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, scope TEXT NOT NULL, actionId TEXT NOT NULL, origin TEXT NOT NULL, owner TEXT NOT NULL, workspace TEXT NOT NULL, issuerSession TEXT NOT NULL, generation TEXT NOT NULL, path TEXT NOT NULL, method TEXT NOT NULL, body TEXT NOT NULL, baseVersions TEXT NOT NULL, dependencies TEXT NOT NULL, state TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX index_outbox_commands_scope_actionId ON outbox_commands(scope,actionId)")
                db.execSQL("CREATE TABLE outbox_overlays (commandPosition INTEGER NOT NULL, entityType TEXT NOT NULL, entityId TEXT NOT NULL, operation TEXT NOT NULL, payload TEXT, PRIMARY KEY(commandPosition,entityType,entityId), FOREIGN KEY(commandPosition) REFERENCES outbox_commands(position) ON DELETE CASCADE)")
            }
        }
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE cached_identities (scope TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, validatedAt INTEGER NOT NULL)")
            }
        }
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE mirror_tombstones (scope TEXT NOT NULL, entityType TEXT NOT NULL, entityId TEXT NOT NULL, version TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope,entityType,entityId))")
                db.execSQL("CREATE TABLE mirror_changes (scope TEXT NOT NULL, changeId TEXT NOT NULL, PRIMARY KEY(scope,changeId))")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE snapshot_attempts (scope TEXT NOT NULL PRIMARY KEY, snapshot TEXT NOT NULL)")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE snapshot_stages (scope TEXT NOT NULL PRIMARY KEY, snapshot TEXT NOT NULL, descriptor TEXT NOT NULL, nextToken TEXT, received INTEGER NOT NULL, bytes INTEGER NOT NULL, lastKey TEXT, complete INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE snapshot_records (scope TEXT NOT NULL, entityType TEXT NOT NULL, entityId TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope,entityType,entityId))")
                db.execSQL("CREATE TABLE snapshot_tokens (scope TEXT NOT NULL, token TEXT NOT NULL, PRIMARY KEY(scope,token))")
                db.execSQL("CREATE TABLE mirror_heads (scope TEXT NOT NULL PRIMARY KEY, generation TEXT NOT NULL, sequence TEXT NOT NULL, cursor TEXT NOT NULL)")
                db.execSQL("CREATE TABLE mirror_records (scope TEXT NOT NULL, entityType TEXT NOT NULL, entityId TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope,entityType,entityId))")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE financial_commands ADD COLUMN requestId TEXT")
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS financial_commands (commandId TEXT NOT NULL PRIMARY KEY, origin TEXT NOT NULL, ownerId TEXT NOT NULL, workspaceId TEXT NOT NULL, sessionId TEXT NOT NULL, generationId TEXT NOT NULL, path TEXT NOT NULL, method TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL, result TEXT, errorCode TEXT)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_financial_commands_origin_ownerId_workspaceId ON financial_commands (origin, ownerId, workspaceId)")
            }
        }
    }
}
