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

@Database(entities = [ConnectionSettings::class], version = 1, exportSchema = true)
abstract class CapitalDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
}
