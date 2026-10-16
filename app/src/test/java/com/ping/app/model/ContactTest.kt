package com.ping.app.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContactTest {

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
    fun `a card with no name still gets a vcard name`() {
        assertTrue(Contact(email = "a@b.c").toVCard().contains("FN:a@b.c"))
    }

    @Test
    fun `same phone or email is the same person`() {
        val a = Contact(displayName = "Ada", phone = "+1 (555) 010-2030", email = "Ada@X.io")
        assertTrue(a.isSamePerson(Contact(phone = "15550102030")))
        assertTrue(a.isSamePerson(Contact(email = " ada@x.io ")))
        assertFalse(a.isSamePerson(Contact(displayName = "Ada", phone = "999")))
        assertFalse(Contact(displayName = "Ada").isSamePerson(Contact(displayName = "Ada")))
    }

    @Test
    fun `exported vcards import back to the same cards`() {
        val cards = listOf(
            Contact(displayName = "Ada; Lovelace", phone = "+1 555", email = "a@b.c", note = "line one\nline two"),
            Contact(displayName = "Bob", social = "@bob"),
        )
        val back = Contact.fromVCards(Contact.toVCards(cards))
        assertEquals(cards.map { it.copy(id = "", receivedAt = 0) }, back.map { it.copy(id = "", receivedAt = 0) })
    }

    @Test
    fun `vcard 2_1 with folding, quoted-printable and a photo still imports`() {
        val vcf = "BEGIN:VCARD\r\nVERSION:2.1\r\nN:Lovelace;Ada;;;\r\n" +
            "NOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:caf=C3=A9 =\r\nnotes\r\n" +
            "TEL;CELL:555\r\nTEL;HOME:666\r\nPHOTO;ENCODING=BASE64:AAAA==\r\n" +
            "END:VCARD\r\nBEGIN:VCARD\r\nEND:VCARD\r\n"
        val c = Contact.fromVCards(vcf).single()
        assertEquals("Ada Lovelace", c.displayName)
        assertEquals("café notes", c.note)
        assertEquals("555", c.phone)
    }

    @Test
    fun `memo is never exported`() {
        assertFalse(Contact(displayName = "Ada", memo = "met at the fair").toVCard().contains("fair"))
    }

    @Test
    fun `peer fields are clamped to 200 chars`() {
        val long = "x".repeat(5000)
        val c = Contact.fromMap(mapOf("name" to long, "phone" to long, "email" to long, "social" to long, "note" to long))
        listOf(c.displayName, c.phone, c.email, c.social, c.note).forEach { assertEquals(200, it.length) }
    }
}
