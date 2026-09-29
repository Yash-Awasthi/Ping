package com.ping.app.auth

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Turns a MediaPipe 21-point hand into a short code that two phones can compare to decide
 * "we're doing the same gesture". The code is the pairing key in Ping: only phones whose
 * gestures produce the same code connect and swap.
 *
 * What goes into the code:
 *  - **Finger mask** — 5 bits, one per finger (thumb…pinky), extended = 1.
 *  - **Direction** — wrist→middle-knuckle in 4 buckets (up/right/down/left). For a lone thumb
 *    (👍 👎) it is the thumb's own direction, so any fist angle gives the same code.
 *  - **Gaps** — on an open hand, which neighbouring fingers are held apart (✌️ 🖖 🖐️).
 *  - **Pinch** — thumb and index tips touching (👌 🤏 🤌); the thumb and index bits are dropped.
 *  - **Crossed** — index and middle crossed (🤞).
 *  - **Palm** — facing the camera, turned away, or edge-on; only on open hands.
 *  - **Motion** — a circle or a wave, see [MotionTracker]. A motion code keeps only the finger
 *    mask, because direction and flags wobble while the hand moves.
 *  - **Partner** — with two hands together (👏 🙏 🤝 🫶) the other hand's mask is added.
 *
 * The code is a pure function of geometry — no per-person calibration — which is what makes
 * stranger-to-stranger matching work. The emoji in [label] is a best guess for the user; the
 * code alone decides matching.
 */
