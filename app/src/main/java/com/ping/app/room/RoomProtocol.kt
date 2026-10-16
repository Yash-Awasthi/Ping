package com.ping.app.room

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.SequenceInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Wire format and pure rules of the file room.
 *
 * Control messages travel as BYTES payloads (`[type][body]`); file bytes travel as STREAM
 * payloads that start with a length-prefixed [header] naming the file, so a stream identifies
 * itself no matter which payload arrives first. The file bytes are followed by their SHA-256.
 * Requests and stream headers name a byte offset to resume from; the digest always covers the whole file.
 */
object RoomProtocol {

    const val T_HELLO: Byte = 1
    const val T_MANIFEST_UP: Byte = 2
    const val T_MANIFEST_DOWN: Byte = 3
    const val T_APPROVED: Byte = 4
    const val T_DENIED: Byte = 5
    const val T_REQUEST: Byte = 6
    const val T_PUSH: Byte = 7
    /** [com.ping.app.utils.Pake] share, then its confirmation tag; nothing else is read until both check out. */
    const val T_SHARE: Byte = 8
    const val T_CONFIRM: Byte = 9
    /** Host to guest: a file it asked for cannot be relayed; body is the global id. */
    const val T_FAILED: Byte = 10

    const val HOST_KEY = "host"
    /** Nearby's P2P_STAR has no fixed cap; this keeps a full room's listing and relay load sane. */
    const val MAX_GUESTS = 16
    const val MAX_FILES_PER_MEMBER = 100
    const val MAX_ROOM_FILES = MAX_FILES_PER_MEMBER * (MAX_GUESTS + 1)
    /** Nearby's limit for one BYTES payload. */
    const val MAX_FRAME = 32 * 1024
    /** Room for a page's members and envelope on top of its files, under [MAX_FRAME]. */
    private const val PAGE_BUDGET = 24 * 1024
    private const val MAX_PAGES = 128
    const val MAX_NAME = 80
    const val MAX_DEPTH = 8
    const val MAX_PATH = 160
    private const val MAX_HEADER = 200
    private const val MAX_ID = 40
    private const val DIGEST_SIZE = 32

    /**
     * A file a member offers: [id] is unique within that member only. [path] is the folder it
     * sat in inside a shared folder, "" for a single file.
     */
    @Serializable
    data class FileMeta(val id: String, val name: String, val size: Long, val path: String = "")

    /** A file as the whole room sees it; [id] is globally unique (see [globalId]). */
    @Serializable
    data class RoomFile(
        val id: String,
        val name: String,
        val size: Long,
        val ownerKey: String,
        val ownerName: String,
        val path: String = "",
    )

    @Serializable
    data class Manifest(val files: List<RoomFile>, val members: List<String>)

