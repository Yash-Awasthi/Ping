package com.ping.app.room

import com.ping.app.room.RoomProtocol.FileMeta
import com.ping.app.room.RoomProtocol.GuestShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream

class RoomProtocolTest {

    @Test
    fun `merge namespaces ids per owner and lists members`() {
        val m = RoomProtocol.merge(
            "Host",
            listOf(FileMeta("f1", "a.txt", 3)),
            listOf(GuestShare("ep9", "Ada", listOf(FileMeta("f1", "b.txt", 5)))),
        )
        assertEquals(listOf("host:f1", "ep9:f1"), m.files.map { it.id })
        assertEquals(listOf("Host", "Ada"), m.members)
        assertEquals("ep9", RoomProtocol.ownerOf("ep9:f1"))
        assertEquals("f1", RoomProtocol.localIdOf("ep9:f1"))
    }

    @Test
    fun `manifest survives a json round trip`() {
        val m = RoomProtocol.merge("Host", listOf(FileMeta("f1", "a.txt", 3)), emptyList())
        assertEquals(m, RoomProtocol.decodeManifest(RoomProtocol.encodeManifest(m)))
    }

    @Test
    fun `garbage manifests and file lists decode to nothing`() {
        assertNull(RoomProtocol.decodeManifest("not json"))
        assertTrue(RoomProtocol.decodeMetas("{").isEmpty())
    }

    @Test
    fun `decoded metas drop bad ids and negative sizes and clean names`() {
        val json = """[{"id":"a:b","name":"x","size":1},{"id":"ok","name":"../../etc/passwd","size":2},{"id":"neg","name":"n","size":-1}]"""
        assertEquals(listOf(FileMeta("ok", "passwd", 2)), RoomProtocol.decodeMetas(json))
    }

    @Test
    fun `a peer cannot list more files than the cap`() {
        val json = (1..500).joinToString(",", "[", "]") { """{"id":"i$it","name":"n","size":1}""" }
        assertEquals(RoomProtocol.MAX_FILES_PER_MEMBER, RoomProtocol.decodeMetas(json).size)
    }

    @Test
    fun `header is read back and the file bytes follow it`() {
        val body = "hello".toByteArray()
        val stream = SequenceInputStream(ByteArrayInputStream(RoomProtocol.header("host:f1")), ByteArrayInputStream(body))
        assertEquals("host:f1", RoomProtocol.readHeader(stream))
        assertEquals("hello", String(stream.readBytes()))
    }

    @Test
    fun `a truncated or oversized header is rejected`() {
        assertNull(RoomProtocol.readHeader(ByteArrayInputStream(byteArrayOf(0))))
        assertNull(RoomProtocol.readHeader(ByteArrayInputStream(byteArrayOf(0x7f, 0x00, 1))))
        assertNull(RoomProtocol.readHeader(ByteArrayInputStream(byteArrayOf(0, 0))))
    }

    @Test
    fun `safe names never carry a path or a leading dot`() {
        assertEquals("passwd", RoomProtocol.safeName("../../etc/passwd"))
        assertEquals("evil.exe", RoomProtocol.safeName("C:\\Users\\x\\evil.exe"))
        assertEquals("bashrc", RoomProtocol.safeName("...bashrc"))
        assertEquals("file", RoomProtocol.safeName(""))
        assertEquals("file", RoomProtocol.safeName(null))
        assertEquals("ab.txt", RoomProtocol.safeName("a\u0000b.txt"))
    }

    @Test
    fun `long names are cut but keep their extension`() {
        val n = RoomProtocol.safeName("a".repeat(300) + ".pdf")
        assertEquals(RoomProtocol.MAX_NAME, n.length)
        assertTrue(n.endsWith(".pdf"))
    }

    @Test
    fun `unique names get a counter before the extension`() {
        assertEquals("a.txt", RoomProtocol.uniqueName("a.txt", emptySet()))
        assertEquals("a (2).txt", RoomProtocol.uniqueName("a.txt", setOf("a.txt")))
        assertEquals("a (3).txt", RoomProtocol.uniqueName("a.txt", setOf("a.txt", "a (2).txt")))
        assertNotEquals("noext", RoomProtocol.uniqueName("noext", setOf("noext")))
    }
}
