package com.capital.accounting

import android.app.Application
import androidx.room.Room
import com.capital.accounting.data.CapitalDatabase

class CapitalApplication : Application() {
    val database: CapitalDatabase by lazy {
        Room.databaseBuilder(this, CapitalDatabase::class.java, "capital.db").build()
    }
}
