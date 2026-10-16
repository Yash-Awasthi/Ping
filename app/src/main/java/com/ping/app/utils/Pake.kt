package com.ping.app.utils

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Gesture-keyed key exchange (CPace-style balanced PAKE on P-256).
 *
 * Both phones hash the gesture code to a curve point G, pick a fresh scalar, and swap
 * x(scalar·G); the connection context is bound into the key and both confirmation tags. Only a
 * phone holding the same gesture derives the same key, and a peer that guessed wrong learns
 * nothing it can test offline: each connection is one online guess.
 *
 * Only x-coordinates travel, so the JDK's ECDH does all point arithmetic: x(k·P) is the same
 * for P and -P, and the y recovered in [lift] never needs its sign.
 */
object Pake {

    const val SHARE_SIZE = 32

    private val params: ECParameterSpec by lazy {
        val pub = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public as ECPublicKey
        pub.params
    }
    private val p: BigInteger get() = (params.curve.field as ECFieldFp).p

    /**
     * Binds the exchange to this connection: both advertisements (sorted, so either side builds
     * the same bytes) and the transport's link token, which differs on each leg of a relay.
     */
    fun context(nameA: String, nameB: String, linkToken: ByteArray?): ByteArray {
        val (lo, hi) = if (nameA <= nameB) nameA to nameB else nameB to nameA
        return lengthPrefixed(lo.toByteArray(), hi.toByteArray(), linkToken ?: ByteArray(0))
    }

    /**
     * One side of one attempt, built before the link exists so only one scalar multiplication is
     * left once the peer's share arrives. Never reuse across connections: the scalar is the secret.
     */
    class Session(generator: ECPoint) {
        private val scalar = KeyPairGenerator.getInstance("EC").apply { initialize(params) }.generateKeyPair().private
        val share: ByteArray = mulX(scalar, generator)

        /** Keys for the link described by [context], or null when [peerShare] is not a usable point. */
        fun finish(peerShare: ByteArray, context: ByteArray): Keys? {
            if (peerShare.size != SHARE_SIZE || MessageDigest.isEqual(peerShare, share)) return null
            val point = lift(BigInteger(1, peerShare)) ?: return null
            val k = runCatching { mulX(scalar, point) }.getOrNull() ?: return null
            val (lo, hi) = if (compareUnsigned(share, peerShare) <= 0) share to peerShare else peerShare to share
            val isk = CryptoUtils.hkdfSha256(k, SALT, lengthPrefixed(context, lo, hi))
            k.fill(0)
            val confirmKey = CryptoUtils.hkdfSha256(isk, SALT, "ping-confirm".toByteArray())
            val aes = CryptoUtils.hkdfSha256(isk, SALT, "ping-aes-256-gcm".toByteArray())
            isk.fill(0)
            return Keys(
                session = SecretKeySpec(aes, "AES"),
                myTag = hmac(confirmKey, share, peerShare),
                peerTag = hmac(confirmKey, peerShare, share),
            )
        }
    }

    /** [myTag] goes to the peer; its tag must equal [peerTag] before anything it sent is opened or trusted. */
    class Keys internal constructor(val session: SecretKey, val myTag: ByteArray, private val peerTag: ByteArray) {
        fun verify(tag: ByteArray): Boolean = MessageDigest.isEqual(tag, peerTag)
    }

    // Try-and-increment of the gesture alone (CPace hashes sid here too) so G is ready at lock;
    // the ISK and so both tags still bind the link context, so a relay onto another link fails.
    fun generator(password: String): ECPoint {
        for (ctr in 0..255) {
            val h = MessageDigest.getInstance("SHA-256").apply {
                update(DSI)
                update(ctr.toByte())
                update(lengthPrefixed(password.toByteArray()))
            }.digest()
            lift(BigInteger(1, h).mod(p))?.let { return it }
        }
        error("no curve point in 256 tries")
    }

    /** A P-256 point with x-coordinate [x], or null when there is none. */
    internal fun lift(x: BigInteger): ECPoint? {
        if (x.signum() < 0 || x >= p) return null
        val rhs = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        // p ≡ 3 (mod 4), so a square root is rhs^((p+1)/4) when one exists.
        val y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        return if (y.multiply(y).mod(p) == rhs) ECPoint(x, y) else null
    }

    private fun mulX(scalar: java.security.PrivateKey, point: ECPoint): ByteArray {
        val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        return KeyAgreement.getInstance("ECDH").run {
            init(scalar)
            doPhase(pub, true)
            generateSecret()
        }
    }

    private fun hmac(key: ByteArray, first: ByteArray, second: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(first)
            update(second)
            doFinal()
        }

    private fun lengthPrefixed(vararg parts: ByteArray): ByteArray =
        parts.fold(ByteArray(0)) { acc, part ->
            acc + byteArrayOf((part.size ushr 8).toByte(), part.size.toByte()) + part
        }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private val DSI = "ping-cpace-p256-v1".toByteArray()
    private val SALT = "ping-cpace-v1".toByteArray()
}
