package com.ping.app.auth

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Turns a MediaPipe 21-point hand into a short, orientation-tolerant **code**
 * that two phones can compare to decide "we're doing the same gesture".
 *
 * The code is the pairing secret in Ping: only phones whose current gesture
 * produces the *same* code will connect and swap cards.
 *
 * Two independent signals go into the code:
 *  - **Finger mask** — 5 bits, one per finger (thumb…pinky), extended = 1.
 *    32 possible finger poses (✊ = 00000, ✋ = 11111, ✌ = 01100, 👍 = 10000, …).
 *  - **Angle bucket** — the wrist→middle-knuckle direction quantised into 4
 *    coarse buckets (up / right / down / left), so "peace pointing up" and
 *    "peace sideways" are different gestures.
 *
 * 32 × 4 = 128 distinct codes — plenty of space for a playful shared password,
 * while the coarse buckets keep it forgiving enough that two people who agreed
 * "let's both do a fist pointing up" reliably land on the same code.
 *
 * A [GestureFingerprint] is a pure function of the landmark geometry — no
 * per-person calibration — which is exactly what makes stranger-to-stranger
 * matching work.
 */
data class GestureFingerprint(
    /** 5-bit finger-extended mask, thumb = bit 0 … pinky = bit 4. */
    val fingerMask: Int,
    /** Hand direction bucket: 0 = up, 1 = right, 2 = down, 3 = left. */
    val angleBucket: Int,
) {
    /** Compact wire code, e.g. "F19A0". This is what gets advertised + compared. */
    val code: String get() = "F${fingerMask}A${angleBucket}"

    /** Friendly one-liner for the UI, e.g. "✌ pointing up". */
    val label: String get() = "${emojiFor(fingerMask)} ${directionName(angleBucket)}"

    companion object {
        // Landmark indices (MediaPipe hand model).
        private const val WRIST = 0
        private const val MIDDLE_MCP = 9
        // tip / pip index pairs per finger, thumb → pinky.
        private val TIPS = intArrayOf(4, 8, 12, 16, 20)
        private val PIPS = intArrayOf(2, 6, 10, 14, 18)

        /**
         * Build a fingerprint from 21 landmarks laid out as [x0,y0,z0, x1,y1,z1, …]
         * (63 floats). Returns null if the hand is missing or degenerate.
         *
         * A finger is "extended" when its tip is farther from the wrist than its
         * middle joint — a rotation-tolerant test that doesn't assume the hand
         * points any particular way.
         */
        fun fromLandmarks(xyz: FloatArray): GestureFingerprint? {
            if (xyz.size < 63) return null
            fun x(i: Int) = xyz[i * 3]
            fun y(i: Int) = xyz[i * 3 + 1]
            val wx = x(WRIST); val wy = y(WRIST)
            fun distFromWrist(i: Int) = hypot((x(i) - wx).toDouble(), (y(i) - wy).toDouble())

            var mask = 0
            for (f in 0 until 5) {
                val extended = distFromWrist(TIPS[f]) > distFromWrist(PIPS[f]) * 1.15
                if (extended) mask = mask or (1 shl f)
            }

            // Direction: wrist → middle knuckle, quantised to 4 buckets.
            // Screen y grows downward, so negate to make "up" intuitive.
            val dx = x(MIDDLE_MCP) - wx
            val dy = -(y(MIDDLE_MCP) - wy)
            val deg = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0
            // Center buckets on up(90)/right(0)/down(270)/left(180): shift by 45°.
            val bucket = when (((deg + 45.0) % 360.0 / 90.0).toInt()) {
                0 -> 1 // right
                1 -> 0 // up
                2 -> 3 // left
                else -> 2 // down
            }
            return GestureFingerprint(mask, bucket)
        }

        private fun directionName(bucket: Int) = when (bucket) {
            0 -> "pointing up"
            1 -> "pointing right"
            2 -> "pointing down"
            else -> "pointing left"
        }

        /** Best-effort emoji for common finger masks; falls back to a hand. */
        private fun emojiFor(mask: Int): String = when (mask) {
            0b00000 -> "✊"      // fist
            0b11111 -> "✋"      // open palm
            0b00001 -> "👍"      // thumb only
            0b00110 -> "✌️" // index + middle (peace)
            0b00010 -> "☝️" // index only
            0b10010 -> "🤟"      // rock-ish
            0b10011 -> "🤙"      // call me-ish
            else -> "🖐️"
        }
    }
}
