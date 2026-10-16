package com.ping.app.service

import com.ping.app.service.BleWire.T_ABORT
import com.ping.app.service.BleWire.T_ACCEPT
import com.ping.app.service.BleWire.T_BYTES
import com.ping.app.service.BleWire.T_CHUNK
import com.ping.app.service.BleWire.T_END
import com.ping.app.service.BleWire.T_REJECT
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One encrypted channel of [BleL2capTransport] over plain streams, so it runs in JVM tests.
 * Both sides must [accept] before [Host.connected]; closing [rawIn] or [rawOut] closes the channel.
 */
internal class BleLink(
    val id: String,
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
    private val initiator: Boolean,
    @Volatile var remoteName: String,
    private val host: Host,
    private val handshakeMs: Long = HANDSHAKE_MS,
) {
    /** Called from link threads; the transport hops to the main thread itself. */
    interface Host {
        fun initiated(link: BleLink, token: ByteArray)
        fun connected(link: BleLink)
        fun bytes(link: BleLink, body: ByteArray)
        /** Hands an incoming stream to the app, or null to drop it. */
        fun stream(link: BleLink): ((InputStream) -> Unit)?
        fun closed(link: BleLink, notify: Boolean)
    }

    private val input = DataInputStream(BufferedInputStream(rawIn))
    private val out = DataOutputStream(BufferedOutputStream(rawOut, OUT_BUFFER))
    private lateinit var cipher: LinkCipher
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "ble-l2cap-$id").apply { isDaemon = true } }
    private val nextStream = AtomicInteger()
    private val inbound = ConcurrentHashMap<Int, Sink>()
    // ponytail: forgets after 64 ids, so a long-dropped stream that keeps sending reopens mid-way; per-stream state if that bites.
    private val dropped = object : LinkedHashMap<Int, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Unit>?) = size > MAX_DROPPED
    }
    private val control = ConcurrentLinkedQueue<ByteArray>()
    private val chunks = ConcurrentLinkedQueue<ByteArray>()
    private val chunkRoom = Semaphore(QUEUED_CHUNKS)

    @Volatile private var localAccepted = false
    @Volatile private var remoteAccepted = false
    @Volatile var connected = false
        private set
    @Volatile private var closed = false

    val incoming get() = !initiator

    /** Runs until the channel closes; [localName] is sent as the hello by the initiator only. */
    fun run(localName: String?) {
        val watchdog = TIMER.schedule({ close(notify = true) }, handshakeMs, TimeUnit.MILLISECONDS)
        try {
            val session = BleWire.handshake(input, out, initiator)
            cipher = session.cipher
            if (localName != null) send(BleWire.hello(localName))
            else remoteName = BleWire.parseHello(cipher.open(BleWire.readBlob(input, BleWire.HANDSHAKE_BLOB)))
            watchdog.cancel(false)
            host.initiated(this, session.token)
            while (!closed) dispatch(cipher.open(BleWire.readBlob(input)))
        } catch (e: Exception) {
            if (!closed) Timber.d(e, "BleLink: $id ended")
        } finally {
            watchdog.cancel(false)
            close(notify = true)
        }
    }

    fun accept() {
        localAccepted = true
        enqueue(BleWire.message(T_ACCEPT))
        maybeConnected()
    }

    fun reject() {
        enqueue(BleWire.message(T_REJECT))
        runCatching { writer.execute { close(notify = !connected) } }
    }

    fun sendBytes(data: ByteArray) = enqueue(BleWire.message(T_BYTES, data))

    /** Blocks the calling thread until [source] is sent; a failed read aborts the stream at the peer. */
    fun sendStream(source: InputStream) {
        val streamId = nextStream.incrementAndGet()
        try {
            source.use {
                val buf = ByteArray(CHUNK_BYTES)
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    if (n > 0) enqueueChunk(BleWire.chunk(streamId, buf, n))
                }
            }
            enqueueChunk(BleWire.end(streamId))
        } catch (e: Exception) {
            Timber.e(e, "BleLink: stream to $id failed")
            runCatching { enqueueChunk(BleWire.abort(streamId)) }
        }
    }

    private fun dispatch(msg: ByteArray) {
        if (msg.isEmpty()) throw IOException("empty frame")
        when (msg[0]) {
            T_ACCEPT -> { remoteAccepted = true; maybeConnected() }
            T_REJECT -> throw IOException("rejected by peer")
            T_BYTES -> if (connected) host.bytes(this, msg.copyOfRange(1, msg.size))
            T_CHUNK -> if (connected) receiveChunk(BleWire.streamId(msg), msg)
            T_END -> inbound.remove(BleWire.streamId(msg))?.let { runCatching { it.close() } }
            T_ABORT -> inbound.remove(BleWire.streamId(msg))?.abort()
        }
    }

    // ponytail: a stream reader slower than the radio stalls this link's inbound control; per-stream credits if that bites.
    private fun receiveChunk(streamId: Int, msg: ByteArray) {
        if (dropped[streamId] != null) return
        val sink = inbound[streamId] ?: run {
            val deliver = host.stream(this)
            if (deliver == null || inbound.size >= MAX_INBOUND) {
                dropped[streamId] = Unit
                return
            }
            val sink = Sink()
            inbound[streamId] = sink
            if (closed) sink.abort()
            deliver(Source(sink))
            sink
        }
        try {
            sink.write(msg, 5, msg.size - 5)
            // A JDK pipe's reader otherwise naps up to a second before it sees the bytes.
            sink.flush()
        } catch (_: IOException) {
            inbound.remove(streamId)
            dropped[streamId] = Unit
        }
    }

    @Synchronized
    private fun maybeConnected() {
        if (connected || !localAccepted || !remoteAccepted || closed) return
        connected = true
        host.connected(this)
    }

    /** Seal and write one message; only [run] before [Host.initiated], then only [writer], calls it. */
    private fun send(plain: ByteArray, flush: Boolean = true) = BleWire.writeBlob(out, cipher.seal(plain), flush)

    private fun enqueue(plain: ByteArray) {
        runCatching { post(control, plain) }
    }

    /** Blocks while [QUEUED_CHUNKS] wait, so a stream never runs far ahead of the radio. */
    private fun enqueueChunk(plain: ByteArray) {
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
            .onFailure { Timber.w(it, "BleLink: send to $id failed") }
    }

    @Synchronized
    fun close(notify: Boolean) {
        if (closed) return
        closed = true
        runCatching { rawIn.close() }
        runCatching { rawOut.close() }
        writer.shutdown()
        inbound.values.forEach { it.abort() }
        inbound.clear()
        host.closed(this, notify)
    }

    private class Sink : PipedOutputStream() {
        @Volatile var aborted = false

        fun abort() {
            aborted = true
            runCatching { close() }
        }
    }

    /** A stream cut by [T_ABORT] or a dropped link reads as an IOException, never as a clean end. */
    private class Source(private val sink: Sink) : FilterInputStream(PipedInputStream(sink, PIPE_BYTES)) {
        override fun read(): Int = super.read().also(::check)
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also(::check)
        private fun check(n: Int) {
            if (n < 0 && sink.aborted) throw IOException("stream aborted")
        }
    }

    private companion object {
        /** Handshake and hello must finish in this long, so a silent peer cannot hold a link slot. */
        const val HANDSHAKE_MS = 5_000L
        const val MAX_INBOUND = 4
        const val MAX_DROPPED = 64
        /** A control frame waits behind at most one chunk on the air; 32 KiB is ~0.2 s at LE rates. */
        const val CHUNK_BYTES = 32 * 1024
        const val QUEUED_CHUNKS = 4
        /** Holds a whole chunk frame, so its length prefix never goes out as a separate SDU. */
        const val OUT_BUFFER = 64 * 1024
        const val PIPE_BYTES = 256 * 1024
        val TIMER = ScheduledThreadPoolExecutor(1) { Thread(it, "ble-l2cap-watchdog").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }
}
