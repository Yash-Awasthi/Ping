package com.ping.app.service

import java.io.InputStream
import java.util.Collections

/** In-memory stand-in for the radio: endpoint ids are the names passed to [endpoint]. */
internal class FakeAir {
    private val nodes = Collections.synchronizedMap(mutableMapOf<String, Node>())
    private val frames: MutableList<Triple<String, String, ByteArray>> = Collections.synchronizedList(mutableListOf())

    /** Types of the frames [from] sent [to], in order. */
    fun types(from: String, to: String): List<Byte> = synchronized(frames) {
        frames.filter { it.first == from && it.second == to }.mapNotNull { it.third.firstOrNull() }
    }

    fun endpoint(id: String): Node = Node(id).also { nodes[id] = it }

    /** [id] stops sending frames of [type], as a peer that never answers would. */
    fun drop(id: String, type: Byte) { nodes.getValue(id).dropped += type }

    /** [id] hears about its new links [ms] late, so the peer's first frames beat its onConnected. */
    fun lateConnect(id: String, ms: Long) { nodes.getValue(id).connectDelayMs = ms }

    inner class Node(val id: String) : NearbyTransport {
        override var onPayloadReceived: ((String, ByteArray) -> Unit)? = null
        override var onConnected: ((String, String, Boolean) -> Unit)? = null
        override var onDisconnected: ((String) -> Unit)? = null
        override var onEndpointFound: ((String, String) -> Unit)? = null
        override var onEndpointLost: ((String) -> Unit)? = null
        override var onConnectionInitiated: ((String, String) -> Unit)? = null
        override var onStreamReceived: ((String, InputStream) -> Unit)? = null
        override var onAuthToken: ((String, ByteArray) -> Unit)? = null
        val dropped: MutableSet<Byte> = Collections.synchronizedSet(mutableSetOf())
        @Volatile var connectDelayMs = 0L

        @Volatile var advertised: String? = null
        @Volatile var discovering = false
        private val accepted = Collections.synchronizedSet(mutableSetOf<String>())
        private val names = Collections.synchronizedMap(mutableMapOf<String, String>())

        override fun startAdvertising(localName: String, serviceId: String, star: Boolean) {
            advertised = localName
            peers().filter { it.discovering }.forEach { it.onEndpointFound?.invoke(id, localName) }
        }

        override fun startDiscovery(serviceId: String, star: Boolean) {
            discovering = true
            peers().forEach { n -> n.advertised?.let { onEndpointFound?.invoke(n.id, it) } }
        }

        override fun requestConnection(localName: String, endpointId: String) {
            val peer = nodes[endpointId] ?: return
            names[endpointId] = peer.advertised.orEmpty()
            peer.names[id] = localName
            onAuthToken?.invoke(endpointId, byteArrayOf(4, 8, 2, 1))
            peer.onAuthToken?.invoke(id, byteArrayOf(4, 8, 2, 1))
            onConnectionInitiated?.invoke(endpointId, peer.advertised.orEmpty())
            peer.onConnectionInitiated?.invoke(id, localName)
        }

        // Both ends accept on their own threads; unlocked, each can miss the other's accept.
        override fun acceptConnection(endpointId: String) {
            synchronized(this@FakeAir) {
                accepted += endpointId
                val peer = nodes[endpointId] ?: return
                if (id in peer.accepted) {
                    connected(endpointId, names[endpointId].orEmpty(), false)
                    peer.connected(id, peer.names[id].orEmpty(), true)
                }
            }
        }

        private fun connected(endpointId: String, name: String, incoming: Boolean) {
            val delay = connectDelayMs
            if (delay == 0L) onConnected?.invoke(endpointId, name, incoming)
            else Thread { Thread.sleep(delay); onConnected?.invoke(endpointId, name, incoming) }.start()
        }

        // Nearby reports a rejected request as a failed result on both ends.
        override fun rejectConnection(endpointId: String) {
            nodes[endpointId]?.onDisconnected?.invoke(id)
            onDisconnected?.invoke(endpointId)
        }

        override fun sendBytes(endpointId: String, data: ByteArray) {
            if (data.firstOrNull() in dropped) return
            frames += Triple(id, endpointId, data)
            nodes[endpointId]?.onPayloadReceived?.invoke(id, data)
        }

        override fun disconnect(endpointId: String) {
            accepted -= endpointId
            nodes[endpointId]?.let { it.accepted -= id; it.onDisconnected?.invoke(id) }
            onDisconnected?.invoke(endpointId)
        }

        override fun stopAdvertising() { advertised = null }
        override fun stopDiscovery() { discovering = false }
        override fun stopAllEndpoints() {
            accepted.toList().filter { nodes[it]?.accepted?.contains(id) == true }.forEach { peer ->
                nodes[peer]?.let { it.accepted -= id; it.onDisconnected?.invoke(id) }
            }
            accepted.clear()
        }

        private fun peers() = synchronized(nodes) { nodes.values.filter { it !== this } }
    }
}
