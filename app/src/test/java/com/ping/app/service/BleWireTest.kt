package com.ping.app.service

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.GeneralSecurityException
import java.util.concurrent.CompletableFuture

class BleWireTest {

    @Test
    fun `advert round-trips psm and name`() {
        val name = Pairing.advertisement("F3A0", "aaaa1111")
        assertEquals(0x0081 to name, BleWire.parseAdvert(BleWire.packAdvert(0x0081, name)))
        assertEquals(0xFFFF to "é", BleWire.parseAdvert(BleWire.packAdvert(0xFFFF, "é")))
    }

    @Test
    fun `foreign service data is not an advert`() {
        assertNull(BleWire.parseAdvert(null))
        assertNull(BleWire.parseAdvert(byteArrayOf(0, 0x7F)))
        assertNull(BleWire.parseAdvert(byteArrayOf(0, 0, 'x'.code.toByte())))
    }

    @Test
    fun `a pairing advert needs extended advertising`() {
        assertTrue(BleWire.fitsLegacy(BleWire.packAdvert(0x81, "12345678")))
        assertFalse(BleWire.fitsLegacy(BleWire.packAdvert(0x81, "123456789")))
        assertFalse(BleWire.fitsLegacy(BleWire.packAdvert(0x81, Pairing.advertisement("F3A0", "aaaa1111"))))
    }

    @Test
    fun `service uuid is stable per service id`() {
        assertEquals(BleWire.serviceUuid("com.ping.app.swap"), BleWire.serviceUuid("com.ping.app.swap"))
        assertNotEquals(BleWire.serviceUuid("com.ping.app.swap"), BleWire.serviceUuid("com.ping.app.room"))
    }

    @Test
    fun `blobs round-trip and absurd lengths are refused`() {
        val buf = ByteArrayOutputStream()
        BleWire.writeBlob(DataOutputStream(buf), byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), BleWire.readBlob(DataInputStream(ByteArrayInputStream(buf.toByteArray()))))

        for (len in listOf(0, -1, Int.MAX_VALUE)) {
            val bad = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(len) }.toByteArray()
            assertThrows(IOException::class.java) { BleWire.readBlob(DataInputStream(ByteArrayInputStream(bad))) }
        }
    }

    @Test
    fun `chunk and end frames carry the stream id`() {
        val chunk = BleWire.chunk(7, byteArrayOf(9, 8, 7), 2)
        assertArrayEquals(byteArrayOf(BleWire.T_CHUNK, 0, 0, 0, 7, 9, 8), chunk)
        assertArrayEquals(byteArrayOf(BleWire.T_END, 0, 0, 0, 7), BleWire.end(7))
    }

    private fun pair(): Pair<BleWire.Session, BleWire.Session> {
        val aOut = PipedOutputStream()
        val bOut = PipedOutputStream()
        val aIn = PipedInputStream(bOut, 4096)
        val bIn = PipedInputStream(aOut, 4096)
        val responder = CompletableFuture.supplyAsync {
            BleWire.handshake(DataInputStream(bIn), DataOutputStream(bOut), initiator = false)
        }
        val initiator = BleWire.handshake(DataInputStream(aIn), DataOutputStream(aOut), initiator = true)
        return initiator to responder.get()
    }

    @Test
    fun `both sides derive the same token and can talk both ways`() {
        val (a, b) = pair()
        assertArrayEquals(a.token, b.token)
        assertEquals(32, a.token.size)
        assertArrayEquals("hi".toByteArray(), b.cipher.open(a.cipher.seal("hi".toByteArray())))
        assertArrayEquals("yo".toByteArray(), a.cipher.open(b.cipher.seal("yo".toByteArray())))
        assertArrayEquals("again".toByteArray(), b.cipher.open(a.cipher.seal("again".toByteArray())))
    }

    @Test
    fun `each connection gets a fresh token`() {
        assertFalse(pair().first.token.contentEquals(pair().first.token))
    }

    @Test
    fun `a frame cannot be read back by its own sender`() {
        val (a, _) = pair()
        assertThrows(GeneralSecurityException::class.java) { a.cipher.open(a.cipher.seal(byteArrayOf(1))) }
    }

    @Test
    fun `replayed, reordered and tampered frames are rejected`() {
        val (a, b) = pair()
        val first = a.cipher.seal(byteArrayOf(1))
        val second = a.cipher.seal(byteArrayOf(2))
        assertThrows(GeneralSecurityException::class.java) { b.cipher.open(second) }
        b.cipher.open(first)
        assertThrows(GeneralSecurityException::class.java) { b.cipher.open(first) }

        val third = a.cipher.seal(byteArrayOf(3))
        b.cipher.open(second)
        third[0] = (third[0].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { b.cipher.open(third) }
    }
}
