package com.ping.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gesture code is the pairing key, so the derived code space is checked against
 * the code space the documentation promises: 5 finger bits times 4 direction buckets, plus splay.
 */
class GestureFingerprintTest {

    private val tips = intArrayOf(4, 8, 12, 16, 20)
    private val pips = intArrayOf(2, 6, 10, 14, 18)

    /**
     * Builds a 63-float landmark array with the wrist at (0.5, 0.5) and fingers
     * laid out along the vertical. Proximal joints sit 0.10 from the wrist; an
     * extended tip sits 0.20 away and a curled tip 0.05, against a threshold of
     * 1.15x the proximal distance.
     */
    private fun landmarks(
        middleKnuckle: Pair<Float, Float> = 0.5f to 0.30f,
        extendedFingers: Set<Int> = emptySet(),
        tipSpread: Float = 0f,
    ): FloatArray {
        val xyz = FloatArray(63)
        fun put(index: Int, x: Float, y: Float) {
            xyz[index * 3] = x
            xyz[index * 3 + 1] = y
        }
        put(0, 0.5f, 0.5f)
        put(9, middleKnuckle.first, middleKnuckle.second)
        // Palm width (index to pinky knuckle) is 0.10.
        put(5, 0.45f, 0.40f)
        put(17, 0.55f, 0.40f)
        for (finger in 0 until 5) {
            put(pips[finger], 0.5f, 0.40f)
            put(tips[finger], 0.5f, if (finger in extendedFingers) 0.30f else 0.45f)
        }
        if (1 in extendedFingers) put(8, 0.5f - tipSpread / 2, 0.30f)
        if (4 in extendedFingers) put(20, 0.5f + tipSpread / 2, 0.30f)
        return xyz
    }

    private val allFingers = setOf(0, 1, 2, 3, 4)

    @Test
    fun `open palm pointing up is the all-extended code`() {
        val fingerprint = GestureFingerprint.fromLandmarks(landmarks(extendedFingers = allFingers))
        assertEquals(GestureFingerprint(0b11111, 0), fingerprint)
        assertEquals("F31A0", fingerprint!!.code)
    }

    @Test
    fun `fist pointing up has an empty finger mask`() {
        assertEquals("F0A0", GestureFingerprint.fromLandmarks(landmarks())!!.code)
    }

    @Test
    fun `peace sign keeps only the index and middle bits`() {
        val fingerprint = GestureFingerprint.fromLandmarks(landmarks(extendedFingers = setOf(1, 2)))
        assertEquals(0b00110, fingerprint!!.fingerMask)
        assertEquals("F6A0", fingerprint.code)
    }

    @Test
    fun `the same pose always produces the same code`() {
        assertEquals(
            GestureFingerprint.fromLandmarks(landmarks(extendedFingers = setOf(1, 2)))!!.code,
            GestureFingerprint.fromLandmarks(landmarks(extendedFingers = setOf(1, 2)))!!.code,
        )
    }

    @Test
    fun `without splay the code space is 128 distinct codes`() {
        val directions = listOf(
            (0.5f to 0.30f) to 0, // up
            (0.9f to 0.50f) to 1, // right
            (0.5f to 0.70f) to 2, // down
            (0.1f to 0.50f) to 3, // left
        )
        val codes = mutableSetOf<String>()
        for (mask in 0 until 32) {
            val fingers = (0 until 5).filter { mask and (1 shl it) != 0 }.toSet()
            for ((knuckle, expectedBucket) in directions) {
                val fingerprint = GestureFingerprint.fromLandmarks(
                    landmarks(middleKnuckle = knuckle, extendedFingers = fingers),
                )!!
                assertEquals(mask, fingerprint.fingerMask)
                assertEquals(expectedBucket, fingerprint.angleBucket)
                codes += fingerprint.code
            }
        }
        assertEquals(128, codes.size)
    }

    @Test
    fun `a fanned open hand adds the splay flag`() {
        val together = GestureFingerprint.fromLandmarks(landmarks(extendedFingers = allFingers, tipSpread = 0.10f))!!
        val fanned = GestureFingerprint.fromLandmarks(landmarks(extendedFingers = allFingers, tipSpread = 0.20f))!!
        assertEquals("F31A0", together.code)
        assertEquals("F31A0S", fanned.code)
    }

    @Test
    fun `splay is ignored unless three non-thumb fingers are extended`() {
        val peace = GestureFingerprint.fromLandmarks(landmarks(extendedFingers = setOf(1, 2), tipSpread = 0.30f))!!
        assertEquals("F6A0", peace.code)
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
}
