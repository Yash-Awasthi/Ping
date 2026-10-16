package com.ping.app.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.ping.app.service.BleWire.T_ACCEPT
import com.ping.app.service.BleWire.T_BYTES
import com.ping.app.service.BleWire.T_CHUNK
import com.ping.app.service.BleWire.T_END
import com.ping.app.service.BleWire.T_HELLO
import com.ping.app.service.BleWire.T_REJECT
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [NearbyTransport] for phones without Play Services: BLE advertising carries the endpoint
 * name and an L2CAP PSM, and an insecure L2CAP channel carries the bytes. The link is
 * encrypted by [BleWire.handshake], which stands in for Nearby's UKEY2.
 *
 * Callbacks run on the main thread, like Nearby's. Both sides see [onConnectionInitiated]
 * and must accept before [onConnected]; a rejected or failed connection surfaces as
 * [onDisconnected], and local [disconnect] / [stopAllEndpoints] stay silent.
 */
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.Q)
class BleL2capTransport(context: Context) : NearbyTransport {

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool { Thread(it, "ble-l2cap").apply { isDaemon = true } }
    private val nextId = AtomicInteger()

    override var onPayloadReceived: ((endpointId: String, data: ByteArray) -> Unit)? = null
    override var onConnected: ((endpointId: String, remoteName: String, isIncoming: Boolean) -> Unit)? = null
    override var onDisconnected: ((endpointId: String) -> Unit)? = null
    override var onEndpointFound: ((endpointId: String, remoteName: String) -> Unit)? = null
    override var onConnectionInitiated: ((endpointId: String, remoteName: String) -> Unit)? = null
    override var onEndpointLost: ((endpointId: String) -> Unit)? = null
    override var onAuthToken: ((endpointId: String, token: ByteArray) -> Unit)? = null
    override var onStreamReceived: ((endpointId: String, stream: InputStream) -> Unit)? = null

    private class Found(val id: String, val device: BluetoothDevice, val psm: Int, val name: String) {
        @Volatile var lastSeen = SystemClock.elapsedRealtime()
    }

    /** Discovered adverts by device address; survives stopDiscovery so a later request still resolves. */
    private val found = ConcurrentHashMap<String, Found>()
    private val links = ConcurrentHashMap<String, Link>()

    @Volatile private var server: BluetoothServerSocket? = null
    private var advertising: AdvertisingSetCallback? = null
    private var scanning: ScanCallback? = null

    private val sweep = object : Runnable {
        override fun run() {
            val cutoff = SystemClock.elapsedRealtime() - LOST_MS
            found.entries.filter { it.value.lastSeen < cutoff }.forEach { (address, f) ->
                found.remove(address)
                onEndpointLost?.invoke(f.id)
            }
            main.postDelayed(this, SWEEP_MS)
        }
    }

    // Advertising

