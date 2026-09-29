package com.ping.app.auth

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture code is the pairing key, so each pose family is built from a small hand model
 * (wrist at the bottom, fingers pointing up, palm width 0.18) and checked for the code and
 * the emoji a person would expect.
 */
class GestureFingerprintTest {

    /** Fingers laid out along the vertical; each tip can be overridden to make gaps, pinches and crosses. */
    private class Hand {
        private val tips = intArrayOf(4, 8, 12, 16, 20)
        val pts = Array(21) { floatArrayOf(0.5f, 0.7f) }

        init {
            set(0, 0.50f, 0.70f)
            // Thumb chain, folded by default.
            set(1, 0.44f, 0.66f); set(2, 0.40f, 0.60f); set(3, 0.43f, 0.58f); set(4, 0.47f, 0.56f)
            // Knuckles.
            set(5, 0.44f, 0.50f); set(9, 0.50f, 0.48f); set(13, 0.56f, 0.50f); set(17, 0.62f, 0.52f)
            // Second joints and curled tips.
            val base = mapOf(6 to 0.44f, 10 to 0.50f, 14 to 0.56f, 18 to 0.62f)
            base.forEach { (i, x) -> set(i, x, 0.40f); set(i + 1, x, 0.36f); set(i + 2, x, 0.53f) }
        }

        fun set(i: Int, x: Float, y: Float): Hand { pts[i][0] = x; pts[i][1] = y; return this }

        fun thumbOut() = set(4, 0.30f, 0.48f).set(3, 0.35f, 0.54f)

        /** Extends finger [f] (1 index … 4 pinky) straight up from its second joint. */
        fun extend(f: Int, tipX: Float? = null): Hand {
            val tip = tips[f]
            return set(tip - 1, pts[tip - 2][0], 0.33f).set(tip, tipX ?: pts[tip - 2][0], 0.28f)
        }

        fun fingers(vararg f: Int): Hand { f.forEach { extend(it) }; return this }

        fun rotated(deg: Double): Hand {
            val a = Math.toRadians(deg)
            val h = Hand()
            for (i in 0 until 21) {
                val dx = pts[i][0] - 0.5f; val dy = pts[i][1] - 0.7f
                h.set(i, (0.5 + dx * cos(a) - dy * sin(a)).toFloat(), (0.7 + dx * sin(a) + dy * cos(a)).toFloat())
            }
            return h
        }

        fun xyz(): FloatArray = FloatArray(63).also { out ->
            for (i in 0 until 21) { out[i * 3] = pts[i][0]; out[i * 3 + 1] = pts[i][1] }
        }
    }

    private fun fp(h: Hand, right: Boolean? = null) = GestureFingerprint.fromLandmarks(h.xyz(), right)!!

    private val open = { Hand().thumbOut().fingers(1, 2, 3, 4) }

    @Test
    fun `open palm pointing up`() {
        assertEquals("F31A0", fp(open()).code)
        assertTrue(fp(open()).label.startsWith("✋"))
    }

    @Test
    fun `fist and its sideways forms`() {
        assertEquals("F0A0", fp(Hand()).code)
        assertEquals("F0A1", fp(Hand().rotated(90.0)).code)
        assertEquals("F0A3", fp(Hand().rotated(-90.0)).code)
        assertEquals("F0A2", fp(Hand().rotated(180.0)).code)
        assertTrue(fp(Hand().rotated(90.0)).label.startsWith("🤜"))
        assertTrue(fp(Hand().rotated(-90.0)).label.startsWith("🤛"))
    }

    @Test
    fun `one finger points in four directions`() {
        val labels = listOf(0.0 to "☝️", 90.0 to "👉", 180.0 to "👇", -90.0 to "👈")
        for ((deg, emoji) in labels) {
            assertTrue("$deg", fp(Hand().fingers(1).rotated(deg)).label.startsWith(emoji))
        }
    }

    @Test
    fun `thumb up and thumb down differ`() {
        val up = Hand().set(4, 0.35f, 0.41f).set(3, 0.37f, 0.50f)
        val down = Hand().set(4, 0.32f, 0.85f).set(3, 0.36f, 0.72f)
        assertEquals("F1A0", fp(up).code)
        assertEquals("F1A2", fp(down).code)
        assertTrue(fp(up).label.startsWith("👍"))
        assertTrue(fp(down).label.startsWith("👎"))
    }

