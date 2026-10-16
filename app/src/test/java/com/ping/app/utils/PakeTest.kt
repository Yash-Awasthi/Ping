package com.ping.app.utils

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

class PakeTest {

    private val ctx = Pake.context("a|n1", "a|n2", byteArrayOf(1, 2, 3))

    private fun run(pwA: String, pwB: String, ctxA: ByteArray = ctx, ctxB: ByteArray = ctx): Pair<Pake.Keys, Pake.Keys> {
        val a = Pake.Session(Pake.generator(pwA))
        val b = Pake.Session(Pake.generator(pwB))
        return a.finish(b.share, ctxA)!! to b.finish(a.share, ctxB)!!
    }

    @Test
    fun `same gesture gives both phones the same key and accepted tags`() {
        val (a, b) = run("F31A0", "F31A0")
        assertArrayEquals(a.session.encoded, b.session.encoded)
        assertEquals(32, a.session.encoded.size)
        assertTrue(a.verify(b.myTag))
        assertTrue(b.verify(a.myTag))
    }

    @Test
    fun `a different gesture fails confirmation on both sides`() {
        val (a, b) = run("F31A0", "F31A1")
        assertFalse(a.session.encoded.contentEquals(b.session.encoded))
        assertFalse(a.verify(b.myTag))
        assertFalse(b.verify(a.myTag))
    }

    @Test
    fun `a relay with a different link token on each leg fails confirmation`() {
        val (a, b) = run("F31A0", "F31A0", ctxB = Pake.context("a|n1", "a|n2", byteArrayOf(9)))
        assertFalse(a.verify(b.myTag))
        assertFalse(b.verify(a.myTag))
        assertFalse(a.session.encoded.contentEquals(b.session.encoded))
    }

    @Test
    fun `a relay that swaps in other advertisements fails confirmation`() {
        val (a, b) = run("F31A0", "F31A0", ctxB = Pake.context("a|n1", "a|n3", byteArrayOf(1, 2, 3)))
        assertFalse(a.verify(b.myTag))
        assertFalse(b.verify(a.myTag))
    }

    @Test
    fun `a reflected tag is rejected`() {
        val (a, _) = run("F31A0", "F31A0")
        assertFalse(a.verify(a.myTag))
    }

    @Test
    fun `context does not depend on which phone builds it`() {
        assertArrayEquals(Pake.context("x", "y", null), Pake.context("y", "x", null))
    }

    @Test
    fun `fresh scalars give fresh shares and keys`() {
        val (a1, _) = run("F1A0", "F1A0")
        val (a2, _) = run("F1A0", "F1A0")
        assertFalse(a1.session.encoded.contentEquals(a2.session.encoded))
    }

    @Test
    fun `malformed or echoed shares are refused`() {
        val g = Pake.generator("F1A0")
        val s = Pake.Session(g)
        assertNull(s.finish(ByteArray(31), ctx))
        assertNull(s.finish(s.share, ctx))
        assertNull(s.finish(ByteArray(32) { 0xFF.toByte() }, ctx))
        assertNotNull(s.finish(Pake.Session(g).share, ctx))
    }

    @Test
    fun `the generator built at lock is a deterministic curve point per gesture`() {
        val g = Pake.generator("F31A0")
        assertEquals(g, Pake.generator("F31A0"))
        assertNotNull(Pake.lift(g.affineX))
        assertFalse(g == Pake.generator("F31A1"))
    }

    @Test
    fun `sessions from one precomputed generator still get fresh shares`() {
        val g = Pake.generator("F31A0")
        assertFalse(Pake.Session(g).share.contentEquals(Pake.Session(g).share))
    }

    @Test
    fun `lift rejects an x with no point`() {
        val misses = (1..20).count { Pake.lift(BigInteger.valueOf(it.toLong())) == null }
        assertTrue(misses in 1..19)
    }
}
