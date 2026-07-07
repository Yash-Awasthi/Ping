package com.ping.app.model

/**
 * A single live swap session.
 *
 * Flow: SEARCHING (advertising + discovering for a matching gesture code) →
 * CONNECTING → EXCHANGING → COMPLETED, or CANCELLED / NO_MATCH / ERROR.
 */
data class ExchangeSession(
    val gestureCode: String,
    val state: State = State.SEARCHING,
    val receivedContact: Contact? = null,
    val errorMessage: String? = null,
) {
    enum class State {
        /** Advertising our gesture code and scanning for a peer with the same one. */
        SEARCHING,
        /** A matching peer was found — negotiating the connection. */
        CONNECTING,
        /** Connected — swapping encrypted cards. */
        EXCHANGING,
        /** Card received and saved. */
        COMPLETED,
        /** No matching peer appeared within the pairing window. */
        NO_MATCH,
        /** User cancelled. */
        CANCELLED,
        /** Unrecoverable error. */
        ERROR,
    }
}
