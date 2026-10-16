package com.ping.app.data

import com.ping.app.model.Contact
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `sealed backup round trips with its passphrase and hides the contents`() {
        val sealed = encodeBackup(listOf(ada), "correct horse".toCharArray())
        assertFalse(sealed.decodeToString().contains("Ada"))
        assertEquals(listOf(ada), decodeBackup(sealed, "correct horse".toCharArray()))
    }

    @Test
    fun `sealed backup refuses a wrong or missing passphrase clearly`() {
        val sealed = encodeBackup(listOf(ada), "correct horse".toCharArray())
        assertTrue(assertThrows<BackupLockedException> { decodeBackup(sealed, "wrong".toCharArray()) }.wrongPassphrase)
        assertFalse(assertThrows<BackupLockedException> { decodeBackup(sealed) }.wrongPassphrase)
    }

    @Test
    fun `blank passphrase writes a plain backup that still reads with or without one`() {
        val plain = encodeBackup(listOf(ada), "  ".toCharArray())
        assertTrue(plain.decodeToString().startsWith("{"))
        assertEquals(listOf(ada), decodeBackup(plain))
        assertEquals(listOf(ada), decodeBackup(plain, "unused".toCharArray()))
    }

    @Test
    fun `tampered sealed header is rejected`() {
        val pass = "pw".toCharArray()
        val sealed = encodeBackup(listOf(ada), pass)
        // Offsets: magic 0..7, version 8, salt 9..24, iterations 25..28.
        listOf(0, 8, 9, 28, sealed.size - 1).forEach { i ->
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows<IllegalArgumentException> { decodeBackup(bad, pass) }
        }
        assertThrows<IllegalArgumentException> { decodeBackup(sealed.copyOf(20), pass) }
    }

    @Test
    fun `backup carries its format version`() {
        assertTrue(encodeBackup(emptyList()).decodeToString().contains("\"version\":$BACKUP_VERSION"))
    }

    @Test
    fun `rejects malformed, foreign and newer files`() {
        assertThrows<IllegalArgumentException> { decodeBackup("not json".toByteArray()) }
        assertThrows<IllegalArgumentException> { decodeBackup("""{"contacts":[]}""".toByteArray()) }
        assertThrows<IllegalArgumentException> { decodeBackup("""{"version":99,"contacts":[]}""".toByteArray()) }
        assertThrows<IllegalArgumentException> { decodeBackup("""{"version":1,"contacts":[{"phone":5}]}""".toByteArray()) }
        assertThrows<IllegalArgumentException> { decodeBackup("[]".toByteArray()) }
    }

    @Test
    fun `drops empty cards and fills a blank id`() {
        val text = """{"version":1,"contacts":[{"id":"x"},{"id":"","displayName":"Bo"}],"extra":true}"""
        val got = decodeBackup(text.toByteArray())
        assertEquals(listOf("Bo"), got.map { it.displayName })
        assertTrue(got.single().id.isNotBlank())
    }

    @Test
    fun `restoring onto the same phone twice writes nothing new`() {
        assertEquals(emptyList<Contact>(), mergeBackup(listOf(ada), listOf(ada)))
    }

    @Test
    fun `matching person keeps the phone's id and memo, takes the newer card and either star`() {
        val onPhone = Contact(id = "p", displayName = "ada", phone = "15550100", receivedAt = 5, memo = "")
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

    @Test
    fun `two people sharing a phone stay apart whatever their order`() {
        val eve = Contact(id = "e", displayName = "Eve", phone = "+1 555-0100", receivedAt = 30)
        assertEquals(listOf(ada, eve), mergeBackup(emptyList(), listOf(ada, eve)))
        assertEquals(listOf(eve), mergeBackup(listOf(ada), listOf(ada, eve)))
        assertEquals(listOf(eve, ada), mergeBackup(emptyList(), listOf(eve, ada)))
    }
}
