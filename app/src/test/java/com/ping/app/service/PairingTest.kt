package com.ping.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    @Test
    fun `same gesture on two phones matches even with different nonces`() {
        val a = Pairing.advertisement("F3A0", "aaaa1111")
        val b = Pairing.advertisement("F3A0", "bbbb2222")
        assertTrue(Pairing.matches("F3A0", b))
        assertTrue(Pairing.matches("F3A0", a))
    }

    @Test
    fun `different gestures never match`() {
        assertFalse(Pairing.matches("F3A0", Pairing.advertisement("F31A1", "x")))
    }

    @Test
    fun `advertisement never contains the raw code`() {
        assertFalse(Pairing.advertisement("F31A0", "n").contains("F31A0"))
    }

    @Test
    fun `every code has a distinct token`() {
        val tokens = (0 until 32).flatMap { m -> (0 until 4).map { "F${m}A$it" } }.map(Pairing::token)
        assertEquals(128, tokens.toSet().size)
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
}
