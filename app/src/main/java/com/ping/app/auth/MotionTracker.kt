package com.ping.app.auth

import com.ping.app.auth.GestureFingerprint.Motion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Watches one point (the pointing fingertip or the palm centre, in 0..1 image coordinates)
 * and says whether the last second of movement was a circle, a wave, a swipe, or none of them.
 */
class MotionTracker(private val window: Int = WINDOW) {

    private val xs = ArrayDeque<Double>()
    private val ys = ArrayDeque<Double>()

    fun add(x: Double, y: Double) {
        xs.addLast(x); ys.addLast(y)
        while (xs.size > window) { xs.removeFirst(); ys.removeFirst() }
    }

    fun clear() { xs.clear(); ys.clear() }

    /** True when the last [frames] samples all stayed within a small box. */
    fun isStill(frames: Int): Boolean {
        if (xs.size < frames) return false
        val x = xs.toList().takeLast(frames); val y = ys.toList().takeLast(frames)
        return (x.max() - x.min()) < STILL_EXTENT && (y.max() - y.min()) < STILL_EXTENT
    }

    /** The motion seen in the current window, or null. */
    fun classify(): Motion? {
        if (xs.size < MIN_SAMPLES) return null
        return circle() ?: wave() ?: swipe()
    }

    private fun circle(): Motion? {
        val x = xs.toList(); val y = ys.toList()
        val cx = x.average(); val cy = y.average()
        val radii = x.indices.map { hypot(x[it] - cx, y[it] - cy) }
        val mean = radii.average()
        if (mean < MIN_RADIUS) return null
        val spread = sqrt(radii.sumOf { (it - mean) * (it - mean) } / radii.size) / mean
        if (spread > MAX_RADIUS_SPREAD) return null
        // Total angle swept around the centre; in image coordinates (y down) positive is clockwise.
        var swept = 0.0
        var prev = atan2(y[0] - cy, x[0] - cx)
        for (i in 1 until x.size) {
            val a = atan2(y[i] - cy, x[i] - cx)
            var d = a - prev
            if (d > PI) d -= 2 * PI
            if (d < -PI) d += 2 * PI
            swept += d
            prev = a
        }
        return when {
            swept >= MIN_SWEEP -> Motion.CIRCLE_CW
            swept <= -MIN_SWEEP -> Motion.CIRCLE_CCW
            else -> null
        }
    }

    private fun wave(): Motion? {
        val x = xs.toList(); val y = ys.toList()
        val xRange = x.max() - x.min()
        val yRange = y.max() - y.min()
        if (xRange < MIN_WAVE_EXTENT || yRange > xRange * 0.6) return null
        return if (reversals(x) >= MIN_REVERSALS) Motion.WAVE else null
    }

    /** One sweep along a single axis: wide on that axis, narrow across it, and never turning back. */
    private fun swipe(): Motion? {
        val x = xs.toList(); val y = ys.toList()
        val horizontal = x.max() - x.min() >= y.max() - y.min()
        val main = if (horizontal) x else y
        val across = if (horizontal) y else x
        val range = main.max() - main.min()
        val net = main.last() - main.first()
        if (range < MIN_SWIPE_EXTENT || across.max() - across.min() > range * SWIPE_MAX_ACROSS) return null
        if (abs(net) < range * SWIPE_MIN_NET || reversals(main) > 0) return null
        // Image y grows downward, and the frame is mirrored like the preview, so x grows to the user's right.
        return when {
            horizontal && net > 0 -> Motion.SWIPE_RIGHT
            horizontal -> Motion.SWIPE_LEFT
            net > 0 -> Motion.SWIPE_DOWN
            else -> Motion.SWIPE_UP
        }
    }

    /** Direction changes along one axis, ignoring steps smaller than [WAVE_STEP] so jitter does not count. */
    private fun reversals(values: List<Double>): Int {
        var reversals = 0
        var direction = 0
        var anchor = values[0]
        for (v in values) {
            val delta = v - anchor
            if (abs(delta) < WAVE_STEP) continue
            val d = if (delta > 0) 1 else -1
            if (direction != 0 && d != direction) reversals++
            direction = d
            anchor = v
        }
        return reversals
    }

    companion object {
        const val WINDOW = 30
        private const val MIN_SAMPLES = 18
        private const val STILL_EXTENT = 0.06
        private const val MIN_RADIUS = 0.045
        private const val MAX_RADIUS_SPREAD = 0.35
        private const val MIN_SWEEP = 5.2
        private const val MIN_WAVE_EXTENT = 0.12
        private const val WAVE_STEP = 0.04
        private const val MIN_REVERSALS = 3
        private const val MIN_SWIPE_EXTENT = 0.2
        private const val SWIPE_MAX_ACROSS = 0.4
        private const val SWIPE_MIN_NET = 0.8
    }
}
