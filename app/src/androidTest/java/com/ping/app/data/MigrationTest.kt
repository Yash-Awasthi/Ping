package com.ping.app.data

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ping.app.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Needs the exported 1.json, 2.json and 3.json schemas packaged as androidTest assets. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        // The driver opens the file directly, so a fresh install's missing databases/ dir must exist first.
        file = instrumentation.targetContext.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs() },
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class,
    )

    @Test
    fun contactFromVersionOneSurvivesToLatestWithEmptyMemo() = runBlocking {
        helper.createDatabase(1).use { db ->
            db.execSQL(
                "INSERT INTO contacts (id, displayName, phone, email, social, note, receivedAt, isFavorite) " +
                    "VALUES ('c1', 'Ada', '555', 'ada@x.io', '', 'hi', 10, 1)",
            )
        }
        val db = helper.runMigrationsAndValidate(3, listOf(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3))
        db.use {
            it.prepare("SELECT displayName, isFavorite, memo FROM contacts WHERE id = 'c1'").use { row ->
                assertTrue(row.step())
                assertEquals("Ada", row.getText(0))
                assertEquals(1L, row.getLong(1))
                assertEquals("", row.getText(2))
            }
        }
    }
}
