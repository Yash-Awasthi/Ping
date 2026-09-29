package com.ping.app.service

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Production [NearbyTransport] implementation backed by Google Nearby Connections.
 *
 * Wraps [ConnectionsClient] and its callback hierarchy, exposing the same
 * interface as [WifiDirectTransport] and [FakeNearbyTransport] so that
 * [NearbyExchangeService] is transport-agnostic.
 *
 * Provided via Hilt in the `gms` product flavor — see `di/TransportModule.kt`
 * in the `gms` source set.
 *
 * STREAM payloads carry file bytes for the room hub; see [sendStream] and [onStreamReceived].
 */
class NearbyConnectionsTransport(context: Context) : NearbyTransport {

    internal val client: ConnectionsClient = Nearby.getConnectionsClient(context)

    // NearbyTransport callbacks

    override var onPayloadReceived: ((endpointId: String, data: ByteArray) -> Unit)? = null
    override var onConnected: ((endpointId: String, remoteName: String, isIncoming: Boolean) -> Unit)? = null
    override var onDisconnected: ((endpointId: String) -> Unit)? = null
    override var onEndpointFound: ((endpointId: String, remoteName: String) -> Unit)? = null
    override var onConnectionInitiated: ((endpointId: String, remoteName: String) -> Unit)? = null
    override var onAuthDigits: ((endpointId: String, digits: String) -> Unit)? = null

    // Streaming — NearbyTransport optional extension

    private var _onStreamReceived: ((endpointId: String, stream: java.io.InputStream) -> Unit)? = null

    /** Bridges the GMS [Payload.Type.STREAM] callback to a plain [InputStream]. */
    override var onStreamReceived: ((endpointId: String, stream: java.io.InputStream) -> Unit)?
        get() = _onStreamReceived
        set(value) { _onStreamReceived = value }

    // Internal state

    /** endpointId → remoteName, captured in [ConnectionLifecycleCallback.onConnectionInitiated]. */
    private val endpointNames = ConcurrentHashMap<String, String>()

    /**
     * Set of endpointIds for which we received [ConnectionLifecycleCallback.onConnectionInitiated]
     * (i.e. we are the advertising/server side). Used to set isIncoming in [onConnected].
     */
    private val incomingInitiated: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Nearby Connections callbacks

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    if (bytes.isNotEmpty()) onPayloadReceived?.invoke(endpointId, bytes)
                }
                Payload.Type.STREAM -> {
                    val stream = payload.asStream()?.asInputStream() ?: return
                    _onStreamReceived?.invoke(endpointId, stream)
                }
                else -> Timber.d("NearbyConnectionsTransport: ignoring payload type ${payload.type} from $endpointId")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No-op — BYTES transfers complete synchronously; STREAM progress not tracked
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            incomingInitiated.add(endpointId)
            Timber.d("NearbyConnectionsTransport: connection initiated from $endpointId (${info.endpointName})")
            onAuthDigits?.invoke(endpointId, info.authenticationDigits)
            onConnectionInitiated?.invoke(endpointId, info.endpointName)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val name = endpointNames.remove(endpointId) ?: endpointId
            val incoming = incomingInitiated.remove(endpointId)
            if (result.status.isSuccess) {
                Timber.i("NearbyConnectionsTransport: connected to $endpointId (incoming=$incoming)")
                onConnected?.invoke(endpointId, name, incoming)
            } else {
                Timber.w("NearbyConnectionsTransport: connection to $endpointId failed: ${result.status.statusMessage}")
                // Surface as disconnect so the service can terminate cleanly
                incomingInitiated.remove(endpointId)
                onDisconnected?.invoke(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            endpointNames.remove(endpointId)
            incomingInitiated.remove(endpointId)
            Timber.d("NearbyConnectionsTransport: disconnected from $endpointId")
            onDisconnected?.invoke(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Timber.d("NearbyConnectionsTransport: endpoint found $endpointId (${info.endpointName})")
            onEndpointFound?.invoke(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.d("NearbyConnectionsTransport: endpoint lost $endpointId")
        }
    }

    // NearbyTransport — implementation

    override fun startAdvertising(localName: String, serviceId: String, star: Boolean) {
        val options = AdvertisingOptions.Builder().setStrategy(strategy(star)).build()
        client.startAdvertising(localName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { Timber.d("NearbyConnectionsTransport: advertising started as $localName") }
            .addOnFailureListener { e -> Timber.e(e, "NearbyConnectionsTransport: startAdvertising failed") }
    }

    override fun startDiscovery(serviceId: String, star: Boolean) {
        val options = DiscoveryOptions.Builder().setStrategy(strategy(star)).build()
        client.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { Timber.d("NearbyConnectionsTransport: discovery started") }
            .addOnFailureListener { e -> Timber.e(e, "NearbyConnectionsTransport: startDiscovery failed") }
    }

    override fun requestConnection(localName: String, endpointId: String) {
        client.requestConnection(localName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener { Timber.d("NearbyConnectionsTransport: connection request sent to $endpointId") }
            .addOnFailureListener { e ->
                Timber.e(e, "NearbyConnectionsTransport: requestConnection failed to $endpointId")
            }
    }

    override fun acceptConnection(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener { Timber.d("NearbyConnectionsTransport: accepted $endpointId") }
            .addOnFailureListener { e -> Timber.e(e, "NearbyConnectionsTransport: acceptConnection failed for $endpointId") }
    }

    override fun rejectConnection(endpointId: String) {
        client.rejectConnection(endpointId)
            .addOnSuccessListener { Timber.d("NearbyConnectionsTransport: rejected $endpointId") }
            .addOnFailureListener { e -> Timber.e(e, "NearbyConnectionsTransport: rejectConnection failed for $endpointId") }
    }

    override fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
    }

    override fun stopAdvertising() {
        client.stopAdvertising()
        Timber.d("NearbyConnectionsTransport: stopped advertising")
    }

    override fun stopDiscovery() {
        client.stopDiscovery()
        Timber.d("NearbyConnectionsTransport: stopped discovery")
    }

    override fun stopAllEndpoints() {
        client.stopAllEndpoints()
        endpointNames.clear()
        incomingInitiated.clear()
        Timber.d("NearbyConnectionsTransport: all endpoints stopped")
    }

    override fun sendBytes(endpointId: String, data: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(data))
            .addOnFailureListener { e -> Timber.e(e, "NearbyConnectionsTransport: sendBytes failed to $endpointId") }
    }

    /**
     * Wrap [inputStream] in a Nearby Connections STREAM [Payload] and send it to [endpointId].
     *
     * Implements [NearbyTransport.sendStream]; test doubles use the default no-op that returns false.
     */
    override fun sendStream(
        endpointId: String,
        inputStream: java.io.InputStream,
        lengthHint: Long,
    ): Boolean {
        return try {
            val payload = Payload.fromStream(inputStream)
            client.sendPayload(endpointId, payload)
                .addOnSuccessListener { Timber.d("NearbyConnectionsTransport: STREAM sent to $endpointId") }
                .addOnFailureListener { e -> Timber.e(e, "NearbyConnectionsTransport: STREAM send failed to $endpointId") }
            true
        } catch (e: Exception) {
            Timber.e(e, "NearbyConnectionsTransport: sendStream failed to $endpointId")
            false
        }
    }

    companion object {
        private fun strategy(star: Boolean) = if (star) Strategy.P2P_STAR else Strategy.P2P_CLUSTER
    }
}
