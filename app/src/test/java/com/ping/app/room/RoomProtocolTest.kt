package com.ping.app.room

import com.ping.app.room.RoomProtocol.FileMeta
import com.ping.app.room.RoomProtocol.GuestShare
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    fun `a manifest with missing fields drops those files instead of crashing later`() {
        val json = """{"files":[{"id":"h:1","name":"a","size":1},{"id":"h:2","name":"b","size":1,"ownerKey":"h","ownerName":"Hana"}],"members":[null,"Hana"]}"""
        val m = RoomProtocol.decodeManifest(json)!!
        assertEquals(listOf("h:2"), m.files.map { it.id })
        assertEquals(listOf("Hana"), m.members)
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
    fun `folder paths stay relative and bounded`() {
        assertEquals("Photos/2024", RoomProtocol.safePath("Photos/2024"))
        listOf(
            "../etc", "Photos/../../x", "./a", "/abs/dir", "\\\\server\\share", "C:/Windows", "C:", "a\\b",
            "a//b", "a/", ".hidden/x", "a/b\u0000c", "a/b.", "x/".repeat(RoomProtocol.MAX_DEPTH) + "x",
            "a".repeat(RoomProtocol.MAX_PATH + 1), null,
        ).forEach { assertEquals("", RoomProtocol.safePath(it), "path $it") }
        val deep = (1..RoomProtocol.MAX_DEPTH).joinToString("/") { "d$it" }
        assertEquals(deep, RoomProtocol.safePath(deep))
    }

    @Test
    fun `folder paths are built from clean names and stop at the depth bound`() {
        assertEquals("Photos", RoomProtocol.childPath("", "Photos"))
        assertEquals("Photos/trip", RoomProtocol.childPath("Photos", "../trip"))
        val deep = (1..RoomProtocol.MAX_DEPTH).joinToString("/") { "d$it" }
        assertEquals(deep, RoomProtocol.childPath(deep, "one-too-many"))
    }

    @Test
    fun `decoded listings flatten malicious paths`() {
        val metas = """[{"id":"a","name":"x","size":1,"path":"../../data"},{"id":"b","name":"y","size":1,"path":"Docs/work"}]"""
        assertEquals(listOf("", "Docs/work"), RoomProtocol.decodeMetas(metas).map { it.path })
        val manifest = """{"files":[{"id":"h:1","name":"a","size":1,"ownerKey":"h","ownerName":"H","path":"/sdcard/x"}],"members":[]}"""
        assertEquals("", RoomProtocol.decodeManifest(manifest)!!.files.single().path)
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

    @Test
    fun `requests carry an offset only when resuming and old bare ids still parse`() {
        assertEquals("host:f1", RoomProtocol.request("host:f1"))
        assertEquals("host:f1" to 0L, RoomProtocol.parseRequest("host:f1"))
        assertEquals("host:f1" to 42L, RoomProtocol.parseRequest(RoomProtocol.request("host:f1", 42)))
        assertNull(RoomProtocol.parseRequest("host:f1 -3"))
        assertNull(RoomProtocol.parseRequest("host:f1 x"))
        assertNull(RoomProtocol.parseRequest(" 5"))
        val stream = ByteArrayInputStream(RoomProtocol.header("host:f1", 9))
        assertEquals("host:f1" to 9L, RoomProtocol.parseRequest(RoomProtocol.readHeader(stream)!!))
    }

    @Test
    fun `a resumed body skips the prefix but its checksum covers the whole file`() {
        val body = "hello resumed room".toByteArray()
        val sent = RoomProtocol.withDigest(ByteArrayInputStream(body), 6).readBytes()
        assertEquals("resumed room", String(sent, 0, body.size - 6))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body)
        assertTrue(RoomProtocol.trailerMatches(ByteArrayInputStream(sent, body.size - 6, 32), digest))
    }

    @Test
    fun `ids with spaces or that are too long are dropped`() {
        val json = """[{"id":"a b","name":"x","size":1},{"id":"${"z".repeat(41)}","name":"y","size":1},{"id":"ok","name":"z","size":1}]"""
        assertEquals(listOf("ok"), RoomProtocol.decodeMetas(json).map { it.id })
    }

    @Test
    fun `a body carries its checksum and a changed byte fails it`() {
        val body = "hello room".toByteArray()
        val sent = RoomProtocol.withDigest(ByteArrayInputStream(body)).readBytes()
        assertEquals(body.size + 32, sent.size)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body)
        assertTrue(RoomProtocol.trailerMatches(ByteArrayInputStream(sent, body.size, 32), digest))
        sent[body.size] = (sent[body.size] + 1).toByte()
        assertFalse(RoomProtocol.trailerMatches(ByteArrayInputStream(sent, body.size, 32), digest))
        assertFalse(RoomProtocol.trailerMatches(ByteArrayInputStream(sent, body.size, 10), digest))
        assertFalse(RoomProtocol.trailerMatches(ByteArrayInputStream(sent + 0, body.size, 33), digest))
    }
}
