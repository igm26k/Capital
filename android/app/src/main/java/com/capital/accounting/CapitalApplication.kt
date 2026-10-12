package com.capital.accounting

import android.app.Application
import com.capital.accounting.auth.CredentialVault
import androidx.room.Room
import com.capital.accounting.data.CapitalDatabase

class CapitalApplication : Application() {
    val financialDrafts: com.capital.accounting.finance.FinancialDrafts by lazy { com.capital.accounting.finance.FinancialDrafts(this) }
    val credentialVault: CredentialVault by lazy { CredentialVault(this) }
    val database: CapitalDatabase by lazy {
        Room.databaseBuilder(this, CapitalDatabase::class.java, "capital.db").addMigrations(CapitalDatabase.MIGRATION_1_2, CapitalDatabase.MIGRATION_2_3, CapitalDatabase.MIGRATION_3_4, CapitalDatabase.MIGRATION_4_5, CapitalDatabase.MIGRATION_5_6, CapitalDatabase.MIGRATION_6_7, CapitalDatabase.MIGRATION_7_8, CapitalDatabase.MIGRATION_8_9).build()
    }
}
