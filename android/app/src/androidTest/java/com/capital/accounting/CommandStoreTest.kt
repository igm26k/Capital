package com.capital.accounting

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.capital.accounting.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CommandStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private fun open(name: String) = Room.databaseBuilder(context, CapitalDatabase::class.java, name)
        .addMigrations(CapitalDatabase.MIGRATION_1_2).build()
    @Test fun versionOneSettingsSurviveRealMigration() = runBlocking<Unit> {
        val name = "command-migration-test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, 0, null).use { old ->
            old.execSQL("CREATE TABLE connection_settings (id INTEGER NOT NULL PRIMARY KEY, origin TEXT NOT NULL)")
            old.execSQL("INSERT INTO connection_settings VALUES (1, 'https://accounting.example')")
            old.execSQL("PRAGMA user_version=1")
        }
        val db = open(name)
        try {
            assertEquals("https://accounting.example", db.settings().get()?.origin)
            assertNull(db.commands().get("https://accounting.example", UUID.randomUUID().toString(), UUID.randomUUID().toString()))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun pendingBodyIdentityAndConfirmedReceiptSurviveReopen() = runBlocking<Unit> {
        val name = "command-durability-test.db"
        context.deleteDatabase(name)
        val owner = UUID.randomUUID().toString()
        val workspace = UUID.randomUUID().toString()
        val original = FinancialCommand(UUID.randomUUID().toString(), "https://accounting.example", owner,
            workspace, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "/workspaces/$workspace/accounts", "POST", "{\"opening_balance_minor\":\"123456\"}")
        var db = open(name)
        try {
            db.commands().insert(original)
            db.close(); db = open(name)
            assertEquals(original, db.commands().get(original.origin, owner, workspace))
            assertNull(db.commands().get("https://other.example", owner, workspace))
            assertNull(db.commands().get(original.origin, UUID.randomUUID().toString(), workspace))
            try { db.commands().insert(original.copy(commandId = UUID.randomUUID().toString())); fail("Scope already pending") }
            catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertEquals(0, db.commands().remove(original.commandId, "confirmed"))
            assertEquals(1, db.commands().confirm(original.commandId, "{\"synthetic_receipt\":true}"))
            assertEquals(0, db.commands().reject(original.commandId, "rejected", "version_conflict"))
            db.close(); db = open(name)
            val confirmed = db.commands().get(original.origin, owner, workspace)!!
            assertEquals("confirmed", confirmed.state)
            assertEquals(original.body, confirmed.body)
            assertEquals("{\"synthetic_receipt\":true}", confirmed.result)
            assertEquals(0, db.commands().remove(UUID.randomUUID().toString(), "confirmed"))
            assertEquals(1, db.commands().remove(original.commandId, "confirmed"))
            assertNull(db.commands().get(original.origin, owner, workspace))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
