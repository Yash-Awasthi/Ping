package com.ping.app.ui.contacts

import com.ping.app.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactFilterTest {

    private val ada = Contact(id = "a", displayName = "Ada Lovelace", email = "ada@x.io", receivedAt = 1)
    private val bob = Contact(id = "b", displayName = "Bob", phone = "555", receivedAt = 3)
    private val cy = Contact(id = "c", displayName = "Cy", note = "met at PyCon", isFavorite = true, receivedAt = 2)

    @Test
    fun `blank query keeps everyone, favourites first then newest`() {
        assertEquals(listOf("c", "b", "a"), filterContacts(listOf(ada, bob, cy), "  ").map { it.id })
    }

    @Test
    fun `matches any field ignoring case`() {
        assertEquals(listOf("a"), filterContacts(listOf(ada, bob, cy), "LOVE").map { it.id })
        assertEquals(listOf("b"), filterContacts(listOf(ada, bob, cy), "555").map { it.id })
        assertEquals(listOf("c"), filterContacts(listOf(ada, bob, cy), "pycon").map { it.id })
    }

    @Test
    fun `no match gives an empty list`() {
        assertEquals(emptyList<Contact>(), filterContacts(listOf(ada, bob, cy), "zzz"))
    }
}
