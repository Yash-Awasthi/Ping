package com.ping.app.service

import org.junit.jupiter.api.Assertions.assertEquals
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
}
