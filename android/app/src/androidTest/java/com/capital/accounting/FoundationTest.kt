package com.capital.accounting

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.capital.accounting.data.CapitalDatabase
import com.capital.accounting.data.ConnectionSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoundationTest {
    @Test fun settingsSurviveDatabaseReopenAndWorkManagerInitializes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<CapitalApplication>()
        val name = "foundation-test.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
        try {
            database.settings().save(ConnectionSettings(origin = "https://localhost:8444"))
            database.close()
            database = Room.databaseBuilder(context, CapitalDatabase::class.java, name).build()
            assertEquals("https://localhost:8444", database.settings().get()?.origin)
            assertNotNull(WorkManager.getInstance(context))
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