data class GestureFingerprint(
    /** 5-bit finger-extended mask, thumb = bit 0 … pinky = bit 4. */
    val fingerMask: Int,
    /** Direction bucket: 0 = up, 1 = right, 2 = down, 3 = left. */
    val angleBucket: Int,
    /** Bit 0 = index/middle apart, bit 1 = middle/ring apart, bit 2 = ring/pinky apart. */
    val gaps: Int = 0,
    val pinch: Boolean = false,
    val crossed: Boolean = false,
    val palm: Palm = Palm.UNKNOWN,
    val motion: Motion? = null,
    /** Which hand this is, when the left/right call has been steady over recent frames. */
    val hand: Hand? = null,
    /** Finger mask of the second hand when both hands are held together. */
    val partner: Int? = null,
) {
    enum class Hand(val tag: String, val text: String) { LEFT("L", "left hand"), RIGHT("R", "right hand") }

    enum class Palm(val tag: String) { UNKNOWN(""), FACING("f"), AWAY("b"), EDGE("e") }

    enum class Motion(val tag: String, val text: String) {
        CIRCLE_CW("CW", "circle clockwise"),
        CIRCLE_CCW("CCW", "circle counter-clockwise"),
        WAVE("WAVE", "wave"),
    }

    /** Compact wire code, e.g. "F31A0", "F2A1P" or "F31~WAVE". This is what gets advertised + compared. */
    val code: String
        get() {
            if (motion != null) return "F$fingerMask~${motion.tag}" + (hand?.let { "H${it.tag}" } ?: "") + partnerTag()
            return buildString {
                append("F").append(fingerMask).append("A").append(angleBucket)
                if (gaps != 0) append("G").append(gaps)
                if (pinch) append("P")
                if (crossed) append("X")
                if (palm != Palm.UNKNOWN) append("O").append(palm.tag)
                if (hand != null) append("H").append(hand.tag)
                append(partnerTag())
            }
        }

    private fun partnerTag() = if (partner != null) "&F$partner" else ""

    /** How many of the five fingers are held out (a pinch counts its thumb and index as closed). */
    val openFingers: Int get() = Integer.bitCount(fingerMask)

    val closedFingers: Int get() = 5 - openFingers

    /** Friendly one-liner for the UI, e.g. "✌️ pointing up". */
    val label: String get() = GestureNames.label(this)

    /** The same hand with only what stays stable while it moves, plus the [motion]. */
    fun withMotion(motion: Motion) =
        GestureFingerprint(fingerMask, 0, partner = partner, motion = motion, hand = hand)

    companion object {
        // Landmark indices (MediaPipe hand model).
        private const val WRIST = 0
        private const val THUMB_MCP = 2
        private const val THUMB_TIP = 4
        private const val INDEX_MCP = 5
        private const val INDEX_TIP = 8
        private const val MIDDLE_MCP = 9
        private const val MIDDLE_TIP = 12
        private const val RING_TIP = 16
        private const val PINKY_MCP = 17
        private const val PINKY_TIP = 20
        // tip / pip index pairs per finger, thumb → pinky.
        private val TIPS = intArrayOf(4, 8, 12, 16, 20)
        private val PIPS = intArrayOf(2, 6, 10, 14, 18)

        private const val EXTENDED_RATIO = 1.15
        private const val THUMB_STRAIGHT = 0.8
        private const val THUMB_AWAY = 0.67
        /** Fingertip gap over palm width above which two extended neighbours count as apart. */
        private const val GAP_RATIO = 0.55
        /** Thumb-tip to index-tip distance over palm width below which the pair is pinched. */
        private const val PINCH_RATIO = 0.4
        /** A pinching index finger is bent but not fully curled into the palm. */
        private const val PINCH_INDEX_MIN = 0.85
        /** |sin| of the angle between the wrist→index and wrist→pinky knuckle lines below which the palm is edge-on. */
        private const val EDGE_SIN = 0.3
        /** Tips must swap order by at least this share of the knuckle spacing to count as crossed. */
        private const val CROSS_MARGIN = 0.3
        /** Two hands whose palm centres are within this many hand sizes count as together. */
        private const val TOGETHER_RATIO = 1.6

        private const val THUMB = 0b00001
        private const val INDEX = 0b00010
        private const val NON_THUMB = 0b11110

        /** Joins gesture codes typed in order into one pairing key. */
        fun sequenceCode(codes: List<String>): String = codes.joinToString("+")

        /**
         * Build a fingerprint from 21 landmarks laid out as [x0,y0,z0, x1,y1,z1, …]
         * (63 floats). [isRight] is MediaPipe's handedness for this hand when known; without it
         * the palm can only be told apart when edge-on. Returns null if the hand is missing.
         */
        fun fromLandmarks(xyz: FloatArray, isRight: Boolean? = null): GestureFingerprint? {
            if (xyz.size < 63) return null
            fun x(i: Int) = xyz[i * 3].toDouble()
            fun y(i: Int) = xyz[i * 3 + 1].toDouble()
            fun dist(a: Int, b: Int) = hypot(x(a) - x(b), y(a) - y(b))
            val wx = x(WRIST); val wy = y(WRIST)
            fun fromWrist(i: Int) = hypot(x(i) - wx, y(i) - wy)

            val palmWidth = dist(INDEX_MCP, PINKY_MCP)
            if (palmWidth <= 0.0) return null

            var mask = 0
            for (f in 1 until 5) {
                if (fromWrist(TIPS[f]) > fromWrist(PIPS[f]) * EXTENDED_RATIO) mask = mask or (1 shl f)
            }
            if (thumbOut(xyz, palmWidth)) mask = mask or THUMB

            // Pinch: thumb and index tips touch, and the index is bent rather than curled flat.
            val pinch = dist(THUMB_TIP, INDEX_TIP) / palmWidth < PINCH_RATIO &&
                fromWrist(INDEX_TIP) > fromWrist(6) * PINCH_INDEX_MIN
            if (pinch) mask = mask and (THUMB or INDEX).inv()

            var gaps = 0
            val tips = intArrayOf(INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP)
            for (g in 0 until 3) {
                val bothOut = mask and (1 shl (g + 1)) != 0 && mask and (1 shl (g + 2)) != 0
                if (bothOut && dist(tips[g], tips[g + 1]) / palmWidth > GAP_RATIO) gaps = gaps or (1 shl g)
            }

            // Crossed: the index and middle tips swap their order along the knuckle line.
            var crossed = false
            if (mask and INDEX != 0 && mask and 0b100 != 0 && gaps and 1 == 0) {
                val ux = x(PINKY_MCP) - x(INDEX_MCP); val uy = y(PINKY_MCP) - y(INDEX_MCP)
                fun along(i: Int) = x(i) * ux + y(i) * uy
                val knuckles = along(MIDDLE_MCP) - along(INDEX_MCP)
                val tipsOrder = along(MIDDLE_TIP) - along(INDEX_TIP)
                crossed = knuckles * tipsOrder < -CROSS_MARGIN * knuckles * knuckles
            }

            // Palm: how wide the knuckle line looks tells facing/away from edge-on.
            val v1x = x(INDEX_MCP) - wx; val v1y = y(INDEX_MCP) - wy
            val v2x = x(PINKY_MCP) - wx; val v2y = y(PINKY_MCP) - wy
            val cross = v1x * v2y - v1y * v2x
            val sin = abs(cross) / (hypot(v1x, v1y) * hypot(v2x, v2y)).coerceAtLeast(1e-9)
            val palm = when {
                Integer.bitCount(mask and NON_THUMB) < 3 -> Palm.UNKNOWN
                sin < EDGE_SIN -> Palm.EDGE
                isRight == null -> Palm.UNKNOWN
                (cross > 0) != isRight -> Palm.FACING
                else -> Palm.AWAY
            }

            // Direction: wrist → middle knuckle, or the thumb itself for a lone thumb.
            val bucket = if (mask == THUMB) {
                bucketOf(x(THUMB_TIP) - x(THUMB_MCP), -(y(THUMB_TIP) - y(THUMB_MCP)))
            } else {
                bucketOf(x(MIDDLE_MCP) - wx, -(y(MIDDLE_MCP) - wy))
            }
            return GestureFingerprint(mask, bucket, gaps, pinch, crossed, palm)
        }

        /** Raw ratios behind the flags, for the Practice screen: gaps, pinch and knuckle-line sine. */
        fun metrics(xyz: FloatArray): String {
            if (xyz.size < 63) return ""
            fun d(a: Int, b: Int) = hypot((xyz[a * 3] - xyz[b * 3]).toDouble(), (xyz[a * 3 + 1] - xyz[b * 3 + 1]).toDouble())
            val w = d(INDEX_MCP, PINKY_MCP)
            if (w <= 0.0) return ""
            val v1x = (xyz[INDEX_MCP * 3] - xyz[0]).toDouble(); val v1y = (xyz[INDEX_MCP * 3 + 1] - xyz[1]).toDouble()
            val v2x = (xyz[PINKY_MCP * 3] - xyz[0]).toDouble(); val v2y = (xyz[PINKY_MCP * 3 + 1] - xyz[1]).toDouble()
            val sin = abs(v1x * v2y - v1y * v2x) / (hypot(v1x, v1y) * hypot(v2x, v2y)).coerceAtLeast(1e-9)
            val chain = d(THUMB_MCP, 3) + d(3, THUMB_TIP)
            return "gaps %.2f %.2f %.2f · pinch %.2f · sin %.2f · thumb straight %.2f away %.2f · side %s".format(
                d(INDEX_TIP, MIDDLE_TIP) / w, d(MIDDLE_TIP, RING_TIP) / w, d(RING_TIP, PINKY_TIP) / w,
                d(THUMB_TIP, INDEX_TIP) / w, sin,
                if (chain > 0) d(THUMB_MCP, THUMB_TIP) / chain else 0.0, d(THUMB_TIP, INDEX_MCP) / w,
                if (v1x * v2y - v1y * v2x > 0) "left" else "right",
            )
        }

        /**
         * A thumb counts as out when it is straight (its joints line up) and its tip is well away
         * from the index knuckle. A thumb tucked over a fist fails the second test, and a thumb
         * resting bent against the side fails the first.
         */
        fun thumbOut(xyz: FloatArray, palmWidth: Double): Boolean {
            fun d(a: Int, b: Int) = hypot((xyz[a * 3] - xyz[b * 3]).toDouble(), (xyz[a * 3 + 1] - xyz[b * 3 + 1]).toDouble())
            val chain = d(THUMB_MCP, 3) + d(3, THUMB_TIP)
            val straight = if (chain > 0) d(THUMB_MCP, THUMB_TIP) / chain else 0.0
            return straight >= THUMB_STRAIGHT && d(THUMB_TIP, INDEX_MCP) / palmWidth >= THUMB_AWAY
        }

        /** Centre of the palm, used to place a hand for motion tracking and two-hand checks. */
        fun palmCentre(xyz: FloatArray): Pair<Double, Double> {
            val ids = intArrayOf(WRIST, INDEX_MCP, PINKY_MCP)
            return ids.sumOf { xyz[it * 3].toDouble() } / ids.size to ids.sumOf { xyz[it * 3 + 1].toDouble() } / ids.size
        }

        /** True when two hands are close enough, relative to their size, to be one two-hand gesture. */
        fun handsTogether(a: FloatArray, b: FloatArray): Boolean {
            val (ax, ay) = palmCentre(a); val (bx, by) = palmCentre(b)
            fun size(h: FloatArray) = hypot((h[9 * 3] - h[0]).toDouble(), (h[9 * 3 + 1] - h[1]).toDouble())
            val avg = (size(a) + size(b)) / 2
            return avg > 0 && hypot(ax - bx, ay - by) / avg < TOGETHER_RATIO
        }

        /** Where the moving point of a hand is: the index tip when it points, else the palm centre. */
        fun pointer(xyz: FloatArray, mask: Int): Pair<Double, Double> =
            if (mask and INDEX != 0 && mask and 0b100 == 0) {
                xyz[INDEX_TIP * 3].toDouble() to xyz[INDEX_TIP * 3 + 1].toDouble()
            } else palmCentre(xyz)

        /** Buckets a vector (x right, y up) into 0 up, 1 right, 2 down, 3 left. */
        private fun bucketOf(dx: Double, dy: Double): Int {
            val deg = (Math.toDegrees(atan2(dy, dx)) + 360.0) % 360.0
            return when (((deg + 45.0) % 360.0 / 90.0).toInt()) {
                0 -> 1
                1 -> 0
                2 -> 3
                else -> 2
            }
        }
    }
}
