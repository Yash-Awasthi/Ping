package com.ping.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactTest {

    @Test
    fun `fromMap reads the wire keys`() {
        val c = Contact.fromMap(mapOf("name" to "Ada", "phone" to "1", "email" to "a@b.c"))
        assertEquals("Ada", c.displayName)
        assertEquals("1", c.phone)
        assertEquals("a@b.c", c.email)
    }

    @Test
    fun `a card with every field blank is empty`() {
        assertTrue(Contact.fromMap(emptyMap()).isEmptyCard)
        assertTrue(Contact.fromMap(mapOf("name" to "  ")).isEmptyCard)
        assertFalse(Contact.fromMap(mapOf("phone" to "1")).isEmptyCard)
    }

    @Test
    fun `vcard carries the filled fields`() {
        val v = Contact(displayName = "Ada", phone = "123", email = "a@b.c").toVCard()
        assertTrue(v.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(v.contains("FN:Ada\r\n"))
        assertTrue(v.contains("TEL:123\r\n"))
        assertTrue(v.contains("EMAIL:a@b.c\r\n"))
        assertFalse(v.contains("NOTE:"))
        assertTrue(v.endsWith("END:VCARD\r\n"))
    }

    @Test
    fun `a note with a newline cannot inject a vcard field`() {
        val v = Contact(displayName = "Eve", note = "hi\r\nTEL:666").toVCard()
        assertFalse(v.contains("\r\nTEL:666"))
        assertTrue(v.contains("NOTE:hi\\nTEL:666"))
    }

    @Test
    fun `same phone or email is the same person`() {
        val a = Contact(displayName = "Ada", phone = "+1 (555) 010-2030", email = "Ada@X.io")
        assertTrue(a.isSamePerson(Contact(phone = "15550102030")))
        assertTrue(a.isSamePerson(Contact(email = " ada@x.io ")))
        assertFalse(a.isSamePerson(Contact(displayName = "Ada", phone = "999")))
        assertFalse(Contact(displayName = "Ada").isSamePerson(Contact(displayName = "Ada")))
    }
}
