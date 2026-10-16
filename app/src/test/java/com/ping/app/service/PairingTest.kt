package com.ping.app.service

import com.ping.app.utils.CryptoUtils
import com.ping.app.utils.Pake
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingTest {

    @Test
    fun `same gesture on two phones matches even with different nonces`() {
        val a = Pairing.advertisement("F3A0", "aaaa1111")
        val b = Pairing.advertisement("F3A0", "bbbb2222")
        assertTrue(Pairing.matches("F3A0", b))
        assertTrue(Pairing.matches("F3A0", a))
    }

    @Test
    fun `a gesture in another bucket never matches`() {
        val other = (0 until 64).map { "F${it}A1" }.first { Pairing.token(it) != Pairing.token("F3A0") }
        assertFalse(Pairing.matches("F3A0", Pairing.advertisement(other, "x")))
    }

    @Test
    fun `advertisement never contains the raw code`() {
        assertFalse(Pairing.advertisement("F31A0", "n").contains("F31A0"))
    }

    @Test
    fun `the token leaks at most four bits of the code`() {
        val tokens = (0 until 32).flatMap { m -> (0 until 4).map { "F${m}A$it" } }.map(Pairing::token)
        assertEquals(16, tokens.toSet().size)
        assertTrue(tokens.all { it.length == 1 })
    }

    @Test
    fun `exactly one side initiates`() {
        val a = Pairing.advertisement("F3A0", "aaaa1111")
        val b = Pairing.advertisement("F3A0", "bbbb2222")
        assertTrue(Pairing.shouldInitiate(a, b) xor Pairing.shouldInitiate(b, a))
    }

    @Test
    fun `garbage advertisement is rejected`() {
        assertFalse(Pairing.matches("F3A0", "no-delimiter"))
        assertFalse(Pairing.matches("F3A0", ""))
    }

    @Test
    fun `phones seen before the lock are matched and counted after it`() {
        val mine = "F3A0"
        val others = (0 until 64).map { "F${it}A2" }.filter { Pairing.token(it) != Pairing.token(mine) }
        val seen = mapOf(
            "e1" to Pairing.advertisement(others[0], "n1"),
            "e2" to Pairing.advertisement(mine, "n2"),
            "e3" to Pairing.advertisement(others[1], "n3"),
            "e4" to Pairing.advertisement(mine, "n4"),
        )
        assertEquals("e2", Pairing.firstMatch(mine, seen)?.key)
        assertEquals("e4", Pairing.firstMatch(mine, seen, tried = setOf("e2"))?.key)
        assertEquals(null, Pairing.firstMatch(mine, seen, tried = setOf("e2", "e4")))
        assertEquals(2, Pairing.othersNearby(mine, seen))
    }

    @Test
    fun `adverts carry the protocol version and older phones only count as outdated`() {
        val mine = "F3A0"
        assertTrue(Pairing.advertisement(mine, "n").startsWith("2:"))
        val legacy = "${Pairing.token(mine)}|oldnonce"
        assertFalse(Pairing.matches(mine, legacy))
        val seen = mapOf("e1" to legacy, "e2" to Pairing.advertisement(mine, "n2"))
        assertEquals(1, Pairing.legacyNearby(seen))
        assertEquals(0, Pairing.othersNearby(mine, seen))
    }

    @Test
    fun `an oversized remote name never matches`() {
        val long = Pairing.advertisement("F3A0", "x".repeat(Pairing.MAX_NAME_BYTES))
        assertFalse(Pairing.matches("F3A0", long))
        assertTrue(Pairing.matches("F3A0", Pairing.advertisement("F3A0", "x".repeat(Pairing.MAX_NAME_BYTES - 4))))
    }

    private val ctx = Pake.context("a|n1", "a|n2", byteArrayOf(7))

    /** Both sides send share, then tag and sealed card together; returns B after it got A's card first. */
    private fun oneRoundTrip(gestureA: String, gestureB: String, ctxB: ByteArray = ctx): Pair<Pairing.Attempt, ByteArray> {
        val a = Pairing.Attempt(Pake.generator(gestureA))
        val b = Pairing.Attempt(Pake.generator(gestureB))
        val ka = a.onShare(b.pake.share, ctx)!!
        b.onShare(a.pake.share, ctxB)!!
        b.onCard(CryptoUtils.encrypt(ka.session, "card-a".toByteArray()))
        assertNull(b.openCard(), "a card is never opened before the tag checks out")
        b.onConfirm(ka.myTag)
        return b to ka.myTag
    }

    @Test
    fun `a card that beats its tag is held and opened once the tag checks out`() {
        val (b, _) = oneRoundTrip("F3A0", "F3A0")
        assertTrue(b.verified)
        assertArrayEquals("card-a".toByteArray(), b.openCard())
        assertNull(b.openCard(), "the card is handed out once")
    }

    @Test
    fun `a different gesture never opens the early card`() {
        val (b, _) = oneRoundTrip("F3A0", "F3A1")
        assertFalse(b.verified)
        assertNull(b.openCard())
    }

    @Test
    fun `a relay onto another link never opens the early card`() {
        val (b, _) = oneRoundTrip("F3A0", "F3A0", ctxB = Pake.context("a|n1", "a|n2", byteArrayOf(8)))
        assertFalse(b.verified)
        assertNull(b.openCard())
    }
}
