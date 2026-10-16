package com.ping.app.data

import com.ping.app.model.Contact
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ContactBackupTest {

    private val ada = Contact(
        id = "a", displayName = "Ada", phone = "+1 555-0100", email = "ada@x.io", social = "@ada",
        note = "hi; there,\nfriend", receivedAt = 10, isFavorite = true, memo = "met at PyCon",
    )

    @Test
    fun `round trip keeps every field including memo, star and timestamp`() {
        assertEquals(listOf(ada), decodeBackup(encodeBackup(listOf(ada))))
    }

    @Test
    fun `backup carries its format version`() {
        assertTrue(encodeBackup(emptyList()).contains("\"version\":$BACKUP_VERSION"))
    }

    @Test
    fun `rejects malformed, foreign and newer files`() {
        assertThrows<IllegalArgumentException> { decodeBackup("not json") }
        assertThrows<IllegalArgumentException> { decodeBackup("""{"contacts":[]}""") }
        assertThrows<IllegalArgumentException> { decodeBackup("""{"version":99,"contacts":[]}""") }
        assertThrows<IllegalArgumentException> { decodeBackup("""{"version":1,"contacts":[{"phone":5}]}""") }
        assertThrows<IllegalArgumentException> { decodeBackup("[]") }
    }

    @Test
    fun `drops empty cards and fills a blank id`() {
        val text = """{"version":1,"contacts":[{"id":"x"},{"id":"","displayName":"Bo"}],"extra":true}"""
        val got = decodeBackup(text)
        assertEquals(listOf("Bo"), got.map { it.displayName })
        assertTrue(got.single().id.isNotBlank())
    }

    @Test
    fun `restoring onto the same phone twice writes nothing new`() {
        assertEquals(emptyList<Contact>(), mergeBackup(listOf(ada), listOf(ada)))
    }

    @Test
    fun `matching person keeps the phone's id and memo, takes the newer card and either star`() {
        val onPhone = Contact(id = "p", displayName = "Ada L", phone = "15550100", receivedAt = 5, memo = "")
        val got = mergeBackup(listOf(onPhone), listOf(ada)).single()
        assertEquals(ada.copy(id = "p"), got)

        val withMemo = onPhone.copy(memo = "neighbour", receivedAt = 20)
        val kept = mergeBackup(listOf(withMemo), listOf(ada)).single()
        assertEquals(withMemo.copy(isFavorite = true), kept)
    }

    @Test
    fun `new people are added once even when the backup repeats them`() {
        val bob = Contact(id = "b", displayName = "Bob", email = "BOB@x.io")
        val bobAgain = Contact(id = "b2", displayName = "Bob", email = "bob@x.io", receivedAt = 0)
        val got = mergeBackup(listOf(ada), listOf(bob, bobAgain))
        assertEquals(listOf("b"), got.map { it.id })
    }
}
