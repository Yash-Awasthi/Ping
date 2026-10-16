package com.ping.app.room

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.ping.app.data.ProfileRepository
import com.ping.app.room.RoomProtocol.FileMeta
import com.ping.app.room.RoomProtocol.GuestShare
import com.ping.app.room.RoomProtocol.HOST_KEY
import com.ping.app.room.RoomProtocol.Manifest
import com.ping.app.room.RoomProtocol.Note
import com.ping.app.room.RoomProtocol.RoomFile
import com.ping.app.service.NearbyTransport
import com.ping.app.service.Pairing
import com.ping.app.utils.Pake
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
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

/** A guest that proved the room's gesture and waits for the host's decision. */
data class PendingGuest(val endpointId: String, val name: String)

data class Transfer(
    val status: Status,
    val bytes: Long = 0,
    val total: Long = 0,
    val path: String? = null,
    /** When bytes started arriving, for speed and time left. */
    val startedAt: Long = 0,
    /** Bytes already on disk when this attempt started; a failed transfer with bytes can resume. */
    val from: Long = 0,
) {
    enum class Status { QUEUED, RECEIVING, DONE, FAILED }
}

data class RoomState(
    val role: RoomRole? = null,
    val phase: RoomPhase = RoomPhase.IDLE,
    /** Our owner key in the manifest: [HOST_KEY] for the host, the endpoint id the host gave us otherwise. */
    val myKey: String = "",
    /** Host only: guests that connected and wait for a decision. */
    val pending: List<PendingGuest> = emptyList(),
    val manifest: Manifest = Manifest(emptyList(), emptyList()),
    /** Files this phone offers to the room. */
    val shared: List<FileMeta> = emptyList(),
    val transfers: Map<String, Transfer> = emptyMap(),
    /** Guest, with [RoomPhase.NO_ROOM]: a room was reached but its gesture check failed. */
    val gestureMismatch: Boolean = false,
    /** Host: too many failed gesture checks, so the room stopped advertising. */
    val guessing: Boolean = false,
    val notes: List<Note> = emptyList(),
)

/**
 * The file room: one host phone at the centre of a star, guests as spokes.
 *
 * Everyone shares only file names and sizes; bytes move on request. Guests join by holding
 * the host's gesture, proved by the same [Pake] exchange as a swap, and the host must then let
 * each guest in, so a stranger who guesses the gesture still sees nothing. A guest's file goes
 * to the host first and is relayed onward, because guests only ever connect to the host.
 */