    /** What one approved guest currently offers. */
    @Serializable
    data class GuestShare(val key: String, val name: String, val files: List<FileMeta>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Decodes each element on its own, so one malformed entry drops only itself. */
    private inline fun <reified T> JsonElement?.items(): List<T> = (this as? JsonArray).orEmpty()
        .mapNotNull { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }

    fun frame(type: Byte, body: ByteArray = ByteArray(0)): ByteArray =
        ByteArray(body.size + 1).also {
            it[0] = type
            System.arraycopy(body, 0, it, 1, body.size)
        }

    fun frameText(type: Byte, text: String): ByteArray = frame(type, text.toByteArray(Charsets.UTF_8))

    fun globalId(ownerKey: String, localId: String) = "$ownerKey:$localId"

    fun ownerOf(globalId: String) = globalId.substringBefore(':')

    fun localIdOf(globalId: String) = globalId.substringAfter(':')

    /** Folds the host's own files and every approved guest's files into one room listing. */
    fun merge(hostName: String, hostFiles: List<FileMeta>, guests: List<GuestShare>): Manifest {
        val files = buildList {
            hostFiles.forEach { add(RoomFile(globalId(HOST_KEY, it.id), it.name, it.size, HOST_KEY, hostName, it.path)) }
            guests.forEach { g ->
                g.files.forEach { add(RoomFile(globalId(g.key, it.id), it.name, it.size, g.key, g.name, it.path)) }
            }
        }
        return Manifest(files, listOf(hostName) + guests.map { it.name })
    }

    /**
     * One frame's share of a file listing: Nearby refuses BYTES payloads over [MAX_FRAME], which a
     * full room's listing passes. [rev] ties the [parts] of one listing together; [members] rides on part 0.
     */
    class Page<T>(val rev: Int, val part: Int, val parts: Int, val files: List<T>, val members: List<String>)

    /** Gathers the pages of one listing; a page of another revision starts over. */
    class PageSet<T> {
        private var rev = 0
        private val got = HashMap<Int, Page<T>>()

        /** The whole listing once [page] completes it, else null. */
        fun add(page: Page<T>): Page<T>? {
            if (page.rev != rev || got.values.any { it.parts != page.parts }) {
                rev = page.rev
                got.clear()
            }
            got[page.part] = page
            if (got.size < page.parts) return null
            val all = (0 until page.parts).map(got::getValue)
            return Page(rev, 0, 1, all.flatMap { it.files }, all[0].members)
        }
    }

    fun manifestPages(m: Manifest, rev: Int): List<String> =
        pages(m.files.map { json.encodeToJsonElement(it) }, m.members, rev)

    fun metaPages(files: List<FileMeta>, rev: Int): List<String> =
        pages(files.map { json.encodeToJsonElement(it) }, emptyList(), rev)

    private fun pages(items: List<JsonElement>, members: List<String>, rev: Int): List<String> {
        val chunks = mutableListOf(mutableListOf<JsonElement>())
        var used = 0
        for (item in items) {
            val size = item.toString().toByteArray(Charsets.UTF_8).size + 1
            if (used + size > PAGE_BUDGET && chunks.last().isNotEmpty()) {
                chunks += mutableListOf<JsonElement>()
                used = 0
            }
            chunks.last() += item
            used += size
        }
        return chunks.mapIndexed { i, chunk ->
            buildJsonObject {
                put("rev", rev)
                put("part", i)
                put("parts", chunks.size)
                put("files", JsonArray(chunk))
                if (i == 0) put("members", JsonArray(members.map(::JsonPrimitive)))
            }.toString()
        }
    }

    /** Parses one page of a guest's file list; malformed input yields null or drops single entries. */
    fun decodeMetaPage(text: String): Page<FileMeta>? = decodePage(text) { files ->
        files.items<FileMeta>().asSequence()
            .filter { it.id.isNotBlank() && it.id.length <= MAX_ID && it.id.none { c -> c == ':' || c.isWhitespace() } && it.size >= 0 }
            .take(MAX_FILES_PER_MEMBER)
            .map { FileMeta(it.id, safeName(it.name), it.size, safePath(it.path)) }
            .toList()
    }

    fun decodeManifestPage(text: String): Page<RoomFile>? = decodePage(text) { files ->
        files.items<RoomFile>().asSequence()
            .filter { it.size >= 0 && it.id.contains(':') && it.ownerKey.isNotBlank() && it.ownerName.isNotBlank() }
            .take(MAX_ROOM_FILES)
            .map { it.copy(name = safeName(it.name), ownerName = safeName(it.ownerName), path = safePath(it.path)) }
            .toList()
    }

    private fun <T> decodePage(text: String, files: (JsonElement?) -> List<T>): Page<T>? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        val parts = o["parts"]?.jsonPrimitive?.int ?: 1
        val part = o["part"]?.jsonPrimitive?.int ?: 0
        if (parts !in 1..MAX_PAGES || part !in 0 until parts) return null
        val members = o["members"].items<String>().take(MAX_GUESTS + 1).map { safeName(it) }
        Page(o["rev"]?.jsonPrimitive?.int ?: 0, part, parts, files(o["files"]), members)
    }.getOrNull()

    /** A request for [id] from byte [offset]; a whole-file request is the bare id, as older peers send it. */
    fun request(id: String, offset: Long = 0): String = if (offset > 0) "$id $offset" else id

