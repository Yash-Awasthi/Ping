package com.ping.app.room

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.ping.app.data.ProfileRepository
import com.ping.app.room.RoomProtocol.FileMeta
import com.ping.app.room.RoomProtocol.GuestShare
import com.ping.app.room.RoomProtocol.HOST_KEY
import com.ping.app.room.RoomProtocol.Manifest
import com.ping.app.service.NearbyTransport
import com.ping.app.service.Pairing
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

enum class RoomRole { HOST, GUEST }

enum class RoomPhase {
    IDLE,
    /** Host: advertising, guests may knock. */
    OPEN,
    /** Guest: looking for a room holding the same gesture. */
    SEARCHING,
    /** Guest: connected, waiting for the host to let us in. */
    WAITING_APPROVAL,
    IN_ROOM,
    DENIED,
    NO_ROOM,
    /** The host closed the room or the link dropped. */
    CLOSED,
}

/** [digits] is the code both phones show for this link; the host compares it with the guest. */
data class PendingGuest(val endpointId: String, val name: String, val digits: String = "")

data class Transfer(
    val status: Status,
    val bytes: Long = 0,
    val total: Long = 0,
    val path: String? = null,
) {
    enum class Status { QUEUED, RECEIVING, DONE, FAILED }
}

data class RoomState(
    val role: RoomRole? = null,
    val phase: RoomPhase = RoomPhase.IDLE,
    /** Guest only: the link code to read out to the host while waiting to be let in. */
    val linkDigits: String = "",
    /** Our owner key in the manifest: [HOST_KEY] for the host, the endpoint id the host gave us otherwise. */
    val myKey: String = "",
    /** Host only: guests that connected and wait for a decision. */
    val pending: List<PendingGuest> = emptyList(),
    val manifest: Manifest = Manifest(emptyList(), emptyList()),
    /** Files this phone offers to the room. */
    val shared: List<FileMeta> = emptyList(),
    val transfers: Map<String, Transfer> = emptyMap(),
)

/**
 * The file room: one host phone at the centre of a star, guests as spokes.
 *
 * Everyone shares only file names and sizes; bytes move on request. Guests join by holding
 * the host's gesture (the same token match as a swap) and the host must then let each guest
 * in, so a stranger who guesses the gesture still sees nothing. A guest's file goes to the
 * host first and is relayed onward, because guests only ever connect to the host.
 */
