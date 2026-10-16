package com.ping.app.model

/**
 * A single live swap session.
 *
 * Flow: SEARCHING (advertising + discovering for a matching gesture code) →
 * CONNECTING (gesture check) → EXCHANGING → COMPLETED, or CANCELLED / NO_MATCH / ERROR.
 */
data class ExchangeSession(
    val gestureCode: String,
    val state: State = State.SEARCHING,
    val receivedContact: Contact? = null,
    val errorMessage: String? = null,
    /** Ping phones in range advertising a different gesture, so a mismatch is not silent. */
    val othersNearby: Int = 0,
) {
    enum class State {
        /** Advertising our gesture code and scanning for a peer with the same one. */
        SEARCHING,
        /** A candidate peer was found; connecting and proving the same gesture. */
        CONNECTING,
        /** The peer proved the same gesture; swapping encrypted cards. */
        EXCHANGING,
        /** Card received and saved. */
        COMPLETED,
        /** No peer proved the same gesture within the window or the attempt limit. */
        NO_MATCH,
        /** User cancelled. */
        CANCELLED,
        /** Unrecoverable error. */
        ERROR,
    }
}
