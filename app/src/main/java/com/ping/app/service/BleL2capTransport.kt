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
import timber.log.Timber
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
                // The discovered id lets the swap see a peer's request as the endpoint it already matched.
                val address = socket.remoteDevice.address
                val f = found[address]
                val id = f?.id ?: newId()
                val claim = claim(id, address, localName = null, remoteName = f?.name)
                if (claim == null) {
                    Timber.w("BleL2capTransport: refusing a second or excess link from $address")
                    runCatching { socket.close() }
                    continue
                }
                claim.socket = socket
                pool.execute { start(id, claim, socket, initiator = false, remoteName = "", localName = null) }
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

    /** One per peer address, from request or accept until the link closes. */
    private class Claim(val address: String, val localName: String?) {
        @Volatile var socket: BluetoothSocket? = null
        @Volatile var link: BleLink? = null
        @Volatile var cancelled = false

        fun cancel() {
            cancelled = true
            link?.close(notify = false)
            runCatching { socket?.close() }
        }
    }

    private val claims = ConcurrentHashMap<String, Claim>()

    /**
     * Claims [id] for [address], or null when it already has a link or [MAX_LINKS] are up. An incoming
     * link crossing our own request wins only when [Pairing.shouldInitiate] picks the peer, as both sides agree.
     */
    private fun claim(id: String, address: String, localName: String?, remoteName: String?): Claim? = synchronized(claims) {
        val prev = claims.entries.firstOrNull { it.value.address == address }
        if (prev != null) {
            val ours = prev.value.localName
            if (localName != null || ours == null || remoteName == null || Pairing.shouldInitiate(ours, remoteName)) return null
            claims.remove(prev.key, prev.value)
            prev.value.cancel()
        } else if (claims.size >= MAX_LINKS) {
            return null
        }
        Claim(address, localName).also { claims[id] = it }
    }

    private fun start(id: String, claim: Claim, socket: BluetoothSocket, initiator: Boolean, remoteName: String, localName: String?) {
        val link = BleLink(id, socket.inputStream, socket.outputStream, initiator, remoteName, host)
        claim.link = link
        if (claim.cancelled) link.close(notify = false)
        link.run(localName)
    }

    private fun link(endpointId: String) = claims[endpointId]?.link

    private val host = object : BleLink.Host {
        override fun initiated(link: BleLink, token: ByteArray) {
            main.post {
                onAuthToken?.invoke(link.id, token)
                onConnectionInitiated?.invoke(link.id, link.remoteName)
            }
        }

        override fun connected(link: BleLink) {
            Timber.i("BleL2capTransport: connected to ${link.id} (incoming=${link.incoming})")
            main.post { onConnected?.invoke(link.id, link.remoteName, link.incoming) }
        }

        override fun bytes(link: BleLink, body: ByteArray) {
            main.post { onPayloadReceived?.invoke(link.id, body) }
        }

        override fun stream(link: BleLink): ((InputStream) -> Unit)? =
            onStreamReceived?.let { cb -> { s: InputStream -> main.post { cb(link.id, s) } } }

        override fun closed(link: BleLink, notify: Boolean) {
            claims[link.id]?.takeIf { it.link === link }?.let { claims.remove(link.id, it) }
            if (notify) main.post { onDisconnected?.invoke(link.id) }
        }
    }

    override fun requestConnection(localName: String, endpointId: String) {
        val f = found.values.firstOrNull { it.id == endpointId }
            ?: return Timber.e("BleL2capTransport: requestConnection to unknown $endpointId")
        val claim = claim(endpointId, f.device.address, localName, f.name) ?: return
        pool.execute {
            val socket = try {
                f.device.createInsecureL2capChannel(f.psm).also {
                    claim.socket = it
                    if (claim.cancelled) it.close()
                    it.connect()
                }
            } catch (e: Exception) {
                claims.remove(endpointId, claim)
                if (!claim.cancelled) {
                    Timber.e(e, "BleL2capTransport: connect to $endpointId failed")
                    main.post { onDisconnected?.invoke(endpointId) }
                }
                return@execute
            }
            start(endpointId, claim, socket, initiator = true, remoteName = f.name, localName = localName)
        }
    }

    override fun acceptConnection(endpointId: String) {
        val link = link(endpointId) ?: return Timber.w("BleL2capTransport: accept for unknown $endpointId")
        link.accept()
    }

    override fun rejectConnection(endpointId: String) {
        link(endpointId)?.reject()
    }

    override fun disconnect(endpointId: String) {
        claims.remove(endpointId)?.cancel()
    }

    override fun stopAllEndpoints() {
        stopAdvertising()
        stopDiscovery()
        synchronized(claims) { claims.values.toList().also { claims.clear() } }.forEach { it.cancel() }
        found.clear()
    }

    override fun sendBytes(endpointId: String, data: ByteArray) {
        val link = link(endpointId)?.takeIf { it.connected }
            ?: return Timber.w("BleL2capTransport: sendBytes to unconnected $endpointId")
        if (data.size > BleWire.MAX_BYTES) return Timber.e("BleL2capTransport: ${data.size} bytes is over the payload cap")
        link.sendBytes(data)
    }

    override fun sendStream(endpointId: String, inputStream: InputStream, lengthHint: Long): Boolean {
        val link = link(endpointId)?.takeIf { it.connected } ?: return false
        pool.execute { link.sendStream(inputStream) }
        return true
    }

    private fun newId() = "b${nextId.incrementAndGet()}"

    private companion object {
        const val SWEEP_MS = 3_000L
        const val LOST_MS = 10_000L
        const val MAX_LINKS = 8
    }
}
