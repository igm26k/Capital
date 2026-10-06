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

@Database(entities = [ConnectionSettings::class, FinancialCommand::class], version = 2, exportSchema = true)
abstract class CapitalDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
    abstract fun commands(): CommandDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS financial_commands (commandId TEXT NOT NULL PRIMARY KEY, origin TEXT NOT NULL, ownerId TEXT NOT NULL, workspaceId TEXT NOT NULL, sessionId TEXT NOT NULL, generationId TEXT NOT NULL, path TEXT NOT NULL, method TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL, result TEXT, errorCode TEXT)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_financial_commands_origin_ownerId_workspaceId ON financial_commands (origin, ownerId, workspaceId)")
            }
        }
    }
}
