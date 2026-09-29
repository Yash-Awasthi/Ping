package com.ping.app.service

/**
 * Minimal transport interface that abstracts Google Nearby Connections
 * from the wire-protocol logic.
 *
 * Motivations:
 *  1. [NearbyExchangeService] depends directly on [com.google.android.gms.nearby.connection.ConnectionsClient].
 *     That class is a Play Services singleton — impossible to instantiate in JVM unit tests.
 *  2. Extracting send/receive to this interface lets a [FakeNearbyTransport] wire two
 *     in-process "endpoints" together, enabling full wire-protocol JVM tests without
 *     real BLE/Wi-Fi or an Android device.
 *
 * Scope: BYTES payloads plus optional byte streams for file transfer ([sendStream]).
 *
 * This interface intentionally has NO imports from com.google.android.gms or android.*
 * so it is usable in pure-JVM (src/test/) test code.
 */
interface NearbyTransport {

    /**
     * Send a raw bytes payload to [endpointId].
     * Delivery is best-effort. On the real transport, failures are logged
     * via [com.google.android.gms.tasks.Task] failure listeners; the fake
     * delivers synchronously.
     */
    fun sendBytes(endpointId: String, data: ByteArray)

    /**
     * Register a callback invoked when bytes arrive from any connected endpoint.
     * Must be set before any connections are accepted.
     */
    var onPayloadReceived: ((endpointId: String, data: ByteArray) -> Unit)?

    /**
     * Register a callback invoked when an endpoint connects and is accepted.
     * Parameters: (endpointId, remoteName, isIncoming)
     */
    var onConnected: ((endpointId: String, remoteName: String, isIncoming: Boolean) -> Unit)?

    /**
     * Register a callback invoked when an endpoint disconnects.
     */
    var onDisconnected: ((endpointId: String) -> Unit)?

    /**
     * Register a callback invoked when a new remote endpoint is discovered
     * (discovery mode only). Parameters: (endpointId, remoteName)
     */
    var onEndpointFound: ((endpointId: String, remoteName: String) -> Unit)?

    /**
     * Register a callback invoked when an incoming connection is initiated
     * but before it has been accepted or rejected.
     *
     * [NearbyExchangeService] uses this to run the async blocklist check
     * before deciding whether to call [acceptConnection] or [rejectConnection].
     *
     * For Nearby Connections: fires from [ConnectionLifecycleCallback.onConnectionInitiated].
     * For Wi-Fi Direct: fires from [WifiDirectTransport.onPeerSocketReady] (OS already
     * established the connection; rejecting calls removeGroup()).
     */
    var onConnectionInitiated: ((endpointId: String, remoteName: String) -> Unit)?

    /** Start advertising under [localName] and [serviceId]; [star] lets one host serve many guests. */
    fun startAdvertising(localName: String, serviceId: String, star: Boolean = false)

    /** Start discovering endpoints advertising [serviceId]; [star] must match the host's setting. */
    fun startDiscovery(serviceId: String, star: Boolean = false)

    /** Request a connection to [endpointId] found via discovery. */
    fun requestConnection(localName: String, endpointId: String)

    /** Accept a pending incoming connection from [endpointId]. */
    fun acceptConnection(endpointId: String)

    /** Reject a pending incoming connection from [endpointId]. */
    fun rejectConnection(endpointId: String)

    /** Drop one connected endpoint and leave the others up. */
    fun disconnect(endpointId: String) {}

    /** Stop advertising. */
    fun stopAdvertising()

    /** Stop discovery. */
    fun stopDiscovery()

    /** Disconnect all endpoints and clean up. */
    fun stopAllEndpoints()

    // Optional streaming — gms only; no-ops on test doubles

    /**
     * Send a raw byte stream (file transfer).
     *
     * The concrete transport wraps it in whatever mechanism it supports
     * (Nearby Connections STREAM payload for gms; WifiDirect is bytes-only).
     *
     * Returns true if the stream transfer was initiated; false if the transport
     * does not support streaming (FOSS / test doubles). Callers skip the send
     * when false is returned.
     *
     * Default: no-op, returns false. Override in gms transport only.
     */
    fun sendStream(
        endpointId: String,
        inputStream: java.io.InputStream,
        lengthHint: Long,
    ): Boolean = false

    /**
     * Callback for an incoming byte-stream.
     *
     * The transport unwraps its internal stream type and delivers a plain
     * [java.io.InputStream] so callers are GMS-free.
     *
     * Default: no-op getter / setter. Transports that do not support streaming
     * never invoke this callback and silently discard the setter assignment.
     * Override in gms transport only.
     */
    var onStreamReceived: ((endpointId: String, stream: java.io.InputStream) -> Unit)?
        get() = null
        @Suppress("UNUSED_PARAMETER") set(value) {}
}