@Singleton
class RoomHub @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transport: NearbyTransport,
    private val profileRepo: ProfileRepository,
) {
    private class GuestInfo(var name: String, var approved: Boolean = false, var files: List<FileMeta> = emptyList())

    // Control state is mutated on this one thread; the maps are concurrent so stream readers can peek.
    private val controlThread = Executors.newSingleThreadExecutor { Thread(it, "room-hub").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + controlThread.asCoroutineDispatcher())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RoomState())
    val state: StateFlow<RoomState> = _state

    private val guests = ConcurrentHashMap<String, GuestInfo>()
    private val sharedOpeners = ConcurrentHashMap<String, () -> InputStream?>()
    private val wanted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val relay = ConcurrentHashMap<String, MutableSet<String>>()
    private val cache = ConcurrentHashMap<String, File>()
    private val digits = ConcurrentHashMap<String, String>()
    private val downloadLock = Any()
    private var nextLocalId = 1

    @Volatile private var code = ""
    @Volatile private var myName = ""
    @Volatile private var hostEndpoint: String? = null
    private var localAdvert = ""
    private var searchJob: Job? = null

    fun host(gestureCode: String) = begin(RoomRole.HOST, gestureCode)

    fun join(gestureCode: String) = begin(RoomRole.GUEST, gestureCode)

    private fun begin(role: RoomRole, gestureCode: String) {
        scope.launch {
            resetLocal()
            code = gestureCode
            myName = profileRepo.getOrCreate().displayName.trim().take(MAX_NAME)
                .ifBlank { if (role == RoomRole.HOST) "Host" else "Guest" }
            localAdvert = Pairing.advertisement(gestureCode, UUID.randomUUID().toString().take(8))
            wireCallbacks()
            if (role == RoomRole.HOST) {
                _state.value = RoomState(role = role, phase = RoomPhase.OPEN, myKey = HOST_KEY)
                transport.startAdvertising(localAdvert, SERVICE_ID, star = true)
            } else {
                _state.value = RoomState(role = role, phase = RoomPhase.SEARCHING)
                transport.startDiscovery(SERVICE_ID, star = true)
                searchJob = launch {
                    delay(SEARCH_MS)
                    if (_state.value.phase == RoomPhase.SEARCHING) {
                        _state.update { it.copy(phase = RoomPhase.NO_ROOM) }
                        stopRadio()
                    }
                }
            }
        }
    }

    /** Leaves or closes the room and forgets everything except files already downloaded. */
    fun leave() {
        scope.launch {
            stopRadio()
            resetLocal()
            _state.value = RoomState()
        }
    }

    private fun stopRadio() {
        runCatching {
            transport.stopAdvertising()
            transport.stopDiscovery()
            transport.stopAllEndpoints()
        }
    }

    private fun resetLocal() {
        searchJob?.cancel()
        runCatching {
            transport.onEndpointFound = null
            transport.onConnectionInitiated = null
            transport.onConnected = null
            transport.onPayloadReceived = null
            transport.onDisconnected = null
            transport.onStreamReceived = null
            transport.onAuthDigits = null
        }
        guests.clear()
        sharedOpeners.clear()
        wanted.clear()
        relay.clear()
        cache.clear()
        digits.clear()
        cacheDir().deleteRecursively()
        hostEndpoint = null
        nextLocalId = 1
    }

    // Sharing

    fun addFiles(uris: List<Uri>) {
        scope.launch {
            for (uri in uris) {
                val meta = describe(uri) ?: continue
                addShared(meta) { context.contentResolver.openInputStream(uri) }
            }
        }
    }

    /** Offers a file whose bytes come from [opener] each time someone asks for it. */
    internal fun addShared(meta: FileMeta, opener: () -> InputStream?) {
        scope.launch {
            if (sharedOpeners.size >= RoomProtocol.MAX_FILES_PER_MEMBER) return@launch
            val id = "f${nextLocalId++}"
            sharedOpeners[id] = opener
            _state.update { it.copy(shared = it.shared + meta.copy(id = id)) }
            sharedChanged()
        }
    }

    fun removeShared(localId: String) {
        scope.launch {
            sharedOpeners.remove(localId)
            _state.update { s -> s.copy(shared = s.shared.filterNot { it.id == localId }) }
            sharedChanged()
        }
    }

    private fun sharedChanged() {
        when (_state.value.role) {
            RoomRole.HOST -> broadcastManifest()
            RoomRole.GUEST -> sendManifestUp()
            null -> Unit
        }
    }

    private fun describe(uri: Uri): FileMeta? = runCatching {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        if (size < 0) size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        if (size < 0) null else FileMeta("", RoomProtocol.safeName(name ?: uri.lastPathSegment), size)
    }.getOrNull()

    // Host decisions

    fun decide(endpointId: String, allow: Boolean) {
        scope.launch {
            val g = guests[endpointId] ?: return@launch
            if (allow) {
                g.approved = true
                transport.sendBytes(endpointId, RoomProtocol.frameText(RoomProtocol.T_APPROVED, endpointId))
                refreshPending()
                broadcastManifest()
            } else {
                transport.sendBytes(endpointId, RoomProtocol.frame(RoomProtocol.T_DENIED))
                guests.remove(endpointId)
                digits.remove(endpointId)
                refreshPending()
                delay(DISCONNECT_GRACE_MS)
                transport.disconnect(endpointId)
            }
        }
    }

    private fun refreshPending() {
        val pending = guests.filterValues { !it.approved }.map { PendingGuest(it.key, it.value.name, digits[it.key].orEmpty()) }
        _state.update { it.copy(pending = pending) }
    }

    private fun broadcastManifest() {
        val s = _state.value
        val approved = guests.filterValues { it.approved }
        val manifest = RoomProtocol.merge(
            myName,
            s.shared,
            approved.map { GuestShare(it.key, it.value.name, it.value.files) },
        )
        _state.update { it.copy(manifest = manifest) }
        val frame = RoomProtocol.frameText(RoomProtocol.T_MANIFEST_DOWN, RoomProtocol.encodeManifest(manifest))
        approved.keys.forEach { transport.sendBytes(it, frame) }
    }

    private fun sendManifestUp() {
        val host = hostEndpoint ?: return
        if (_state.value.phase != RoomPhase.IN_ROOM) return
        transport.sendBytes(
            host,
            RoomProtocol.frameText(RoomProtocol.T_MANIFEST_UP, RoomProtocol.encodeMetas(_state.value.shared)),
        )
    }

    // Downloads

    /** Ask for [globalId]; the file arrives as a stream and lands in [RoomState.transfers]. */
    fun download(globalId: String) {
        scope.launch {
            val s = _state.value
            val file = s.manifest.files.firstOrNull { it.id == globalId } ?: return@launch
            if (file.ownerKey == s.myKey || s.transfers[globalId]?.status in ACTIVE) return@launch
            wanted += globalId
            setTransfer(globalId, Transfer(Transfer.Status.QUEUED, 0, file.size))
            if (s.role == RoomRole.HOST) serve(HOST_KEY, globalId)
            else hostEndpoint?.let { transport.sendBytes(it, RoomProtocol.frameText(RoomProtocol.T_REQUEST, globalId)) }
        }
    }

    /** Host: get [globalId] to [requester] (an endpoint id, or [HOST_KEY] for the host itself). */
    private fun serve(requester: String, globalId: String) {
        val file = _state.value.manifest.files.firstOrNull { it.id == globalId } ?: return
        val owner = file.ownerKey
        when {
            owner == HOST_KEY -> if (requester != HOST_KEY) sendShared(requester, globalId)
            cache[globalId]?.exists() == true -> deliver(requester, globalId, cache.getValue(globalId))
            guests.containsKey(owner) -> {
                val fresh = relay[globalId] == null
                relay.getOrPut(globalId) { ConcurrentHashMap.newKeySet() }.add(requester)
                if (fresh) {
                    transport.sendBytes(
                        owner,
                        RoomProtocol.frameText(RoomProtocol.T_PUSH, RoomProtocol.localIdOf(globalId)),
                    )
                }
            }
            else -> failTransfer(globalId)
        }
    }

    private fun deliver(requester: String, globalId: String, file: File) {
        if (requester == HOST_KEY) io.launch { finish(globalId, file) }
        else streamOut(requester, globalId, FileInputStream(file), file.length())
    }

    private fun sendShared(endpointId: String, globalId: String) {
        val localId = RoomProtocol.localIdOf(globalId)
        val meta = _state.value.shared.firstOrNull { it.id == localId }
        val input = sharedOpeners[localId]?.let { runCatching { it() }.getOrNull() }
        if (meta == null || input == null) return
        streamOut(endpointId, globalId, input, meta.size)
    }

    private fun streamOut(endpointId: String, globalId: String, body: InputStream, size: Long) {
        val stream = SequenceInputStream(ByteArrayInputStream(RoomProtocol.header(globalId)), body)
        if (!transport.sendStream(endpointId, stream, size)) runCatching { body.close() }
    }

    // Incoming

    private fun wireCallbacks() {
        transport.onEndpointFound = { endpointId, remoteName ->
            scope.launch {
                if (_state.value.role == RoomRole.GUEST && hostEndpoint == null &&
                    Pairing.matches(code, remoteName)
                ) {
                    hostEndpoint = endpointId
                    transport.requestConnection(localAdvert, endpointId)
                }
            }
        }
        transport.onAuthDigits = { endpointId, code -> digits[endpointId] = code }
        transport.onConnectionInitiated = { endpointId, remoteName ->
            scope.launch {
                val ok = Pairing.matches(code, remoteName) && when (_state.value.role) {
                    RoomRole.HOST -> guests.size < MAX_GUESTS
                    RoomRole.GUEST -> endpointId == hostEndpoint
                    null -> false
                }
                if (ok) transport.acceptConnection(endpointId) else transport.rejectConnection(endpointId)
            }
        }
        transport.onConnected = { endpointId, _, _ ->
            scope.launch {
                when (_state.value.role) {
                    RoomRole.HOST -> {
                        guests[endpointId] = GuestInfo(name = "…")
                        refreshPending()
                    }
                    RoomRole.GUEST -> if (endpointId == hostEndpoint) {
                        searchJob?.cancel()
                        transport.stopDiscovery()
                        _state.update {
                            it.copy(phase = RoomPhase.WAITING_APPROVAL, linkDigits = digits[endpointId].orEmpty())
                        }
                        transport.sendBytes(endpointId, RoomProtocol.frameText(RoomProtocol.T_HELLO, myName))
                    }
                    null -> Unit
                }
            }
        }
        transport.onDisconnected = { endpointId -> scope.launch { onGone(endpointId) } }
        transport.onPayloadReceived = { endpointId, data -> scope.launch { onMessage(endpointId, data) } }
        transport.onStreamReceived = { endpointId, stream -> io.launch { onStream(endpointId, stream) } }
    }

    private fun onGone(endpointId: String) {
        val s = _state.value
        if (s.role == RoomRole.HOST) {
            digits.remove(endpointId)
            val g = guests.remove(endpointId) ?: return
            relay.values.forEach { it.remove(endpointId) }
            relay.keys.filter { RoomProtocol.ownerOf(it) == endpointId }.forEach { gid ->
                if (relay.remove(gid)?.contains(HOST_KEY) == true) failTransfer(gid)
            }
            refreshPending()
            if (g.approved) broadcastManifest()
        } else if (endpointId == hostEndpoint && s.phase == RoomPhase.SEARCHING) {
            hostEndpoint = null
        } else if (endpointId == hostEndpoint && s.phase != RoomPhase.DENIED) {
            _state.update { st ->
                st.copy(
                    phase = RoomPhase.CLOSED,
                    transfers = st.transfers.mapValues { (_, t) ->
                        if (t.status in ACTIVE) t.copy(status = Transfer.Status.FAILED) else t
                    },
                )
            }
        }
    }

    private fun onMessage(endpointId: String, data: ByteArray) {
        if (data.isEmpty()) return
        val body = data.copyOfRange(1, data.size)
        val text = body.toString(Charsets.UTF_8)
        if (_state.value.role == RoomRole.HOST) hostMessage(endpointId, data[0], text) else guestMessage(endpointId, data[0], text)
    }

    private fun hostMessage(endpointId: String, type: Byte, text: String) {
        val g = guests[endpointId] ?: return
        when (type) {
            RoomProtocol.T_HELLO -> if (!g.approved) {
                g.name = RoomProtocol.safeName(text).take(MAX_NAME)
                refreshPending()
            }
            RoomProtocol.T_MANIFEST_UP -> if (g.approved) {
                g.files = RoomProtocol.decodeMetas(text)
                broadcastManifest()
            }
            RoomProtocol.T_REQUEST -> if (g.approved) serve(endpointId, text)
        }
    }

    private fun guestMessage(endpointId: String, type: Byte, text: String) {
        if (endpointId != hostEndpoint) return
        when (type) {
            RoomProtocol.T_APPROVED -> {
                _state.update { it.copy(phase = RoomPhase.IN_ROOM, myKey = text) }
                sendManifestUp()
            }
            RoomProtocol.T_DENIED -> _state.update { it.copy(phase = RoomPhase.DENIED) }
            RoomProtocol.T_MANIFEST_DOWN -> if (_state.value.phase == RoomPhase.IN_ROOM) {
                RoomProtocol.decodeManifest(text)?.let { m -> _state.update { it.copy(manifest = m) } }
            }
            RoomProtocol.T_PUSH -> if (_state.value.phase == RoomPhase.IN_ROOM) {
                val s = _state.value
                if (s.shared.any { it.id == text }) sendShared(endpointId, RoomProtocol.globalId(s.myKey, text))
            }
        }
    }

    private fun onStream(endpointId: String, stream: InputStream) {
        stream.use { input ->
            val gid = RoomProtocol.readHeader(input) ?: return
            val s = _state.value
            val file = s.manifest.files.firstOrNull { it.id == gid } ?: return
            if (s.role == RoomRole.GUEST) {
                if (endpointId != hostEndpoint || gid !in wanted) return
                val dest = downloadFile(file.name)
                if (receive(input, dest, gid, file.size)) finish(gid, dest)
            } else {
                // The host only takes bytes from the guest that owns the file, and only if someone asked.
                if (endpointId != file.ownerKey || relay[gid].isNullOrEmpty()) return
                val dest = cacheDir().resolve(UUID.randomUUID().toString())
                if (receive(input, dest, gid, file.size)) {
                    cache[gid] = dest
                    relay.remove(gid)?.forEach { deliver(it, gid, dest) }
                } else {
                    relay.remove(gid)
                }
            }
        }
    }

    /** Copies exactly [size] bytes into [dest]; anything else is a failed transfer. */
    private fun receive(input: InputStream, dest: File, gid: String, size: Long): Boolean {
        val hostWaiting = _state.value.role == RoomRole.GUEST || relay[gid]?.contains(HOST_KEY) == true
        if (hostWaiting) setTransfer(gid, Transfer(Transfer.Status.RECEIVING, 0, size))
        val part = File(dest.path + ".part")
        val ok = runCatching {
            var count = 0L
            var lastReport = 0L
            part.outputStream().use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    count += n
                    if (count > size) error("more bytes than announced")
                    out.write(buf, 0, n)
                    if (hostWaiting && count - lastReport >= REPORT_EVERY) {
                        lastReport = count
                        setTransfer(gid, Transfer(Transfer.Status.RECEIVING, count, size))
                    }
                }
            }
            check(count == size) { "stream ended at $count of $size" }
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Timber.w(it, "Room transfer %s failed", gid) }.isSuccess
        if (!ok) {
            part.delete()
            dest.delete()
            if (hostWaiting) failTransfer(gid)
        }
        return ok
    }

    private fun finish(gid: String, file: File) {
        // Host copying a relayed file out of the cache; guests already wrote straight to the download.
        val name = _state.value.manifest.files.firstOrNull { it.id == gid }?.name ?: "file"
        val dest = if (file.parentFile == cacheDir()) downloadFile(name).also { file.copyTo(it, overwrite = true) } else file
        wanted -= gid
        setTransfer(gid, Transfer(Transfer.Status.DONE, dest.length(), dest.length(), dest.path))
    }

    private fun failTransfer(gid: String) {
        wanted -= gid
        setTransfer(gid, Transfer(Transfer.Status.FAILED))
    }

    private fun setTransfer(gid: String, t: Transfer) =
        _state.update { it.copy(transfers = it.transfers + (gid to t)) }

    // Files

    private fun cacheDir() = File(context.cacheDir, "room").apply { mkdirs() }

    private fun downloadDir() = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir)
        .resolve("Ping").apply { mkdirs() }

    /**
     * A path inside [downloadDir] that no earlier or concurrent download uses. The empty file
     * created here holds the name until the transfer replaces it or fails.
     */
    private fun downloadFile(name: String): File = synchronized(downloadLock) {
        val dir = downloadDir()
        val taken = dir.list()?.map { it.removeSuffix(".part") }?.toSet().orEmpty()
        File(dir, RoomProtocol.uniqueName(RoomProtocol.safeName(name), taken)).also { it.createNewFile() }
    }

    private companion object {
        const val SERVICE_ID = "com.ping.app.room"
        const val MAX_GUESTS = 7
        const val MAX_NAME = 30
        const val SEARCH_MS = 30_000L
        const val DISCONNECT_GRACE_MS = 500L
        const val BUFFER = 32 * 1024
        const val REPORT_EVERY = 128 * 1024L
        val ACTIVE = setOf(Transfer.Status.QUEUED, Transfer.Status.RECEIVING)
    }
}
