package com.ping.app.room

import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Wire format and pure rules of the file room.
 *
 * Control messages travel as BYTES payloads (`[type][body]`); file bytes travel as STREAM
 * payloads that start with a length-prefixed [header] naming the file, so a stream identifies
 * itself no matter which payload arrives first.
 */
object RoomProtocol {

    const val T_HELLO: Byte = 1
    const val T_MANIFEST_UP: Byte = 2
    const val T_MANIFEST_DOWN: Byte = 3
    const val T_APPROVED: Byte = 4
    const val T_DENIED: Byte = 5
    const val T_REQUEST: Byte = 6
    const val T_PUSH: Byte = 7

    const val HOST_KEY = "host"
    const val MAX_FILES_PER_MEMBER = 100
    const val MAX_NAME = 80
    private const val MAX_HEADER = 200

    /** A file a member offers: [id] is unique within that member only. */
    data class FileMeta(val id: String, val name: String, val size: Long)

    /** A file as the whole room sees it; [id] is globally unique (see [globalId]). */
    data class RoomFile(
        val id: String,
        val name: String,
        val size: Long,
        val ownerKey: String,
        val ownerName: String,
    )

    data class Manifest(val files: List<RoomFile>, val members: List<String>)

    /** What one approved guest currently offers. */
    data class GuestShare(val key: String, val name: String, val files: List<FileMeta>)

    private val gson = Gson()

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
            hostFiles.forEach { add(RoomFile(globalId(HOST_KEY, it.id), it.name, it.size, HOST_KEY, hostName)) }
            guests.forEach { g ->
                g.files.forEach { add(RoomFile(globalId(g.key, it.id), it.name, it.size, g.key, g.name)) }
            }
        }
        return Manifest(files, listOf(hostName) + guests.map { it.name })
    }

    fun encodeMetas(files: List<FileMeta>): String = gson.toJson(files)

    fun encodeManifest(m: Manifest): String = gson.toJson(m)

    /** Parses a peer's file list; malformed input yields an empty list rather than an error. */
    fun decodeMetas(json: String): List<FileMeta> = runCatching {
        gson.fromJson(json, Array<FileMeta>::class.java).orEmpty().asSequence()
            .filter { !it.id.isNullOrBlank() && !it.id.contains(':') && it.size >= 0 }
            .take(MAX_FILES_PER_MEMBER)
            .map { FileMeta(it.id, safeName(it.name), it.size) }
            .toList()
    }.getOrDefault(emptyList())

    fun decodeManifest(json: String): Manifest? = runCatching {
        val m = gson.fromJson(json, Manifest::class.java)
        Manifest(
            // Gson skips Kotlin null checks, so absent fields must be screened by hand.
            files = m.files.orEmpty().filter {
                it.size >= 0 && (it.id as String?)?.contains(':') == true &&
                    !(it.ownerKey as String?).isNullOrBlank() && !(it.ownerName as String?).isNullOrBlank()
            }.map { it.copy(name = safeName(it.name), ownerName = safeName(it.ownerName)) },
            members = m.members.orEmpty().filterNotNull().map { safeName(it) },
        )
    }.getOrNull()

    /** Stream preamble: unsigned-short length, then the UTF-8 global id. */
    fun header(globalId: String): ByteArray {
        val id = globalId.toByteArray(Charsets.UTF_8)
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
