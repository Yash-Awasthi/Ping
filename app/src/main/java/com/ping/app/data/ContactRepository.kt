package com.ping.app.data

import com.ping.app.data.local.ContactDao
import com.ping.app.model.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence layer for contact cards via Room.
 *
 * Exposes reactive [Flow] for UI observation.
 */
@Singleton
class ContactRepository @Inject constructor(
    private val contactDao: ContactDao,
) {
    /** Observable stream of all contacts. */
    val contacts: Flow<List<Contact>> = contactDao.observeAll()

    /** Alias used by some ViewModels. */
    val allContacts: Flow<List<Contact>> = contacts

    suspend fun getById(id: String): Contact? = contactDao.getById(id)

    suspend fun save(contact: Contact) = contactDao.insert(contact)

    /** Saves a received card; a repeat swap refreshes the existing entry and keeps its id and star. */
    suspend fun saveReceived(contact: Contact): Contact {
        val existing = contacts.first().firstOrNull { it.isSamePerson(contact) }
        val toSave = if (existing == null) contact
        else contact.copy(id = existing.id, isFavorite = existing.isFavorite)
        contactDao.insert(toSave)
        return toSave
    }

    suspend fun update(contact: Contact) = contactDao.update(contact)

    suspend fun delete(contact: Contact) = contactDao.delete(contact)

    suspend fun deleteById(id: String) {
        contactDao.getById(id)?.let { contactDao.delete(it) }
    }

    suspend fun deleteAll() = contactDao.deleteAll()

    suspend fun count(): Int = contactDao.count()
}