@Singleton
class RoomHub @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transport: NearbyTransport,
    private val profileRepo: ProfileRepository,
) {
    private class GuestInfo(var name: String, var approved: Boolean = false, var files: List<FileMeta> = emptyList()) {
        val pages = RoomProtocol.PageSet<FileMeta>()
    }

    /** One gesture check per connection; [verified] once the peer's tag checked out. */
    private class Link(val session: Pake.Session, val context: ByteArray) {
        var keys: Pake.Keys? = null
        var verified = false
    }

    // Control state is mutated on this one thread; the maps are concurrent so stream readers can peek.
    private val controlThread = Executors.newSingleThreadExecutor { Thread(it, "room-hub").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + controlThread.asCoroutineDispatcher())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RoomState())
    val state: StateFlow<RoomState> = _state

    private val guests = ConcurrentHashMap<String, GuestInfo>()
    private val sharedOpeners = ConcurrentHashMap<String, () -> InputStream?>()
    private val wanted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Files asked for while [MAX_PARALLEL] were already on their way, in the order asked; control thread only. */
    private val backlog = ArrayDeque<String>()
    /** Host: guest files on their way into the cache, and who waits for them from which offset. */
    private val relay = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val cache = ConcurrentHashMap<String, File>()
    /** Host: relayed files still landing in the cache, which requesters already read as they grow. */
    private val landing = ConcurrentHashMap<String, Landing>()
    private val linkTokens = ConcurrentHashMap<String, ByteArray>()
    private val links = ConcurrentHashMap<String, Link>()
    private val earlyShares = ConcurrentHashMap<String, ByteArray>()
    /** Guest: where each requested file lands; its `.part` survives a failed attempt for a resume. */
    private val partials = ConcurrentHashMap<String, File>()
    /** Files that already fell back from a resume to a full download this attempt. */
    private val restarted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Guest: rooms whose gesture check failed, never retried this search. */
    private val tried: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** When each wanted or relayed file last made progress, for the idle timeout. */
    private val lastBytes = ConcurrentHashMap<String, Long>()
    /** Streams being read per file; a result only counts while its stream is still the one listed. */
    private val inflight = ConcurrentHashMap<String, InputStream>()
    /** Bumped on every reset so work from an earlier room drops its results. */
    @Volatile private var epoch = 0
    private var session = SupervisorJob()
    private val downloadLock = Any()
    private var nextLocalId = 1
    /** Revision of the listing this phone last sent, so receivers can tell its pages apart. */
    private var listingRev = 0
    private var manifestPages = RoomProtocol.PageSet<RoomFile>()

    @Volatile private var code = ""
    @Volatile private var myName = ""
    @Volatile private var hostEndpoint: String? = null
    private var localAdvert = ""
    private var searchJob: Job? = null
    /** Rooms already heard while the guest was still making the gesture; endpointId to advertisement. */
    private val seen = ConcurrentHashMap<String, String>()

    internal var idleMs = IDLE_MS
    internal var relayCap = RELAY_CAP

    /** Runs everything already queued on the control thread, so tests can assert on what did not happen. */
    internal fun flush() = runBlocking { scope.launch {}.join() }

    /** [flush], after every stream being read has finished. */
    internal fun drain() = runBlocking {
        io.coroutineContext.job.children.toList().forEach { it.join() }
        scope.launch {}.join()
    }

    fun host(gestureCode: String) = begin(RoomRole.HOST, gestureCode)

    fun join(gestureCode: String) = begin(RoomRole.GUEST, gestureCode)

    /** A guest starts listening for rooms before the gesture locks, so joining is near-instant. */
    fun prewarm() {
        scope.launch {
            if (_state.value.role != null) return@launch
            seen.clear()
            transport.onEndpointFound = { id, name -> seen[id] = name }
            transport.onEndpointLost = { id -> seen.remove(id) }
            transport.startDiscovery(SERVICE_ID, star = true)
        }
    }

    fun cancelPrewarm() {
        scope.launch {
            if (_state.value.role != null) return@launch
            seen.clear()
            runCatching {
                transport.onEndpointFound = null
                transport.onEndpointLost = null
                transport.stopDiscovery()
            }
        }
    }

    private fun begin(role: RoomRole, gestureCode: String) {
        scope.launch {
            resetLocal()
            code = gestureCode
            myName = profileRepo.getOrCreate().displayName.trim().take(MAX_NAME)
                .ifBlank { if (role == RoomRole.HOST) "Host" else "Guest" }
            localAdvert = Pairing.advertisement(gestureCode, UUID.randomUUID().toString().take(8))
            wireCallbacks()
            launch(session) {
                while (true) {
                    delay(IDLE_TICK_MS)
                    checkIdle()
                }
            }
            if (role == RoomRole.HOST) {
                _state.value = RoomState(role = role, phase = RoomPhase.OPEN, myKey = HOST_KEY)
                transport.startAdvertising(localAdvert, SERVICE_ID, star = true)
            } else {
                _state.value = RoomState(role = role, phase = RoomPhase.SEARCHING)
                transport.startDiscovery(SERVICE_ID, star = true)
                knockNext()
                searchJob = launch(session) {
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
            io.coroutineContext.cancelChildren()
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
        epoch++
        session.cancel()
        session = SupervisorJob()
        runCatching {
            transport.onEndpointFound = null
            transport.onConnectionInitiated = null
            transport.onConnected = null
            transport.onPayloadReceived = null
            transport.onDisconnected = null
            transport.onStreamReceived = null
            transport.onAuthToken = null
            transport.onEndpointLost = null
        }
        guests.clear()
        sharedOpeners.clear()
        wanted.clear()
        backlog.clear()
        relay.clear()
        cache.clear()
        landing.values.forEach { it.end(false) }
        landing.clear()
        inflight.values.forEach { runCatching { it.close() } }
        inflight.clear()
        lastBytes.clear()
        linkTokens.clear()
        links.clear()
        earlyShares.clear()
        tried.clear()
        partials.values.forEach { partOf(it).delete() }
        partials.clear()
        restarted.clear()
        cacheDir().deleteRecursively()
        hostEndpoint = null
        nextLocalId = 1
        manifestPages = RoomProtocol.PageSet()
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

    /**
     * Offers every file under a picked folder, subfolders included, up to the per-member cap.
     * Each file keeps its folder path, starting with the picked folder's own name.
     */
    fun addFolder(tree: Uri) {
        scope.launch {
            val files = withContext(Dispatchers.IO) { listTree(tree) }
            files.forEach { (doc, meta) -> addShared(meta) { context.contentResolver.openInputStream(doc) } }
        }
    }

    private fun listTree(tree: Uri): List<Pair<Uri, FileMeta>> {
        val out = mutableListOf<Pair<Uri, FileMeta>>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val rootName = runCatching {
            context.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), columns, null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(1) else null }
        }.getOrNull()
        val pending = ArrayDeque(listOf(rootId to RoomProtocol.childPath("", rootName)))
        while (pending.isNotEmpty() && out.size < RoomProtocol.MAX_FILES_PER_MEMBER) {
            val (dirId, path) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
            runCatching {
                context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                    while (c.moveToNext() && out.size < RoomProtocol.MAX_FILES_PER_MEMBER) {
                        val id = c.getString(0)
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending += id to RoomProtocol.childPath(path, c.getString(1))
                            continue
                        }
                        val size = if (c.isNull(3)) continue else c.getLong(3)
                        out += DocumentsContract.buildDocumentUriUsingTree(tree, id) to
                            FileMeta("", RoomProtocol.safeName(c.getString(1)), size, path)
                    }
                }
            }.onFailure { Timber.w(it, "Could not list folder") }
        }
        return out
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
                refreshPending()
                delay(DISCONNECT_GRACE_MS)
                transport.disconnect(endpointId)
            }
        }
    }

    /** Host: let every waiting guest in at once. */
    fun approveAll() {
        scope.launch { guests.filterValues { !it.approved }.keys.forEach { decide(it, true) } }
    }

    /** Guest or host: queue every room file not already here or on its way. */
    fun downloadAll() {
        scope.launch {
            val s = _state.value
            s.manifest.files
                .filter { it.ownerKey != s.myKey && s.transfers[it.id]?.status !in ACTIVE + Transfer.Status.DONE }
                .forEach { download(it.id) }
        }
    }

    private fun refreshPending() {
        val pending = guests.filter { !it.value.approved && links[it.key]?.verified == true }
            .map { PendingGuest(it.key, it.value.name) }
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
        val frames = RoomProtocol.manifestPages(manifest, ++listingRev)
            .map { RoomProtocol.frameText(RoomProtocol.T_MANIFEST_DOWN, it) }
        approved.keys.forEach { id -> frames.forEach { transport.sendBytes(id, it) } }
    }

    private fun sendManifestUp() {
        val host = hostEndpoint ?: return
        if (_state.value.phase != RoomPhase.IN_ROOM) return
        RoomProtocol.metaPages(_state.value.shared, ++listingRev)
            .forEach { transport.sendBytes(host, RoomProtocol.frameText(RoomProtocol.T_MANIFEST_UP, it)) }
    }

    // Downloads

    /**
     * Ask for [globalId]; the file arrives as a stream and lands in [RoomState.transfers]. After a
     * failed attempt this resumes from the bytes already kept.
     */
    fun download(globalId: String) {
        scope.launch {
            val s = _state.value
            val file = s.manifest.files.firstOrNull { it.id == globalId } ?: return@launch
            if (file.ownerKey == s.myKey || s.transfers[globalId]?.status in ACTIVE) return@launch
            restarted -= globalId
            if (wanted.size < MAX_PARALLEL) return@launch fetch(file)
            backlog += globalId
            setTransfer(globalId, Transfer(Transfer.Status.QUEUED, keptBytes(globalId), file.size))
        }
    }

    /** Starts held-back downloads while a slot is free; a file that left the room fails instead. */
    private fun pump() {
        while (wanted.size < MAX_PARALLEL) {
            val gid = backlog.removeFirstOrNull() ?: return
            _state.value.manifest.files.firstOrNull { it.id == gid }?.let(::fetch) ?: failTransfer(gid)
        }
    }

    private fun fetch(file: RoomFile) {
        val gid = file.id
        wanted += gid
        touch(gid)
        if (_state.value.role == RoomRole.HOST) {
            setTransfer(gid, Transfer(Transfer.Status.QUEUED, keptBytes(gid), file.size))
            serve(HOST_KEY, gid, 0)
            return
        }
        val dest = partials.getOrPut(gid) { downloadFile(file.name, file.path) }
        val offset = freshPart(dest, file.size).length()
        setTransfer(gid, Transfer(Transfer.Status.QUEUED, offset, file.size))
        hostEndpoint?.let {
            transport.sendBytes(it, RoomProtocol.frameText(RoomProtocol.T_REQUEST, RoomProtocol.request(gid, offset)))
        }
    }

    /** Host: get [globalId] from byte [offset] to [requester] (an endpoint id, or [HOST_KEY] for the host itself). */
    private fun serve(requester: String, globalId: String, offset: Long) {
        val file = _state.value.manifest.files.firstOrNull { it.id == globalId } ?: return refuse(requester, globalId)
        val owner = file.ownerKey
        when {
            owner == HOST_KEY -> if (requester != HOST_KEY) sendShared(requester, globalId, offset)
            cache[globalId]?.exists() == true -> deliver(requester, globalId, cache.getValue(globalId), offset)
            requester != HOST_KEY && landing[globalId] != null ->
                forward(requester, globalId, landing.getValue(globalId), file.size, offset)
            guests.containsKey(owner) -> {
                val fresh = relay[globalId] == null
                relay.getOrPut(globalId) { ConcurrentHashMap() }[requester] = offset
                if (fresh) push(file)
            }
            else -> refuse(requester, globalId)
        }
    }

    /** Host: [gid] will not reach [who], which frees the download slot it holds. */
    private fun refuse(who: String, gid: String) {
        if (who == HOST_KEY) failTransfer(gid)
        else transport.sendBytes(who, RoomProtocol.frameText(RoomProtocol.T_FAILED, gid))
    }

    /**
     * Host: ask a guest for its file, resuming whatever part of it the cache already holds. Refused
     * when the cache would pass [relayCap] or the disk, counting what other relays still owe.
     */
    private fun push(file: RoomFile) {
        val offset = freshPart(cacheFile(file.id), file.size).length()
        val sizes = _state.value.manifest.files.associate { it.id to it.size }
        val owed = relay.keys.sumOf { gid -> ((sizes[gid] ?: 0) - partOf(cacheFile(gid)).length()).coerceAtLeast(0) }
        val onDisk = cacheDir().listFiles().orEmpty().sumOf { it.length() }
        if (onDisk + owed > relayCap || cacheDir().usableSpace <= owed) return relayFailed(file.id)
        touch(file.id)
        transport.sendBytes(
            file.ownerKey,
            RoomProtocol.frameText(RoomProtocol.T_PUSH, RoomProtocol.request(RoomProtocol.localIdOf(file.id), offset)),
        )
    }

    /** Host: a relayed file will not come; everyone waiting for it hears so. */
    private fun relayFailed(gid: String) {
        relay.remove(gid)?.keys?.forEach { refuse(it, gid) }
    }

    /** Host: stream a relayed file to [who] while it still lands; a part already gone fails instead. */
    private fun forward(who: String, gid: String, growing: Landing, size: Long, from: Long) {
        val input = runCatching { growing.open() }.getOrNull() ?: return refuse(who, gid)
        streamOut(who, gid, input, size, from)
    }

    private fun deliver(requester: String, globalId: String, file: File, offset: Long) {
        val e = epoch
        if (requester == HOST_KEY) io.launch { if (e == epoch) finish(globalId, file) }
        else streamOut(requester, globalId, FileInputStream(file), file.length(), offset)
    }

    /** Sends our own file; when it cannot, the other end hears so, as either end holds a slot until it does. */
    private fun sendShared(endpointId: String, globalId: String, offset: Long) {
        val localId = RoomProtocol.localIdOf(globalId)
        val meta = _state.value.shared.firstOrNull { it.id == localId }
        val input = sharedOpeners[localId]?.let { runCatching { it() }.getOrNull() }
        if (meta == null || input == null) {
            transport.sendBytes(endpointId, RoomProtocol.frameText(RoomProtocol.T_FAILED, globalId))
            return
        }
        streamOut(endpointId, globalId, input, meta.size, offset)
    }

    private fun streamOut(endpointId: String, globalId: String, body: InputStream, size: Long, offset: Long) {
        val skip = offset.coerceIn(0, size)
        val stream = SequenceInputStream(
            ByteArrayInputStream(RoomProtocol.header(globalId, skip)),
            RoomProtocol.withDigest(body, skip),
        )
        if (!transport.sendStream(endpointId, stream, size - skip)) {
            runCatching { body.close() }
            transport.sendBytes(endpointId, RoomProtocol.frameText(RoomProtocol.T_FAILED, globalId))
        }
    }

    // Incoming

    private fun wireCallbacks() {
        transport.onEndpointLost = { id -> seen.remove(id) }
        transport.onEndpointFound = { endpointId, remoteName ->
            seen[endpointId] = remoteName
            scope.launch { if (_state.value.role == RoomRole.GUEST) knockNext() }
        }
        transport.onAuthToken = { endpointId, token -> linkTokens[endpointId] = token }
        transport.onConnectionInitiated = { endpointId, remoteName ->
            scope.launch {
                val ok = Pairing.matches(code, remoteName) && when (_state.value.role) {
                    RoomRole.HOST -> guests.size < RoomProtocol.MAX_GUESTS
                    RoomRole.GUEST -> endpointId == hostEndpoint && endpointId !in tried
                    null -> false
                }
                if (ok) transport.acceptConnection(endpointId) else transport.rejectConnection(endpointId)
            }
        }
        transport.onConnected = { endpointId, remoteName, _ ->
            scope.launch {
                when (_state.value.role) {
                    RoomRole.HOST -> guests[endpointId] = GuestInfo(name = "…")
                    RoomRole.GUEST -> if (endpointId != hostEndpoint) return@launch
                    null -> return@launch
                }
                val link = Link(Pake.Session(Pake.generator(code)), Pake.context(localAdvert, remoteName, linkTokens[endpointId]))
                links[endpointId] = link
                transport.sendBytes(endpointId, RoomProtocol.frame(RoomProtocol.T_SHARE, link.session.share))
                earlyShares.remove(endpointId)?.let { onShare(endpointId, link, it) }
            }
        }
        transport.onDisconnected = { endpointId -> scope.launch { onGone(endpointId) } }
        transport.onPayloadReceived = { endpointId, data -> scope.launch { onMessage(endpointId, data) } }
        transport.onStreamReceived = { endpointId, stream -> io.launch { onStream(endpointId, stream) } }
    }

    /** Guest: connect to the first room in our gesture's bucket we have not already failed with. */
    private fun knockNext() {
        if (hostEndpoint != null || _state.value.phase != RoomPhase.SEARCHING) return
        val (id, _) = Pairing.firstMatch(code, seen, tried) ?: return
        hostEndpoint = id
        transport.requestConnection(localAdvert, id)
    }

    private fun onShare(endpointId: String, link: Link, share: ByteArray) {
        if (link.keys != null) return
        val keys = link.session.finish(share, link.context) ?: return gestureFailed(endpointId)
        link.keys = keys
        transport.sendBytes(endpointId, RoomProtocol.frame(RoomProtocol.T_CONFIRM, keys.myTag))
    }

    private fun onConfirm(endpointId: String, link: Link, tag: ByteArray) {
        val keys = link.keys ?: return
        if (link.verified) return
        if (!keys.verify(tag)) return gestureFailed(endpointId)
        link.verified = true
        if (_state.value.role == RoomRole.HOST) {
            refreshPending()
        } else {
            searchJob?.cancel()
            transport.stopDiscovery()
            _state.update { it.copy(phase = RoomPhase.WAITING_APPROVAL) }
            transport.sendBytes(endpointId, RoomProtocol.frameText(RoomProtocol.T_HELLO, myName))
        }
    }

    /** The peer does not hold this room's gesture, or is guessing it: hang up, and a guest tries the next room. */
    private fun gestureFailed(endpointId: String) {
        Timber.w("Gesture check with %s failed", endpointId)
        links.remove(endpointId)
        linkTokens.remove(endpointId)
        earlyShares.remove(endpointId)
        runCatching { transport.disconnect(endpointId) }
        if (_state.value.role == RoomRole.HOST) {
            guests.remove(endpointId)
            refreshPending()
        } else if (endpointId == hostEndpoint) {
            hostEndpoint = null
            tried += endpointId
            if (tried.size >= MAX_JOIN_ATTEMPTS) {
                _state.update { it.copy(phase = RoomPhase.NO_ROOM) }
                stopRadio()
            } else {
                knockNext()
            }
        }
    }

    private fun onGone(endpointId: String) {
        val s = _state.value
        links.remove(endpointId)
        linkTokens.remove(endpointId)
        earlyShares.remove(endpointId)
        if (s.role == RoomRole.HOST) {
            val g = guests.remove(endpointId) ?: return
            relay.values.forEach { it.remove(endpointId) }
            relay.keys.filter { RoomProtocol.ownerOf(it) == endpointId }.forEach(::relayFailed)
            refreshPending()
            if (g.approved) broadcastManifest()
        } else if (endpointId == hostEndpoint && s.phase == RoomPhase.SEARCHING) {
            // Hung up before the gesture checked out: the host did not take our gesture.
            hostEndpoint = null
            tried += endpointId
            if (tried.size >= MAX_JOIN_ATTEMPTS) {
                _state.update { it.copy(phase = RoomPhase.NO_ROOM) }
                stopRadio()
            } else {
                knockNext()
            }
        } else if (endpointId == hostEndpoint && s.phase != RoomPhase.DENIED) {
            backlog.clear()
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
        val link = links[endpointId]
        when (data[0]) {
            RoomProtocol.T_SHARE -> {
                if (link == null) earlyShares[endpointId] = body else onShare(endpointId, link, body)
                return
            }
            RoomProtocol.T_CONFIRM -> {
                link?.let { onConfirm(endpointId, it, body) }
                return
            }
        }
        if (link?.verified != true) return
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
                val listing = RoomProtocol.decodeMetaPage(text)?.let(g.pages::add) ?: return
                g.files = listing.files.take(RoomProtocol.MAX_FILES_PER_MEMBER)
                broadcastManifest()
            }
            RoomProtocol.T_REQUEST -> if (g.approved) {
                RoomProtocol.parseRequest(text)?.let { (gid, offset) -> serve(endpointId, gid, offset) }
            }
            RoomProtocol.T_FAILED -> if (g.approved && RoomProtocol.ownerOf(text) == endpointId) relayFailed(text)
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
                val listing = RoomProtocol.decodeManifestPage(text)?.let(manifestPages::add) ?: return
                val m = Manifest(listing.files.take(RoomProtocol.MAX_ROOM_FILES), listing.members)
                _state.update { it.copy(manifest = m) }
            }
            RoomProtocol.T_PUSH -> if (_state.value.phase == RoomPhase.IN_ROOM) {
                val s = _state.value
                val (localId, offset) = RoomProtocol.parseRequest(text) ?: return
                sendShared(endpointId, RoomProtocol.globalId(s.myKey, localId), offset)
            }
            RoomProtocol.T_FAILED -> if (text in wanted) failTransfer(text)
        }
    }

    /** Runs [block] on the control thread unless the room was reset since [e]. */
    private fun onControl(e: Int, block: () -> Unit) {
        scope.launch { if (e == epoch) block() }
    }

    private fun onStream(endpointId: String, stream: InputStream) {
        val e = epoch
        stream.use { input ->
            val (gid, offset) = RoomProtocol.readHeader(input)?.let(RoomProtocol::parseRequest) ?: return
            val s = _state.value
            val file = s.manifest.files.firstOrNull { it.id == gid }
            if (s.role == RoomRole.GUEST) {
                if (endpointId != hostEndpoint || gid !in wanted) return
                val dest = partials[gid]
                if (file == null || dest == null) return onControl(e) { failTransfer(gid) }
                inflight[gid] = input
                val outcome = receive(input, dest, gid, file.size, offset, e)
                onControl(e) {
                    if (!inflight.remove(gid, input)) return@onControl
                    when (outcome) {
                        Outcome.DONE -> {
                            partials.remove(gid)
                            finish(gid, dest)
                        }
                        Outcome.CUT -> failTransfer(gid)
                        Outcome.BAD -> if (offset > 0 && restarted.add(gid)) {
                            fetch(file)
                        } else {
                            partials.remove(gid)
                            failTransfer(gid)
                        }
                    }
                }
            } else {
                if (file == null) {
                    if (endpointId == RoomProtocol.ownerOf(gid)) onControl(e) { relayFailed(gid) }
                    return
                }
                // The host only takes bytes from the guest that owns the file, and only if someone asked.
                if (endpointId != file.ownerKey || relay[gid].isNullOrEmpty()) return
                val dest = cacheFile(gid)
                val growing = Landing(partOf(dest), dest)
                landing[gid] = growing
                inflight[gid] = input
                // Guests already waiting read the file as it lands instead of after it is whole.
                onControl(e) {
                    val waiting = relay[gid] ?: return@onControl
                    waiting.keys.filter { it != HOST_KEY }.forEach { who ->
                        waiting.remove(who)?.let { from -> forward(who, gid, growing, file.size, from) }
                    }
                }
                var outcome = Outcome.CUT
                try {
                    outcome = receive(input, dest, gid, file.size, offset, e, growing)
                } finally {
                    growing.end(outcome == Outcome.DONE)
                }
                onControl(e) {
                    landing.remove(gid, growing)
                    if (!inflight.remove(gid, input)) return@onControl
                    when (outcome) {
                        Outcome.DONE -> {
                            cache[gid] = dest
                            relay.remove(gid)?.forEach { (who, from) -> deliver(who, gid, dest, from) }
                        }
                        Outcome.CUT -> relayFailed(gid)
                        Outcome.BAD ->
                            if (offset > 0 && !relay[gid].isNullOrEmpty() && restarted.add(gid)) push(file) else relayFailed(gid)
                    }
                }
            }
        }
    }

    private enum class Outcome { DONE, CUT, BAD }

    /**
     * A relayed file growing in the cache. Readers see bytes as they land and fail at the end
     * unless the upstream's checksum held, so a forwarded copy is never better than the cache.
     */
    private class Landing(private val part: File, private val dest: File) {
        private var landed = 0L
        private var ok: Boolean? = null
        private var moved = false

        @Synchronized fun grew(to: Long) {
            landed = to
            (this as Object).notifyAll()
        }

        @Synchronized fun end(passed: Boolean) {
            ok = passed
            (this as Object).notifyAll()
        }

        /** Under the lock so [open] never looks for the part after it moved; open readers keep the file. */
        @Synchronized fun move() {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            moved = true
        }

        /** The whole file from byte 0, blocking for bytes still on their way. */
        @Synchronized fun open(): InputStream {
            val channel = FileChannel.open((if (moved) dest else part).toPath(), StandardOpenOption.READ)
            return object : InputStream() {
                private var pos = 0L
                override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xff }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    val ready = ready(pos)
                    if (ready < 0) return -1
                    val n = channel.read(ByteBuffer.wrap(b, off, minOf(len.toLong(), ready).toInt()), pos)
                    if (n > 0) pos += n
                    return n
                }
                override fun close() = channel.close()
            }
        }

        /** Bytes readable past [pos], or -1 once a checked file ends; throws when the upstream failed. */
        @Synchronized private fun ready(pos: Long): Long {
            while (pos >= landed && ok == null) (this as Object).wait()
            return when {
                pos < landed -> landed - pos
                ok == true -> -1
                else -> throw IOException("relay upstream failed")
            }
        }
    }

    /**
     * Writes the bytes after [offset] into [dest]'s part file and checks the whole file's SHA-256.
     * A stream that stops early keeps the part for a resume; wrong or extra bytes delete it.
     */
    private fun receive(input: InputStream, dest: File, gid: String, size: Long, offset: Long, e: Int, growing: Landing? = null): Outcome {
        val hostWaiting = _state.value.role == RoomRole.GUEST || relay[gid]?.containsKey(HOST_KEY) == true
        val part = partOf(dest)
        if (offset > size || offset > part.length()) {
            part.delete()
            return Outcome.BAD
        }
        val started = System.currentTimeMillis()
        var count = offset
        val outcome = try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER)
            RandomAccessFile(part, "rw").use { it.setLength(offset) }
            growing?.grew(offset)
            part.inputStream().use { prefix ->
                var n = prefix.read(buf)
                while (n >= 0) {
                    digest.update(buf, 0, n)
                    n = prefix.read(buf)
                }
            }
            if (hostWaiting) setTransfer(gid, Transfer(Transfer.Status.RECEIVING, count, size, startedAt = started, from = offset))
            var lastReport = System.nanoTime()
            FileOutputStream(part, true).use { out ->
                while (count < size && e == epoch) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), size - count).toInt())
                    if (n < 0) break
                    touch(gid)
                    count += n
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    growing?.grew(count)
                    if (hostWaiting && System.nanoTime() - lastReport >= REPORT_NANOS) {
                        lastReport = System.nanoTime()
                        setTransfer(gid, Transfer(Transfer.Status.RECEIVING, count, size, startedAt = started, from = offset))
                    }
                }
            }
            when {
                count < size || e != epoch -> Outcome.CUT
                !RoomProtocol.trailerMatches(input, digest.digest()) -> Outcome.BAD
                else -> {
                    if (growing != null) growing.move()
                    else Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Outcome.DONE
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Room transfer %s stopped at %d of %d", gid, count, size)
            Outcome.CUT
        }
        if (outcome == Outcome.BAD) {
            Timber.w("Room transfer %s failed its checksum", gid)
            part.delete()
        }
        return outcome
    }

    private fun finish(gid: String, file: File) {
        // Host copying a relayed file out of the cache; guests already wrote straight to the download.
        val meta = _state.value.manifest.files.firstOrNull { it.id == gid }
        val dest = if (file.parentFile == cacheDir()) {
            downloadFile(meta?.name ?: "file", meta?.path.orEmpty()).also {
                val part = partOf(it).toPath()
                // A hard link costs nothing where cache and downloads share a file system; else a native copy.
                runCatching { Files.deleteIfExists(part); Files.createLink(part, file.toPath()) }
                    .getOrElse { Files.copy(file.toPath(), part, StandardCopyOption.REPLACE_EXISTING) }
                Files.move(part, it.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } else file
        wanted -= gid
        restarted -= gid
        setTransfer(gid, Transfer(Transfer.Status.DONE, dest.length(), dest.length(), dest.path))
        scope.launch { pump() }
    }

    private fun touch(gid: String) {
        lastBytes[gid] = System.currentTimeMillis()
    }

    /** Fails files that made no progress for [idleMs], whatever stopped them, so their slots free up. */
    private fun checkIdle() {
        val stale = System.currentTimeMillis() - idleMs
        lastBytes.filterValues { it < stale }.keys.forEach { gid ->
            lastBytes.remove(gid)
            inflight.remove(gid)?.let { runCatching { it.close() } }
            if (relay.containsKey(gid)) relayFailed(gid)
            if (gid in wanted) failTransfer(gid)
        }
    }

    private fun failTransfer(gid: String) {
        wanted -= gid
        val total = _state.value.manifest.files.firstOrNull { it.id == gid }?.size ?: 0
        setTransfer(gid, Transfer(Transfer.Status.FAILED, keptBytes(gid), total))
        scope.launch { pump() }
    }

    /** Bytes of [gid] already on disk from earlier attempts. */
    private fun keptBytes(gid: String): Long {
        val dest = if (_state.value.role == RoomRole.HOST) cacheFile(gid) else partials[gid] ?: return 0
        return partOf(dest).length()
    }

    private fun setTransfer(gid: String, t: Transfer) =
        _state.update { it.copy(transfers = it.transfers + (gid to t)) }

    // Files

    private fun cacheDir() = File(context.cacheDir, "room").apply { mkdirs() }

    /** Peer-chosen ids never reach the file system; the cache name only derives from them. */
    private fun cacheFile(gid: String) = File(cacheDir(), UUID.nameUUIDFromBytes(gid.toByteArray()).toString())

    private fun partOf(dest: File) = File(dest.path + ".part")

    /** [dest]'s part file, emptied when it is longer than the file can be. */
    private fun freshPart(dest: File, size: Long): File = partOf(dest).apply {
        if (!exists() || length() > size) writeBytes(ByteArray(0))
    }

    private fun downloadDir() = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir)
        .resolve("Ping").apply { mkdirs() }

    /**
     * A path inside [downloadDir], under the file's folder [path] when it has one, that no earlier
     * or concurrent download uses. The empty part file created here holds the name until the
     * transfer completes or gives up.
     */
    private fun downloadFile(name: String, path: String): File = synchronized(downloadLock) {
        val root = downloadDir()
        val dir = RoomProtocol.safePath(path).takeIf { it.isNotEmpty() }?.let { File(root, it) }
            ?.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) && (it.isDirectory || it.mkdirs()) }
            ?: root
        val taken = dir.list()?.map { it.removeSuffix(".part") }?.toSet().orEmpty()
        File(dir, RoomProtocol.uniqueName(RoomProtocol.safeName(name), taken)).also { partOf(it).createNewFile() }
    }

    private companion object {
        const val SERVICE_ID = "com.ping.app.room"
        /** Rooms a guest may fail the gesture check with per search; each is an online guess. */
        const val MAX_JOIN_ATTEMPTS = 3
        const val MAX_NAME = 30
        const val SEARCH_MS = 30_000L
        /** A transfer with no new bytes for this long gives up its slot. */
        const val IDLE_MS = 30_000L
        const val IDLE_TICK_MS = 1_000L
        // ponytail: no eviction, the relay cache only empties when the room closes; LRU if rooms run long.
        const val RELAY_CAP = 2L shl 30
        const val DISCONNECT_GRACE_MS = 500L
        /** Downloads in flight at once: enough to hide each request's round trip, few enough to keep streams fast. */
        const val MAX_PARALLEL = 3
        const val BUFFER = RoomProtocol.BUFFER
        /** Progress by time, not bytes: a fast link would otherwise rebuild the state many times a second. */
        const val REPORT_NANOS = 200_000_000L
        val ACTIVE = setOf(Transfer.Status.QUEUED, Transfer.Status.RECEIVING)
    }
}
