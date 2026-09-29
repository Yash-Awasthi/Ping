package com.ping.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ping.app.data.local.AppDatabase
import com.ping.app.model.Contact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun insertedContactsAreObservedNewestFirst() = runBlocking {
        val dao = db.contactDao()
        dao.insert(Contact(id = "old", displayName = "Old", receivedAt = 1))
        dao.insert(Contact(id = "new", displayName = "New", receivedAt = 2))
        assertEquals(listOf("new", "old"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun deleteRemovesTheRow() = runBlocking {
        val dao = db.contactDao()
        val c = Contact(id = "x", displayName = "X")
        dao.insert(c)
        dao.delete(c)
        assertNull(dao.getById("x"))
        assertEquals(0, dao.count())
    }

    @Test
    fun insertWithSameIdReplaces() = runBlocking {
        val dao = db.contactDao()
        dao.insert(Contact(id = "x", displayName = "A"))
        dao.insert(Contact(id = "x", displayName = "B"))
        assertEquals("B", dao.getById("x")?.displayName)
        assertEquals(1, dao.count())
    }
}
