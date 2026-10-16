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

    /**
     * Saves a received card. A repeat swap from a matching person with the same name refreshes that
     * entry, keeping its id, star and memo; a different name sharing a phone or email gets its own row.
     */
    suspend fun saveReceived(contact: Contact): Contact {
        val existing = contacts.first().firstOrNull {
            it.isSamePerson(contact) && it.displayName.trim().equals(contact.displayName.trim(), ignoreCase = true)
        }
        val toSave = if (existing == null) contact
        else contact.copy(id = existing.id, isFavorite = existing.isFavorite, memo = existing.memo)
        contactDao.insert(toSave)
        return toSave
    }

    /** Saves cards from a .vcf file, merging each into a matching contact; returns how many. */
    suspend fun import(cards: List<Contact>): Int {
        cards.forEach { saveReceived(it) }
        return cards.size
    }

    /** Merges contacts from a backup file without duplicating anyone; returns how many it held. */
    suspend fun restore(backup: List<Contact>): Int {
        contactDao.insertAll(mergeBackup(contacts.first(), backup))
        return backup.size
    }

    suspend fun update(contact: Contact) = contactDao.update(contact)

    suspend fun delete(contact: Contact) = contactDao.delete(contact)

    suspend fun deleteById(id: String) {
        contactDao.getById(id)?.let { contactDao.delete(it) }
    }

    suspend fun deleteAll() = contactDao.deleteAll()

    suspend fun count(): Int = contactDao.count()

    val contactCount: Flow<Int> = contactDao.observeCount()
}
