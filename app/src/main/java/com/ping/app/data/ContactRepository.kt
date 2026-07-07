package com.ping.app.data

import com.ping.app.data.local.ContactDao
import com.ping.app.model.Contact
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository @Inject constructor(
    private val contactDao: ContactDao
) {
    val allContacts: Flow<List<Contact>> = contactDao.observeAll()

    suspend fun getById(id: String): Contact? = contactDao.getById(id)

    suspend fun save(contact: Contact) = contactDao.insert(contact)

    suspend fun update(contact: Contact) = contactDao.update(contact)

    suspend fun delete(contact: Contact) = contactDao.delete(contact)

    suspend fun deleteAll() = contactDao.deleteAll()

    suspend fun count(): Int = contactDao.count()
}
