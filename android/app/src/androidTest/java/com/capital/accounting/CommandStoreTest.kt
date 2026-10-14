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
        .addMigrations(CapitalDatabase.MIGRATION_1_2, CapitalDatabase.MIGRATION_2_3, CapitalDatabase.MIGRATION_3_4, CapitalDatabase.MIGRATION_4_5, CapitalDatabase.MIGRATION_5_6, CapitalDatabase.MIGRATION_6_7, CapitalDatabase.MIGRATION_7_8, CapitalDatabase.MIGRATION_8_9, CapitalDatabase.MIGRATION_9_10, CapitalDatabase.MIGRATION_10_11, CapitalDatabase.MIGRATION_11_12).build()
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
    @Test fun versionTwoPendingCommandSurvivesMigrationAndRejectedReferenceReopen() = runBlocking<Unit> {
        val name = "command-v2-migration.db"
        context.deleteDatabase(name)
        val id = UUID.randomUUID().toString(); val owner = UUID.randomUUID().toString(); val workspace = UUID.randomUUID().toString()
        val session = UUID.randomUUID().toString(); val generation = UUID.randomUUID().toString()
        context.openOrCreateDatabase(name, 0, null).use { old ->
            old.execSQL("CREATE TABLE connection_settings (id INTEGER NOT NULL PRIMARY KEY, origin TEXT NOT NULL)")
            old.execSQL("CREATE TABLE financial_commands (commandId TEXT NOT NULL PRIMARY KEY, origin TEXT NOT NULL, ownerId TEXT NOT NULL, workspaceId TEXT NOT NULL, sessionId TEXT NOT NULL, generationId TEXT NOT NULL, path TEXT NOT NULL, method TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL, result TEXT, errorCode TEXT)")
            old.execSQL("CREATE UNIQUE INDEX index_financial_commands_origin_ownerId_workspaceId ON financial_commands(origin,ownerId,workspaceId)")
            old.execSQL("INSERT INTO connection_settings VALUES (1, 'https://accounting.example')")
            old.execSQL("INSERT INTO financial_commands VALUES (?, 'https://accounting.example', ?, ?, ?, ?, ?, 'POST', '{}', 'pending', NULL, NULL)", arrayOf(id, owner, workspace, session, generation, "/workspaces/$workspace/accounts"))
            old.execSQL("PRAGMA user_version=2")
        }
        var db = open(name)
        try {
            val original = db.commands().get("https://accounting.example", owner, workspace)!!
            assertEquals(id, original.commandId); assertEquals("{}", original.body); assertNull(original.requestId)
            assertEquals("https://accounting.example", db.settings().get()?.origin)
            val reference = UUID.randomUUID().toString()
            assertEquals(1, db.commands().reject(id, "rejected", "version_conflict", reference))
            db.close(); db = open(name)
            val rejected = db.commands().get(original.origin, owner, workspace)!!
            assertEquals(original.copy(state = "rejected", errorCode = "version_conflict", requestId = reference), rejected)
            assertEquals(0, db.commands().reject(id, "rejected", "invalid", UUID.randomUUID().toString()))
            assertEquals(reference, db.commands().get(original.origin, owner, workspace)?.requestId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

}
