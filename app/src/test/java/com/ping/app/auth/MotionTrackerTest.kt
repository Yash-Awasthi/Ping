package com.ping.app.auth

import com.ping.app.auth.GestureFingerprint.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun `a straight sweep reads as a swipe that way`() {
        assertEquals(Motion.SWIPE_RIGHT, tracker(30) { i -> 0.3 + 0.4 * i / 29 to 0.5 }.classify())
        assertEquals(Motion.SWIPE_LEFT, tracker(30) { i -> 0.7 - 0.4 * i / 29 to 0.5 }.classify())
        assertEquals(Motion.SWIPE_DOWN, tracker(30) { i -> 0.5 to 0.3 + 0.4 * i / 29 }.classify())
        assertEquals(Motion.SWIPE_UP, tracker(30) { i -> 0.5 to 0.7 - 0.4 * i / 29 }.classify())
    }

    @Test
    fun `a quick swipe between still holds still reads as a swipe`() {
        val t = tracker(30) { i ->
            val x = when { i < 10 -> 0.3; i < 16 -> 0.3 + 0.4 * (i - 9) / 6; else -> 0.7 }
            x + 0.004 * sin(i * 1.3) to 0.5 + 0.004 * cos(i * 1.9)
        }
        assertEquals(Motion.SWIPE_RIGHT, t.classify())
    }

    @Test
    fun `a sweep there and back is not a swipe`() {
        val t = tracker(30) { i -> (if (i < 15) 0.3 + 0.4 * i / 14 else 0.7 - 0.3 * (i - 14) / 15) to 0.5 }
        assertNull(t.classify())
    }

    @Test
    fun `a diagonal or short sweep is not a swipe`() {
        assertNull(tracker(30) { i -> 0.3 + 0.4 * i / 29 to 0.3 + 0.4 * i / 29 }.classify())
        assertNull(tracker(30) { i -> 0.45 + 0.1 * i / 29 to 0.5 }.classify())
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
}