    @Test
    fun `a fist with the thumb tucked or bent against it is not a thumbs up`() {
        assertEquals("F0A0", fp(Hand()).code)
        // Thumb resting bent along the side: far from the index knuckle but not straight.
        val bent = Hand().set(3, 0.28f, 0.56f).set(4, 0.30f, 0.44f)
        assertEquals(0, fp(bent).fingerMask)
    }

    @Test
    fun `hand side is reported once the votes agree`() {
        val left = fp(open()).copy(hand = GestureFingerprint.Hand.LEFT)
        val right = fp(open()).copy(hand = GestureFingerprint.Hand.RIGHT)
        assertEquals("F31A0HL", left.code)
        assertEquals("F31A0HR", right.code)
        assertTrue(left.label.contains("left hand"))
        assertEquals("F31~WAVEHL", left.withMotion(GestureFingerprint.Motion.WAVE).code)
    }

    @Test
    fun `peace, rock on, love you and call me`() {
        assertTrue(fp(Hand().fingers(1, 2)).label.startsWith("✌️"))
        assertTrue(fp(Hand().fingers(1, 4)).label.startsWith("🤘"))
        assertTrue(fp(Hand().thumbOut().fingers(1, 4)).label.startsWith("🤟"))
        assertTrue(fp(Hand().thumbOut().fingers(4)).label.startsWith("🤙"))
    }

    @Test
    fun `crossed fingers are told from peace`() {
        val crossed = Hand().extend(1, tipX = 0.52f).extend(2, tipX = 0.47f)
        assertEquals("F6A0X", fp(crossed).code)
        assertTrue(fp(crossed).label.startsWith("🤞"))
        assertFalse(fp(Hand().fingers(1, 2)).crossed)
    }

    @Test
    fun `open and closed fingers are counted`() {
        assertEquals(5, fp(open()).openFingers)
        assertEquals(0, fp(open()).closedFingers)
        assertEquals(0, fp(Hand()).openFingers)
        assertEquals(5, fp(Hand()).closedFingers)
        assertEquals(2, fp(Hand().fingers(1, 2)).openFingers)
        assertEquals(3, fp(Hand().fingers(1, 2)).closedFingers)
    }

    @Test
    fun `open fingers held together are not crossed`() {
        assertFalse(fp(open()).crossed)
        assertFalse(fp(open().set(8, 0.445f, 0.28f).set(12, 0.495f, 0.28f)).crossed)
    }

    @Test
    fun `metrics text carries the raw ratios`() {
        val text = GestureFingerprint.metrics(open().xyz())
        assertTrue(text, text.startsWith("gaps "))
    }

    @Test
    fun `peace with the fingers apart has its own code`() {
        val apart = Hand().extend(1, tipX = 0.38f).extend(2, tipX = 0.56f)
        assertEquals("F6A0G1", fp(apart).code)
    }

    @Test
    fun `vulcan salute and spread fingers`() {
        val vulcan = open().set(8, 0.46f, 0.28f).set(12, 0.50f, 0.28f).set(16, 0.62f, 0.28f).set(20, 0.66f, 0.28f)
        assertEquals(0b010, fp(vulcan).gaps)
        assertTrue(fp(vulcan).label.startsWith("🖖"))
        val spread = open().set(8, 0.34f, 0.28f).set(12, 0.48f, 0.28f).set(16, 0.64f, 0.28f).set(20, 0.78f, 0.28f)
        assertEquals(0b111, fp(spread).gaps)
        assertTrue(fp(spread).label.startsWith("🖐️"))
    }

    @Test
    fun `pinch shapes`() {
        // Index bent down to meet the thumb.
        fun pinched(h: Hand) = h.set(8, 0.42f, 0.42f).set(7, 0.43f, 0.36f).set(4, 0.42f, 0.44f)
        val ok = pinched(Hand().fingers(2, 3, 4))
        assertEquals("F28A0P", fp(ok).code)
        assertTrue(fp(ok).label.startsWith("👌"))
        val tiny = pinched(Hand())
        assertEquals("F0A0P", fp(tiny).code)
        assertTrue(fp(tiny).label.startsWith("🤏"))
    }

