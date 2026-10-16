package com.ping.app.auth

import com.ping.app.auth.GestureFingerprint.Hand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HandednessTest {

    private fun Handedness.feed(n: Int, detector: Boolean?, shape: Boolean?): Hand? {
        var last: Hand? = null
        repeat(n) { last = update(detector, shape) }
        return last
    }

    @Test
    fun `agreeing clues settle within a few frames`() {
        val h = Handedness()
        assertNull(h.update(true, true))
        assertEquals(Hand.RIGHT, h.feed(5, true, true))
    }

    @Test
    fun `the detector alone settles when the hand is too flat to read`() {
        assertEquals(Hand.LEFT, Handedness().feed(8, false, null))
    }

    @Test
    fun `a flickering detector does not flip a settled hand`() {
        val h = Handedness()
        h.feed(10, true, true)
        for (i in 0 until 40) assertEquals(Hand.RIGHT, h.update(i % 3 != 0, null))
        assertEquals(Hand.RIGHT, h.feed(10, false, true))
    }

    @Test
    fun `a steady run the other way does flip it`() {
        val h = Handedness()
        h.feed(10, true, true)
        assertEquals(Hand.LEFT, h.feed(10, false, false))
    }

    @Test
    fun `clues that keep disagreeing never settle`() {
        assertNull(Handedness().feed(30, true, false))
    }

    @Test
    fun `clearing forgets the hand`() {
        val h = Handedness()
        h.feed(10, true, true)
        h.clear()
        assertNull(h.hand)
        assertNull(h.update(null, null))
    }
}
