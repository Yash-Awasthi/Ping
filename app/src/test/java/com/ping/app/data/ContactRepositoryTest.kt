package com.ping.app.data

import com.ping.app.data.local.ContactDao
import com.ping.app.model.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

private class FakeContactDao : ContactDao {
    val rows = MutableStateFlow<Map<String, Contact>>(emptyMap())
    override fun observeAll(): Flow<List<Contact>> = rows.map { it.values.sortedByDescending(Contact::receivedAt) }
    override suspend fun getById(id: String) = rows.value[id]
    override suspend fun insert(contact: Contact) { rows.value += contact.id to contact }
    override suspend fun insertAll(contacts: List<Contact>) = contacts.forEach { insert(it) }
    override suspend fun update(contact: Contact) { if (contact.id in rows.value) insert(contact) }
    override suspend fun delete(contact: Contact) { rows.value -= contact.id }
    override suspend fun deleteAll() { rows.value = emptyMap() }
    override suspend fun count() = rows.value.size
    override fun observeCount(): Flow<Int> = rows.map { it.size }
}

class ContactRepositoryTest {

    private val dao = FakeContactDao()
    private val repo = ContactRepository(dao)
    private val ada = Contact(id = "a", displayName = "Ada", phone = "555", email = "ada@x.io", isFavorite = true, memo = "fair")

    @Test
    fun `repeat swap with the same name refreshes the card and keeps id, star and memo`() = runBlocking {
        dao.insert(ada)
        val incoming = Contact(displayName = " ada ", phone = "555", email = "new@x.io", note = "hi")
        val saved = repo.saveReceived(incoming)
        assertEquals(incoming.copy(id = "a", isFavorite = true, memo = "fair"), saved)
        assertEquals(listOf(saved), dao.rows.value.values.toList())
    }

    @Test
    fun `a different name on a shared phone is saved as a new contact`() = runBlocking {
        dao.insert(ada)
        val saved = repo.saveReceived(Contact(displayName = "Eve", phone = "555", email = "eve@x.io"))
        assertNotEquals("a", saved.id)
        assertEquals(ada, dao.getById("a"))
        assertEquals(2, dao.count())
    }

    @Test
    fun `a blank name never overwrites a named contact`() = runBlocking {
        dao.insert(ada)
        repo.saveReceived(Contact(email = "ada@x.io"))
        assertEquals(ada, dao.getById("a"))
        assertEquals(2, dao.count())
    }

    @Test
    fun `an unknown person is inserted as is`() = runBlocking {
        val bob = Contact(id = "b", displayName = "Bob", phone = "777")
        dao.insert(ada)
        assertEquals(bob, repo.saveReceived(bob))
        assertEquals(2, dao.count())
    }
}