    @Test
    fun `a fist is not a pinch`() {
        assertFalse(fp(Hand()).pinch)
    }

    @Test
    fun `palm facing, back of hand and edge on`() {
        assertEquals(GestureFingerprint.Palm.AWAY, fp(open(), right = true).palm)
        assertEquals(GestureFingerprint.Palm.FACING, fp(open(), right = false).palm)
        assertEquals(GestureFingerprint.Palm.UNKNOWN, fp(open(), right = null).palm)
        assertTrue(fp(open(), right = true).label.startsWith("🤚"))
        val edge = open().set(5, 0.47f, 0.50f).set(17, 0.50f, 0.51f)
        assertEquals(GestureFingerprint.Palm.EDGE, fp(edge).palm)
    }

    @Test
    fun `palm is ignored on closed hands so a fist code stays stable`() {
        assertEquals(GestureFingerprint.Palm.UNKNOWN, fp(Hand(), right = true).palm)
        assertEquals(fp(Hand(), right = true).code, fp(Hand(), right = false).code)
    }

    @Test
    fun `the same pose always produces the same code`() {
        assertEquals(fp(open()).code, fp(open()).code)
    }

    @Test
    fun `every named pose has its own code`() {
        val poses = mapOf(
            "fist" to Hand(),
            "palm" to open(),
            "one" to Hand().fingers(1),
            "peace" to Hand().fingers(1, 2),
            "rock" to Hand().fingers(1, 4),
            "love" to Hand().thumbOut().fingers(1, 4),
            "call" to Hand().thumbOut().fingers(4),
            "three" to Hand().fingers(1, 2, 3),
            "four" to Hand().fingers(1, 2, 3, 4),
            "crossed" to Hand().extend(1, tipX = 0.52f).extend(2, tipX = 0.47f),
            "thumbup" to Hand().set(4, 0.35f, 0.41f).set(3, 0.37f, 0.50f),
            "thumbdown" to Hand().set(4, 0.32f, 0.85f).set(3, 0.36f, 0.72f),
        )
        val codes = poses.mapValues { fp(it.value).code }
        assertEquals(codes.toString(), poses.size, codes.values.toSet().size)
    }

    @Test
    fun `motion keeps only the finger mask`() {
        val moving = fp(Hand().fingers(1)).withMotion(GestureFingerprint.Motion.CIRCLE_CW)
        assertEquals("F2~CW", moving.code)
        assertEquals("F31~WAVE", fp(open()).withMotion(GestureFingerprint.Motion.WAVE).code)
        assertTrue(moving.label.contains("clockwise"))
    }

    @Test
    fun `two hands together add the partner mask`() {
        val both = fp(open()).copy(partner = 31)
        assertEquals("F31A0&F31", both.code)
        assertTrue(both.label.contains("both hands"))
    }

    @Test
    fun `hands together depends on distance`() {
        val a = open()
        val near = open().also { h -> for (i in 0 until 21) h.pts[i][0] += 0.12f }
        val far = open().also { h -> for (i in 0 until 21) h.pts[i][0] += 0.9f }
        assertTrue(GestureFingerprint.handsTogether(a.xyz(), near.xyz()))
        assertFalse(GestureFingerprint.handsTogether(a.xyz(), far.xyz()))
    }

    @Test
    fun `pointer is the index tip only when the index alone points`() {
        val point = Hand().fingers(1)
        assertEquals(0.28, GestureFingerprint.pointer(point.xyz(), 0b10).second, 1e-4)
        val palm = GestureFingerprint.pointer(open().xyz(), 0b11111)
        assertNotNull(palm)
        assertTrue(palm.second > 0.5)
    }

    @Test
    fun `sequence code joins gestures in order`() {
        assertEquals("F31A0+F0A0", GestureFingerprint.sequenceCode(listOf("F31A0", "F0A0")))
        assertEquals("F0A0", GestureFingerprint.sequenceCode(listOf("F0A0")))
    }

    @Test
    fun `a truncated landmark array yields no fingerprint`() {
        assertNull(GestureFingerprint.fromLandmarks(FloatArray(62)))
    }

    @Test
    fun `the guide lists every family`() {
        assertTrue(GestureNames.guide.size >= 20)
        assertTrue(GestureNames.guide.any { it.emoji.contains("👋") })
    }
}
