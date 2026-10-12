package com.capital.accounting

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.capital.accounting.api.*
import com.capital.accounting.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IdentityStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<CapitalApplication>()
    private fun id() = UUID.randomUUID().toString()
    private fun open(name: String) = Room.databaseBuilder(context, CapitalDatabase::class.java, name).addMigrations(CapitalDatabase.MIGRATION_1_2, CapitalDatabase.MIGRATION_2_3, CapitalDatabase.MIGRATION_3_4, CapitalDatabase.MIGRATION_4_5, CapitalDatabase.MIGRATION_5_6, CapitalDatabase.MIGRATION_6_7, CapitalDatabase.MIGRATION_7_8, CapitalDatabase.MIGRATION_8_9).build()
    @Test fun metadataReopenContainsNoBearerAndRejectsAnotherSession() = runBlocking<Unit> {
        val name = "identity-store.db"; context.deleteDatabase(name); var db = open(name)
        val token = "synthetic-secret-canary-" + id()
        val auth = BearerAuth(Profile(id(), "cached-owner@example.test", "UTC"), Workspace(id(), "Identity", "1", "owner", id()), Session(id(), "Device", true, "2026-10-01T12:00:00Z", "2026-10-12T12:00:00Z", "2030-10-12T12:00:00Z", "2030-10-13T12:00:00Z"), "bearer", token, "Bearer")
        val saved = auth.credential("https://accounting.example")
        try {
            IdentityStore(db).save(auth, saved.origin)
            val row = db.identities().get(MirrorScope(saved.origin, saved.ownerId, saved.workspaceId).key)!!
            assertFalse(row.payload.contains(token)); assertFalse(row.payload.contains("access_token")); assertFalse(row.payload.contains("password"))
            db.close(); db = open(name)
            val restored = IdentityStore(db).read(saved)!!
            assertEquals(auth.profile, restored.profile); assertEquals(auth.workspace, restored.workspace); assertEquals(auth.session, restored.session)
            val other = auth.copy(session = auth.session.copy(id = id())).credential(saved.origin)
            assertNull(IdentityStore(db).read(other))
            db.close()
            context.openOrCreateDatabase(name, 0, null).use { legacy -> legacy.execSQL("DROP TABLE cached_identities"); legacy.execSQL("DROP TABLE outbox_receipts"); legacy.execSQL("DROP TABLE outbox_overlays"); legacy.execSQL("DROP TABLE outbox_commands")
                legacy.execSQL("DROP TABLE room_master_table"); legacy.execSQL("PRAGMA user_version=6") }
            db = open(name); assertNull(IdentityStore(db).read(saved)); IdentityStore(db).save(auth, saved.origin)
            assertNotNull(IdentityStore(db).read(saved))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
