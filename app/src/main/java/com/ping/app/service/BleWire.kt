package com.ping.app.service

import com.ping.app.utils.CryptoUtils
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wire format of [BleL2capTransport], kept free of android.* so it runs in JVM tests.
 *
 * Advert: service data under [serviceUuid], `psm(2) || utf8(name)`.
 * Channel: length-prefixed blobs. The first blob each way is an ephemeral P-256 public key;
 * every later blob is `AES-256-GCM(type(1) || body)` under a per-direction key.
 */
internal object BleWire {

    const val T_HELLO: Byte = 1
    const val T_ACCEPT: Byte = 2
    const val T_REJECT: Byte = 3
    const val T_BYTES: Byte = 4
    const val T_CHUNK: Byte = 5
    const val T_END: Byte = 6

    const val MAX_BYTES = 1 shl 20
    private const val MAX_BLOB = MAX_BYTES + 64

    // 31 bytes minus the flags AD (3), the service-data AD header (2) and the 128-bit UUID (16).
    private const val LEGACY_ROOM = 31 - 3 - 2 - 16

    fun serviceUuid(serviceId: String): UUID = UUID.nameUUIDFromBytes(serviceId.toByteArray(Charsets.UTF_8))

    fun packAdvert(psm: Int, name: String): ByteArray {
        require(psm in 1..0xFFFF) { "psm out of range: $psm" }
        return byteArrayOf((psm shr 8).toByte(), psm.toByte()) + name.toByteArray(Charsets.UTF_8)
    }

    /** (psm, name), or null when [data] is not a Ping advert. */
    fun parseAdvert(data: ByteArray?): Pair<Int, String>? {
        if (data == null || data.size < 3) return null
        val psm = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (psm == 0) return null
        return psm to data.copyOfRange(2, data.size).toString(Charsets.UTF_8)
    }

    fun fitsLegacy(advert: ByteArray): Boolean = advert.size <= LEGACY_ROOM

    fun writeBlob(out: DataOutputStream, blob: ByteArray) {
        out.writeInt(blob.size)
        out.write(blob)
        out.flush()
    }

    fun readBlob(input: DataInputStream): ByteArray {
        val n = input.readInt()
        if (n !in 1..MAX_BLOB) throw IOException("bad frame length $n")
        return ByteArray(n).also(input::readFully)
    }

    class Session(val cipher: LinkCipher, val token: ByteArray)

    /** Unauthenticated ECDH; [Session.token] is what both phones bind their app-layer check to. */
    fun handshake(input: DataInputStream, out: DataOutputStream, initiator: Boolean): Session {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val mine = keys.public.encoded
        writeBlob(out, mine)
        val theirs = readBlob(input)
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(keys.private)
            doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirs)), true)
            generateSecret()
        }
        val token = MessageDigest.getInstance("SHA-256").digest(if (initiator) mine + theirs else theirs + mine)
        val i2r = CryptoUtils.hkdfSha256(shared, token, "ping-l2cap-i2r".toByteArray())
        val r2i = CryptoUtils.hkdfSha256(shared, token, "ping-l2cap-r2i".toByteArray())
        shared.fill(0)
        return Session(if (initiator) LinkCipher(i2r, r2i) else LinkCipher(r2i, i2r), token)
    }

    fun message(type: Byte, body: ByteArray = ByteArray(0)): ByteArray = byteArrayOf(type) + body

    fun chunk(streamId: Int, data: ByteArray, len: Int): ByteArray =
        ByteBuffer.allocate(5 + len).put(T_CHUNK).putInt(streamId).put(data, 0, len).array()

    fun end(streamId: Int): ByteArray = ByteBuffer.allocate(5).put(T_END).putInt(streamId).array()
}

/**
 * AES-256-GCM with implicit counter nonces. The channel is reliable and ordered, so a
 * replayed, dropped or reordered blob fails authentication instead of being accepted.
 */
internal class LinkCipher(sendKey: ByteArray, recvKey: ByteArray) {
    private val send = SecretKeySpec(sendKey, "AES")
    private val recv = SecretKeySpec(recvKey, "AES")
    private var sent = 0L
    private var received = 0L

    @Synchronized
    fun seal(plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, send, sent++).doFinal(plain)

    @Synchronized
    fun open(blob: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE, recv, received).doFinal(blob).also { received++ }

    private fun cipher(mode: Int, key: SecretKeySpec, counter: Long) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, ByteBuffer.allocate(12).putLong(4, counter).array()))
        }
}
