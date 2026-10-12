package com.ping.app.data.local

import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.ping.app.model.Contact
import com.ping.app.model.Profile
import com.ping.app.rollcall.Attendance
import com.ping.app.rollcall.RollCallDao
import com.ping.app.rollcall.RollSession
import com.ping.app.rollcall.Student

@Database(
    entities = [Contact::class, Profile::class, Student::class, RollSession::class, Attendance::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun profileDao(): ProfileDao
    abstract fun rollCallDao(): RollCallDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE contacts ADD COLUMN memo TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("CREATE TABLE IF NOT EXISTS students (roll TEXT NOT NULL, name TEXT NOT NULL, device TEXT NOT NULL, PRIMARY KEY(roll))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS roll_sessions (id TEXT NOT NULL, label TEXT NOT NULL, startedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS attendance (sessionId TEXT NOT NULL, roll TEXT NOT NULL, " +
                        "seenAt INTEGER NOT NULL, flag TEXT NOT NULL, PRIMARY KEY(sessionId, roll))",
                )
            }
        }
    }
}
