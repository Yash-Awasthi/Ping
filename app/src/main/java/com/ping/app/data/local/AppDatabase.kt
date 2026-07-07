package com.ping.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ping.app.model.Contact
import com.ping.app.model.Profile

@Database(
    entities = [Contact::class, Profile::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun profileDao(): ProfileDao
}
