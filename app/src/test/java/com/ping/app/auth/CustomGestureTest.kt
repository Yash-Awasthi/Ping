package com.ping.app.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CustomGestureTest {

    /** A fist with the given fingers straight up; [dx] shifts and [s] scales it, as two people's hands would differ. */
    private fun hand(vararg fingers: Int, dx: Float = 0f, s: Float = 1f): FloatArray {
        val pts = Array(21) { floatArrayOf(0.5f, 0.7f) }
        pts[2] = floatArrayOf(0.40f, 0.60f); pts[3] = floatArrayOf(0.43f, 0.58f); pts[4] = floatArrayOf(0.47f, 0.56f)
        for ((f, x) in listOf(0.44f, 0.50f, 0.56f, 0.62f).withIndex()) {
            val mcp = 5 + f * 4
            pts[mcp] = floatArrayOf(x, if (f == 1) 0.48f else 0.50f)
            pts[mcp + 1] = floatArrayOf(x, 0.40f)
            val out = (f + 1) in fingers
            pts[mcp + 2] = floatArrayOf(x, if (out) 0.33f else 0.36f)
            pts[mcp + 3] = floatArrayOf(x, if (out) 0.28f else 0.53f)
        }
        return FloatArray(63).also { o ->
            for (i in 0 until 21) {
                o[i * 3] = 0.5f + (pts[i][0] - 0.5f) * s + dx
                o[i * 3 + 1] = 0.7f + (pts[i][1] - 0.7f) * s
            }
        }
    }

    private fun code(xyz: FloatArray) = GestureFingerprint.fromLandmarks(xyz)!!.code

    @Test
    fun `two people recording the same poses get the same code`() {
        val mine = CustomGesture("ours", listOf(code(hand(1, 2)), code(hand())))
        val theirs = CustomGesture("whatever they call it", listOf(code(hand(1, 2, dx = 0.1f, s = 1.3f)), code(hand(dx = -0.1f, s = 0.8f))))
        assertEquals(mine.code, theirs.code)
    }

    @Test
    fun `a saved gesture matches the same poses made live`() {
        val steps = listOf(code(hand(1, 2)), code(hand(1)))
        assertEquals(GestureFingerprint.sequenceCode(steps), CustomGesture("x", steps).code)
        assertEquals(code(hand(1)), CustomGesture("one", listOf(code(hand(1)))).code)
    }

    @Test
    fun `different poses or order give different codes`() {
        val a = code(hand(1, 2)); val b = code(hand())
        assertTrue(CustomGesture("x", listOf(a, b)).code != CustomGesture("x", listOf(b, a)).code)
        assertTrue(CustomGesture("x", listOf(a)).code != CustomGesture("x", listOf(b)).code)
    }

    @Test
    fun `saved gestures survive a round trip and bad data loads as none`() {
        val list = listOf(CustomGesture("ours", listOf("F6A0", "F0A0"), listOf("✌️", "✊")), CustomGesture("solo", listOf("F31~SR")))
        assertEquals(list, CustomGesture.decode(CustomGesture.encode(list)))
        assertEquals(emptyList<CustomGesture>(), CustomGesture.decode("not json"))
        assertEquals(emptyList<CustomGesture>(), CustomGesture.decode(null))
    }
}
