package com.ping.app.auth

import com.ping.app.auth.GestureFingerprint.Hand
import com.ping.app.auth.GestureFingerprint.Motion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GestureLockTest {

    private val peace = GestureFingerprint(fingerMask = 0b00110, angleBucket = 0, gaps = 1)

    /** Frame (1-based) the lock fires on and the code it locks, holding [peace] with the side settling at [settleAt]. */
    private fun hold(settleAt: Int?, hand: Hand = Hand.RIGHT): Pair<Int, String> {
        val lock = GestureLock()
        for (frame in 1..40) {
            val fp = peace.copy(hand = if (settleAt != null && frame >= settleAt) hand else null)
            lock.update(fp, null, still = true)?.let { return frame to it.code }
        }
        error("never locked")
    }

    @Test
    fun `a still pose locks after the commit frames`() {
        assertEquals(GestureLock.COMMIT_FRAMES to peace.code, hold(settleAt = null))
    }

    @Test
    fun `the hand side settling mid-hold does not restart the count`() {
        // Handedness settles on frame 5 with agreeing clues; a restart would lock on frame 10.
        assertEquals(GestureLock.COMMIT_FRAMES to peace.copy(hand = Hand.RIGHT).code, hold(settleAt = 5))
    }

    @Test
    fun `phones settling the side on different frames lock the same code`() {
        val codes = (1..GestureLock.COMMIT_FRAMES).map { hold(settleAt = it).second }.toSet()
        assertEquals(setOf(peace.copy(hand = Hand.RIGHT).code), codes)
    }

    @Test
    fun `real handedness clues lock with the hand on the commit frame`() {
        val side = Handedness()
        val lock = GestureLock()
        var locked: Pair<Int, String>? = null
        for (frame in 1..20) {
            val fp = peace.copy(hand = side.update(true, true))
            lock.update(fp, null, still = true)?.let { locked = frame to it.code; break }
        }
        assertEquals(GestureLock.COMMIT_FRAMES to peace.copy(hand = Hand.RIGHT).code, locked)
    }

    @Test
    fun `a hand flipping sides restarts the count`() {
        val lock = GestureLock()
        repeat(4) { assertNull(lock.update(peace.copy(hand = Hand.RIGHT), null, still = true)) }
        repeat(GestureLock.COMMIT_FRAMES - 1) { assertNull(lock.update(peace.copy(hand = Hand.LEFT), null, still = true)) }
        assertEquals(peace.copy(hand = Hand.LEFT), lock.update(peace.copy(hand = Hand.LEFT), null, still = true))
    }

    @Test
    fun `a changed pose or a moving hand does not lock`() {
        val lock = GestureLock()
        repeat(5) { lock.update(peace, null, still = true) }
        assertNull(lock.update(peace.copy(angleBucket = 1), null, still = true))
        repeat(10) { assertNull(lock.update(peace, null, still = false)) }
    }

    @Test
    fun `a motion locks on the finger mask once both settle`() {
        val lock = GestureLock()
        val results = (1..10).map { lock.update(peace.copy(angleBucket = it % 4), Motion.WAVE, still = false) }
        assertEquals(List(9) { null } + peace.withMotion(Motion.WAVE), results)
    }

    @Test
    fun `clear starts a fresh capture`() {
        val lock = GestureLock()
        repeat(5) { lock.update(peace, null, still = true) }
        lock.clear()
        assertEquals(0f, lock.stability)
        assertNull(lock.update(peace, null, still = true))
    }
}