    override fun startAdvertising(localName: String, serviceId: String, star: Boolean) {
        stopAdvertising()
        val a = adapter ?: return Timber.e("BleL2capTransport: no Bluetooth adapter")
        val srv = runCatching { a.listenUsingInsecureL2capChannel() }
            .getOrElse { return Timber.e(it, "BleL2capTransport: L2CAP listen failed") }
        server = srv
        pool.execute {
            while (true) {
                val socket = runCatching { srv.accept() }.getOrNull() ?: break
                pool.execute { Link(newId(), socket, initiator = false, remoteName = "").run(null) }
            }
        }

        val advert = BleWire.packAdvert(srv.psm, localName)
        val legacy = BleWire.fitsLegacy(advert)
        if (!legacy && (!a.isLeExtendedAdvertisingSupported || advert.size + 18 > a.leMaximumAdvertisingDataLength)) {
            return Timber.e("BleL2capTransport: advert of ${advert.size} bytes needs extended advertising")
        }
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(legacy)
            .setConnectable(true)
            .setScannable(legacy)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(BleWire.serviceUuid(serviceId)), advert)
            .build()
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) Timber.d("BleL2capTransport: advertising started as $localName")
                else Timber.e("BleL2capTransport: advertising failed, status $status")
            }
        }
        advertising = callback
        runCatching { a.bluetoothLeAdvertiser?.startAdvertisingSet(params, data, null, null, null, callback) }
            .onFailure { Timber.e(it, "BleL2capTransport: startAdvertising failed") }
    }

    override fun stopAdvertising() {
        advertising?.let { cb -> runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) } }
        advertising = null
        runCatching { server?.close() }
        server = null
    }

    // Discovery

    override fun startDiscovery(serviceId: String, star: Boolean) {
        stopDiscovery()
        val scanner = adapter?.bluetoothLeScanner ?: return Timber.e("BleL2capTransport: no LE scanner")
        val uuid = ParcelUuid(BleWire.serviceUuid(serviceId))
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = sighted(result, uuid)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { sighted(it, uuid) }
            override fun onScanFailed(errorCode: Int) { Timber.e("BleL2capTransport: scan failed, code $errorCode") }
        }
        val filter = ScanFilter.Builder().setServiceData(uuid, ByteArray(0)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        scanning = callback
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure { return Timber.e(it, "BleL2capTransport: startDiscovery failed") }
        main.postDelayed(sweep, SWEEP_MS)
    }

    override fun stopDiscovery() {
        scanning?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanning = null
        main.removeCallbacks(sweep)
    }

    /** Scan callbacks arrive on the main thread. */
    private fun sighted(result: ScanResult, uuid: ParcelUuid) {
        val (psm, name) = BleWire.parseAdvert(result.scanRecord?.getServiceData(uuid)) ?: return
        val address = result.device.address
        val prev = found[address]
        if (prev != null && prev.psm == psm && prev.name == name) {
            prev.lastSeen = SystemClock.elapsedRealtime()
            return
        }
        if (prev != null) onEndpointLost?.invoke(prev.id)
        val f = Found(newId(), result.device, psm, name)
        found[address] = f
        onEndpointFound?.invoke(f.id, name)
    }

    // Connections

    override fun requestConnection(localName: String, endpointId: String) {
        val f = found.values.firstOrNull { it.id == endpointId }
            ?: return Timber.e("BleL2capTransport: requestConnection to unknown $endpointId")
        if (links.containsKey(endpointId)) return
        pool.execute {
            val socket = try {
                f.device.createInsecureL2capChannel(f.psm).apply { connect() }
            } catch (e: Exception) {
                Timber.e(e, "BleL2capTransport: connect to $endpointId failed")
                main.post { onDisconnected?.invoke(endpointId) }
                return@execute
            }
            Link(endpointId, socket, initiator = true, remoteName = f.name).run(localName)
        }
    }

    override fun acceptConnection(endpointId: String) {
        val link = links[endpointId] ?: return Timber.w("BleL2capTransport: accept for unknown $endpointId")
        link.localAccepted = true
        link.enqueue(BleWire.message(T_ACCEPT))
        link.maybeConnected()
    }

    override fun rejectConnection(endpointId: String) {
        val link = links[endpointId] ?: return
        link.enqueue(BleWire.message(T_REJECT))
        runCatching { link.writer.execute { link.close(notify = !link.connected) } }
    }

    override fun disconnect(endpointId: String) {
        links[endpointId]?.close(notify = false)
    }

    override fun stopAllEndpoints() {
        stopAdvertising()
        stopDiscovery()
        links.values.forEach { it.close(notify = false) }
        found.clear()
    }

    override fun sendBytes(endpointId: String, data: ByteArray) {
        val link = links[endpointId]?.takeIf { it.connected }
            ?: return Timber.w("BleL2capTransport: sendBytes to unconnected $endpointId")
        if (data.size > BleWire.MAX_BYTES) return Timber.e("BleL2capTransport: ${data.size} bytes is over the payload cap")
        link.enqueue(BleWire.message(T_BYTES, data))
    }

    override fun sendStream(endpointId: String, inputStream: InputStream, lengthHint: Long): Boolean {
        val link = links[endpointId]?.takeIf { it.connected } ?: return false
        val streamId = link.nextStream.incrementAndGet()
        pool.execute {
            runCatching {
                inputStream.use { input ->
                    val buf = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) link.enqueueChunk(BleWire.chunk(streamId, buf, n))
                    }
                }
                link.enqueueChunk(BleWire.end(streamId))
            }.onFailure { Timber.e(it, "BleL2capTransport: stream to $endpointId failed") }
        }
        return true
    }

    private fun newId() = "b${nextId.incrementAndGet()}"

    private inner class Link(
        val id: String,
        private val socket: BluetoothSocket,
        private val initiator: Boolean,
        @Volatile var remoteName: String,
    ) {
        private val input = DataInputStream(BufferedInputStream(socket.inputStream))
        private val out = DataOutputStream(BufferedOutputStream(socket.outputStream, OUT_BUFFER))
        private lateinit var cipher: LinkCipher
        val writer = Executors.newSingleThreadExecutor { Thread(it, "ble-l2cap-$id").apply { isDaemon = true } }
        val nextStream = AtomicInteger()
        private val inbound = ConcurrentHashMap<Int, PipedOutputStream>()
        private val dropped: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        private val control = ConcurrentLinkedQueue<ByteArray>()
        private val chunks = ConcurrentLinkedQueue<ByteArray>()
        private val chunkRoom = Semaphore(QUEUED_CHUNKS)

        @Volatile var localAccepted = false
        @Volatile private var remoteAccepted = false
        @Volatile var connected = false
        @Volatile private var closed = false

        /** Runs on a pool thread until the channel closes. */
        fun run(localName: String?) {
            try {
                val session = BleWire.handshake(input, out, initiator)
                cipher = session.cipher
                if (localName != null) {
                    send(BleWire.message(T_HELLO, localName.toByteArray(Charsets.UTF_8)))
                } else {
                    val hello = cipher.open(BleWire.readBlob(input))
                    if (hello[0] != T_HELLO) throw IOException("expected hello")
                    remoteName = hello.copyOfRange(1, hello.size).toString(Charsets.UTF_8)
                }
                links[id] = this
                main.post {
                    onAuthToken?.invoke(id, session.token)
                    onConnectionInitiated?.invoke(id, remoteName)
                }
                while (!closed) dispatch(cipher.open(BleWire.readBlob(input)))
            } catch (e: Exception) {
                if (!closed) Timber.d(e, "BleL2capTransport: link $id ended")
            } finally {
                close(notify = true)
            }
        }

        private fun dispatch(msg: ByteArray) {
            when (msg[0]) {
                T_ACCEPT -> { remoteAccepted = true; maybeConnected() }
                T_REJECT -> throw IOException("rejected by peer")
                T_BYTES -> if (connected) {
                    val body = msg.copyOfRange(1, msg.size)
                    main.post { onPayloadReceived?.invoke(id, body) }
                }
                T_CHUNK -> if (connected) {
                    val streamId = ByteBuffer.wrap(msg, 1, 4).int
                    receiveChunk(streamId, msg)
                }
                T_END -> inbound.remove(ByteBuffer.wrap(msg, 1, 4).int)?.close()
            }
        }

        // ponytail: a stream reader slower than the radio stalls this link's inbound control; per-stream credits if that bites.
        private fun receiveChunk(streamId: Int, msg: ByteArray) {
            if (streamId in dropped) return
            val sink = inbound[streamId] ?: run {
                val callback = onStreamReceived
                if (callback == null) {
                    dropped.add(streamId)
                    return
                }
                val pipe = PipedOutputStream()
                val source = PipedInputStream(pipe, PIPE_BYTES)
                inbound[streamId] = pipe
                main.post { callback(id, source) }
                pipe
            }
            try {
                sink.write(msg, 5, msg.size - 5)
                // A JDK pipe's reader otherwise naps up to a second before it sees the bytes.
                sink.flush()
            } catch (_: IOException) {
                inbound.remove(streamId)
                dropped += streamId
            }
        }

        @Synchronized
        fun maybeConnected() {
            if (connected || !localAccepted || !remoteAccepted || closed) return
            connected = true
            Timber.i("BleL2capTransport: connected to $id (incoming=${!initiator})")
            main.post { onConnected?.invoke(id, remoteName, !initiator) }
        }

        /** Seal and write one message; only [run] before the link is listed, then only [writer], calls it. */
        private fun send(plain: ByteArray, flush: Boolean = true) = BleWire.writeBlob(out, cipher.seal(plain), flush)

        fun enqueue(plain: ByteArray) {
            runCatching { post(control, plain) }
        }

        /** Blocks while [QUEUED_CHUNKS] wait, so a stream never runs far ahead of the radio. */
        fun enqueueChunk(plain: ByteArray) {
            while (!chunkRoom.tryAcquire(1, TimeUnit.SECONDS)) if (closed) throw IOException("link closed")
            post(chunks, plain)
        }

        private fun post(queue: Queue<ByteArray>, plain: ByteArray) {
            queue += plain
            writer.execute(::drain)
        }

        /** One task per queued frame; control frames jump queued chunks, and a flush waits until both run dry. */
        private fun drain() {
            val plain = control.poll() ?: chunks.poll()?.also { chunkRoom.release() } ?: return
            runCatching { send(plain, flush = control.isEmpty() && chunks.isEmpty()) }
                .onFailure { Timber.w(it, "BleL2capTransport: send to $id failed") }
        }

        @Synchronized
        fun close(notify: Boolean) {
            if (closed) return
            closed = true
            links.remove(id, this)
            runCatching { socket.close() }
            writer.shutdown()
            inbound.values.forEach { runCatching { it.close() } }
            inbound.clear()
            if (notify) main.post { onDisconnected?.invoke(id) }
        }
    }

    private companion object {
        const val SWEEP_MS = 3_000L
        const val LOST_MS = 10_000L
        /** A control frame waits behind at most one chunk on the air; 32 KiB is ~0.2 s at LE rates. */
        const val CHUNK_BYTES = 32 * 1024
        const val QUEUED_CHUNKS = 4
        /** Holds a whole chunk frame, so its length prefix never goes out as a separate SDU. */
        const val OUT_BUFFER = 64 * 1024
        const val PIPE_BYTES = 256 * 1024
    }
}
