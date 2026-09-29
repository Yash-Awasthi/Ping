package com.ping.app.auth

import com.ping.app.auth.GestureFingerprint.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTrackerTest {

    private fun tracker(n: Int, point: (Int) -> Pair<Double, Double>) =
        MotionTracker().also { t -> for (i in 0 until n) point(i).let { t.add(it.first, it.second) } }

    @Test
    fun `a full turn one way reads as a clockwise circle`() {
        val t = tracker(30) { i -> 0.5 + 0.08 * cos(2 * PI * i / 28) to 0.5 + 0.08 * sin(2 * PI * i / 28) }
        assertEquals(Motion.CIRCLE_CW, t.classify())
    }

    @Test
    fun `the opposite turn reads as counter-clockwise`() {
        val t = tracker(30) { i -> 0.5 + 0.08 * cos(-2 * PI * i / 28) to 0.5 + 0.08 * sin(-2 * PI * i / 28) }
        assertEquals(Motion.CIRCLE_CCW, t.classify())
    }

    @Test
    fun `an oval still counts as a circle`() {
        val t = tracker(30) { i -> 0.5 + 0.10 * cos(2 * PI * i / 28) to 0.5 + 0.06 * sin(2 * PI * i / 28) }
        assertEquals(Motion.CIRCLE_CW, t.classify())
    }

    @Test
    fun `side to side reads as a wave`() {
        val t = tracker(30) { i -> 0.5 + 0.10 * sin(2 * PI * 2.5 * i / 29) to 0.5 }
        assertEquals(Motion.WAVE, t.classify())
    }

    @Test
    fun `a straight sweep is neither`() {
        val t = tracker(30) { i -> 0.3 + 0.4 * i / 29 to 0.5 }
        assertNull(t.classify())
    }

    @Test
    fun `a hand holding still is neither and is still`() {
        val t = tracker(30) { i -> 0.5 + 0.004 * sin(i.toDouble()) to 0.5 + 0.004 * cos(i.toDouble()) }
        assertNull(t.classify())
        assertTrue(t.isStill(10))
    }

    @Test
    fun `a tiny wobble is not a circle`() {
        val t = tracker(30) { i -> 0.5 + 0.012 * cos(2 * PI * i / 28) to 0.5 + 0.012 * sin(2 * PI * i / 28) }
        assertNull(t.classify())
    }

    @Test
    fun `hand tremor is neither a circle nor a wave`() {
        val t = tracker(30) { i -> 0.5 + 0.03 * sin(i * 1.7) to 0.5 + 0.03 * cos(i * 2.3) }
        assertNull(t.classify())
    }

    @Test
    fun `a moving hand is not still`() {
        val t = tracker(30) { i -> 0.3 + 0.4 * i / 29 to 0.5 }
        assertFalse(t.isStill(10))
    }

    @Test
    fun `too few samples give no verdict`() {
        val t = tracker(8) { i -> 0.5 + 0.08 * cos(i.toDouble()) to 0.5 + 0.08 * sin(i.toDouble()) }
        assertNull(t.classify())
        assertFalse(t.isStill(10))
    }

    @Test
    fun `clear forgets the trail`() {
        val t = tracker(30) { i -> 0.5 + 0.08 * cos(2 * PI * i / 28) to 0.5 + 0.08 * sin(2 * PI * i / 28) }
        t.clear()
        assertNull(t.classify())
    }
}