    /** Inverse of [request]; null for a blank id or a malformed offset. */
    fun parseRequest(text: String): Pair<String, Long>? {
        val id = text.substringBefore(' ')
        val offset = if (' ' in text) text.substringAfter(' ').toLongOrNull()?.takeIf { it >= 0 } ?: return null else 0L
        return if (id.isBlank()) null else id to offset
    }

    /** Stream preamble: unsigned-short length, then the UTF-8 [request] for the bytes that follow. */
    fun header(globalId: String, offset: Long = 0): ByteArray {
        val id = request(globalId, offset).toByteArray(Charsets.UTF_8)
        require(id.size in 1..MAX_HEADER)
        return ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeShort(id.size); write(id); flush() }
        }.toByteArray()
    }

    /** Reads the [header] off [input]; null when it is missing, oversized or truncated. */
    fun readHeader(input: InputStream): String? = try {
        val data = DataInputStream(input)
        val len = data.readUnsignedShort()
        if (len == 0 || len > MAX_HEADER) null
        else ByteArray(len).also { data.readFully(it) }.toString(Charsets.UTF_8)
    } catch (_: EOFException) {
        null
    }

    /**
     * [body] from byte [skip] on, followed by the SHA-256 of all of [body]: the skipped prefix
     * is hashed but not sent, on first read so the caller's thread never waits on it.
     */
    fun withDigest(body: InputStream, skip: Long = 0): InputStream {
        val digesting = DigestInputStream(body, MessageDigest.getInstance("SHA-256"))
        val prefix = object : InputStream() {
            override fun read(): Int {
                val buf = ByteArray(32 * 1024)
                var left = skip
                while (left > 0) {
                    val n = digesting.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    left -= n
                }
                return -1
            }
        }
        val trailer = object : InputStream() {
            // Built on first read, which SequenceInputStream only reaches after the body ends.
            private val bytes by lazy { ByteArrayInputStream(digesting.messageDigest.digest()) }
            override fun read() = bytes.read()
            override fun read(b: ByteArray, off: Int, len: Int) = bytes.read(b, off, len)
        }
        return SequenceInputStream(Collections.enumeration(listOf(prefix, digesting, trailer)))
    }

    /** True when [input] holds exactly the 32-byte [digest] and nothing after it. */
    fun trailerMatches(input: InputStream, digest: ByteArray): Boolean = try {
        val sent = ByteArray(DIGEST_SIZE).also { DataInputStream(input).readFully(it) }
        MessageDigest.isEqual(sent, digest) && input.read() < 0
    } catch (_: EOFException) {
        false
    }

    /**
     * A file name taken from a peer is untrusted: it must not carry a path, hidden-file dots
     * or control characters, and must stay short. Never returns an empty name.
     */
    fun safeName(raw: String?): String {
        val cleaned = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" }
            .trimStart('.', ' ')
            .trimEnd(' ', '.')
        val name = if (cleaned.length > MAX_NAME) {
            val ext = cleaned.substringAfterLast('.', "").take(10)
            if (ext.isNotEmpty() && ext.length < cleaned.length - 1) cleaned.take(MAX_NAME - ext.length - 1) + "." + ext
            else cleaned.take(MAX_NAME)
        } else cleaned
        return name.ifBlank { "file" }
    }

    /**
     * A folder path taken from a peer: relative, `/`-separated, every folder a name [safeName]
     * leaves as it is, at most [MAX_DEPTH] deep and [MAX_PATH] long. Anything else gives "", a flat file.
     */
    fun safePath(raw: String?): String {
        if (raw.isNullOrEmpty() || raw.length > MAX_PATH) return ""
        val folders = raw.split('/')
        val ok = folders.size <= MAX_DEPTH && folders.all { it.isNotEmpty() && it.length <= MAX_NAME && safeName(it) == it }
        return if (ok) raw else ""
    }

    /** [parent] with one more folder, or [parent] itself when that would break [safePath]'s bounds. */
    fun childPath(parent: String, folder: String?): String =
        safePath(if (parent.isEmpty()) safeName(folder) else parent + "/" + safeName(folder)).ifEmpty { parent }

    /** [name] made unique against [taken] by inserting " (n)" before the extension. */
    fun uniqueName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while ("$stem ($n)$ext" in taken) n++
        return "$stem ($n)$ext"
    }
}
