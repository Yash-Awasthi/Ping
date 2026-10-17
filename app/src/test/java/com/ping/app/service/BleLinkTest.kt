package com.ping.app.service

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class BleLinkTest {

    private class Recorder : BleLink.Host {
        val events = LinkedBlockingQueue<String>()
        val streams = LinkedBlockingQueue<InputStream>()
        /** How many new streams to refuse before taking the rest. */
        @Volatile var refuse = 0

        override fun initiated(link: BleLink, token: ByteArray) { events += "initiated ${link.remoteName}" }
        override fun connected(link: BleLink) { events += "connected" }
        override fun bytes(link: BleLink, body: ByteArray) { events += "bytes ${String(body)}" }
        override fun stream(link: BleLink): ((InputStream) -> Unit)? =
            if (refuse > 0) { refuse--; null } else { s -> streams += s }
        override fun closed(link: BleLink, notify: Boolean) { events += "closed $notify" }

        fun next(): String? = events.poll(5, TimeUnit.SECONDS)
        fun nextStream(): InputStream = streams.poll(5, TimeUnit.SECONDS)!!
    }

    private class Ends(val a: BleLink, val b: BleLink, val ha: Recorder, val hb: Recorder)

    private fun thread(block: () -> Unit) = Thread(block).apply { isDaemon = true; start() }

    /** a initiates as "alice" to b; both have seen the handshake and hello. */
    private fun pair(acceptMs: Long = 30_000, early: (BleLink) -> Unit = {}): Ends {
        val aOut = PipedOutputStream()
        val bOut = PipedOutputStream()
        val aIn = PipedInputStream(bOut, 1 shl 20)
        val bIn = PipedInputStream(aOut, 1 shl 20)
        val ha = Recorder()
        val hb = Recorder()
        val a = BleLink("a", aIn, aOut, initiator = true, remoteName = "bob", host = ha)
        val b = BleLink("b", bIn, bOut, initiator = false, remoteName = "", host = hb, acceptMs = acceptMs)
        early(b)
        thread { b.run(null) }
        thread { a.run("alice") }
        assertEquals("initiated bob", ha.next())
        assertEquals("initiated alice", hb.next())
        return Ends(a, b, ha, hb)
    }

    private fun connected(): Ends = pair().apply {
        a.accept()
        b.accept()
        assertEquals("connected", ha.next())
        assertEquals("connected", hb.next())
    }

    @Test
    fun `accept on both sides connects exactly once`() {
        val e = pair()
        e.a.accept()
        e.a.accept()
        e.b.accept()
        e.b.accept()
        assertEquals("connected", e.ha.next())
        assertEquals("connected", e.hb.next())
        e.a.sendBytes("x".toByteArray())
        e.b.sendBytes("y".toByteArray())
        assertEquals("bytes x", e.hb.next())
        assertEquals("bytes y", e.ha.next())
    }

    @Test
    fun `a stream nobody takes is dropped`() {
        val e = connected()
        e.hb.refuse = 1
        e.a.sendStream(ByteArrayInputStream(ByteArray(100_000) { 9 }))
        e.a.sendStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), e.hb.nextStream().readBytes())
        assertTrue(e.hb.streams.isEmpty())
    }

    @Test
    fun `two interleaved streams reassemble byte for byte`() {
        val e = connected()
        val one = Random(1).nextBytes(700_000)
        val two = Random(2).nextBytes(500_000)
        thread { e.a.sendStream(ByteArrayInputStream(one)) }
        thread { e.a.sendStream(ByteArrayInputStream(two)) }
        val got = List(2) { e.hb.nextStream() }.map { s -> CompletableFuture.supplyAsync { s.readBytes() } }
            .map { it.get(30, TimeUnit.SECONDS) }
            .sortedByDescending { it.size }
        assertArrayEquals(one, got[0])
        assertArrayEquals(two, got[1])
    }

    @Test
    fun `a sender failure reads as an IOException, not a clean end`() {
        val e = connected()
        val failing = object : InputStream() {
            var left = 100_000
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left == 0) throw IOException("disk gone")
                return minOf(len, left).also { left -= it }
            }
        }
        thread { e.a.sendStream(failing) }
        val source = e.hb.nextStream()
        val got = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { source.copyTo(got) }
        assertEquals(100_000, got.size())
        e.a.sendBytes("still up".toByteArray())
        assertEquals("bytes still up", e.hb.next())
    }

    @Test
    fun `a dropped link cuts its open streams with an IOException`() {
        val e = connected()
        val release = CountDownLatch(1)
        val stalled = object : InputStream() {
            var sent = false
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent) release.await()
                sent = true
                return 10
            }
        }
        thread { e.a.sendStream(stalled) }
        val source = e.hb.nextStream()
        e.b.close(notify = false)
        assertThrows(IOException::class.java) { source.readBytes() }
        release.countDown()
    }

    @Test
    fun `a sender holds its fifth stream until the receiver has room`() {
        val e = connected()
        val release = CountDownLatch(1)
        repeat(5) {
            val stalled = object : InputStream() {
                var sent = false
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (sent) { release.await(); return -1 }
                    sent = true
                    return 10
                }
            }
            thread { e.a.sendStream(stalled) }
        }
        val sources = List(4) { e.hb.nextStream() }
        assertNull(e.hb.streams.poll(500, TimeUnit.MILLISECONDS))
        release.countDown()
        sources.forEach { assertEquals(10, it.readBytes().size) }
        assertEquals(10, e.hb.nextStream().readBytes().size)
    }

    @Test
    fun `an accept before the handshake is ignored`() {
        val e = pair(early = { it.accept() })
        e.a.accept()
        assertNull(e.ha.events.poll(500, TimeUnit.MILLISECONDS))
        e.b.accept()
        assertEquals("connected", e.ha.next())
    }

    @Test
    fun `a link nobody accepts is dropped after the accept timeout`() {
        val e = pair(acceptMs = 300)
        e.a.accept()
        assertEquals("closed true", e.hb.next())
        assertEquals("closed true", e.ha.next())
    }

    @Test
    fun `a connected link outlives the accept timeout`() {
        val e = pair(acceptMs = 300)
        e.a.accept()
        e.b.accept()
        assertEquals("connected", e.hb.next())
        Thread.sleep(600)
        e.a.sendBytes("x".toByteArray())
        assertEquals("bytes x", e.hb.next())
    }

    @Test
    fun `closing one end notifies the other`() {
        val e = connected()
        e.a.close(notify = false)
        assertEquals("closed false", e.ha.next())
        assertEquals("closed true", e.hb.next())
    }

    @Test
    fun `a rejected link closes both ends`() {
        val e = pair()
        e.b.reject()
        assertEquals("closed true", e.hb.next())
        assertEquals("closed true", e.ha.next())
    }

    @Test
    fun `a silent peer is dropped after the handshake timeout`() {
        val h = Recorder()
        val link = BleLink("s", PipedInputStream(PipedOutputStream()), ByteArrayOutputStream(), false, "", h, handshakeMs = 200)
        thread { link.run(null) }
        assertEquals("closed true", h.next())
    }

    @Test
    fun `an oversized hello name ends the link before it is offered`() {
        val aOut = PipedOutputStream()
        val bOut = PipedOutputStream()
        val ha = Recorder()
        val hb = Recorder()
        val a = BleLink("a", PipedInputStream(bOut, 4096), aOut, true, "bob", ha)
        val b = BleLink("b", PipedInputStream(aOut, 4096), bOut, false, "", hb)
        thread { b.run(null) }
        thread { a.run("x".repeat(BleWire.MAX_NAME_BYTES + 1)) }
        assertEquals("closed true", hb.next())
    }
}
