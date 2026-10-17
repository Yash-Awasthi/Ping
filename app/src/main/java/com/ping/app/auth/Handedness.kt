package com.ping.app.auth

import com.ping.app.auth.GestureFingerprint.Hand

/**
 * Settles which hand is in frame from two per-frame clues, the detector's label and the hand's
 * own shape ([GestureFingerprint.shapeIsRight]), so a few odd frames cannot flip the call.
 */
class Handedness {

    private var score = 0.0

    var hand: Hand? = null
        private set

    /** Each clue is true for the user's right hand, false for the left, null when it has no opinion. */
    fun update(detectorRight: Boolean?, shapeRight: Boolean?): Hand? {
        val votes = listOfNotNull(detectorRight, shapeRight).map { if (it) 1.0 else -1.0 }
        if (votes.isNotEmpty()) score += (votes.average() - score) * SMOOTHING
        // Hysteresis: once settled, only a steady lean past the opposite mark flips it.
        hand = when {
            score >= SETTLE -> Hand.RIGHT
            score <= -SETTLE -> Hand.LEFT
            else -> hand
        }
        return hand
    }

    fun clear() {
        score = 0.0
        hand = null
    }

    companion object {
        private const val SMOOTHING = 0.2
        private const val SETTLE = 0.6
    }
}
