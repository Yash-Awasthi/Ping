package com.ping.app.auth

import com.ping.app.auth.GestureFingerprint.Motion

/**
 * Decides when a hand has held one gesture long enough to lock it as the pairing code. A still
 * hand locks on its pose after [COMMIT_FRAMES]; a moving hand locks on its finger mask and motion.
 */
class GestureLock {

    private var lastBase: String? = null
    private var lastHand: GestureFingerprint.Hand? = null
    private var streak = 0
    private var lastMask = -1
    private var maskStreak = 0
    private var lastMotion: Motion? = null
    private var motionStreak = 0

    /** 0..1 progress of the still-pose lock. */
    val stability: Float get() = (streak.toFloat() / COMMIT_FRAMES).coerceAtMost(1f)

    /** Feeds one frame; returns the locked fingerprint, or null while still detecting. */
    fun update(fp: GestureFingerprint, motion: Motion?, still: Boolean): GestureFingerprint? {
        // The hand side settling mid-hold only refines the code, so it must not restart the count:
        // whichever frame the side settles on, the code locked is the same as waiting it out.
        val base = fp.copy(hand = null).code
        if (base == lastBase && (fp.hand == lastHand || lastHand == null)) streak++ else { lastBase = base; streak = 1 }
        lastHand = fp.hand
        if (fp.fingerMask == lastMask) maskStreak++ else { lastMask = fp.fingerMask; maskStreak = 1 }
        if (motion != null && motion == lastMotion) motionStreak++ else { lastMotion = motion; motionStreak = if (motion != null) 1 else 0 }

        // Direction and flags wobble while a hand moves, so a motion code keeps only the finger mask.
        return when {
            motion != null && maskStreak >= MOTION_POSE_FRAMES && motionStreak >= MOTION_FRAMES -> fp.withMotion(motion)
            motion == null && streak >= COMMIT_FRAMES && still -> fp
            else -> null
        }
    }

    fun clear() {
        lastBase = null; lastHand = null; streak = 0
        lastMask = -1; maskStreak = 0
        lastMotion = null; motionStreak = 0
    }

    companion object {
        /** Frames the same code must persist before we lock it. ~0.2s at 30fps. */
        const val COMMIT_FRAMES = 6
        /** A motion locks after the fingers held one shape this long and the motion read the same this many frames. */
        private const val MOTION_POSE_FRAMES = 10
        private const val MOTION_FRAMES = 4
    }
}
