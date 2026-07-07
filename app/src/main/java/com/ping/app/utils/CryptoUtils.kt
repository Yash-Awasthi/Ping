package com.ping.app.utils

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal crypto for a Ping card swap.
 *
 * Per exchange:
 *  1. Each side generates a fresh ephemeral EC keypair ([generateEphemeralKeyPair]).
 *  2. Public keys are swapped over the (plaintext) Nearby channel.
 *  3. Both derive the same AES-256 key via ECDH + HKDF-SHA256 ([deriveSharedKey]).
 *  4. The card JSON is sealed with AES-256-GCM ([encrypt] / [decrypt]).
 *
 * No long-lived keys, no post-quantum layers, no SAS — this is a playful
 * proximity swap, not a state-secret channel. The gesture is the pairing gate.
 */
object CryptoUtils {

    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_IV_SIZE = 12
    private const val GCM_TAG_BITS = 128

    /** Fresh ephemeral EC (P-256) keypair for a single exchange. Never reused. */
    fun generateEphemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    /** X.509 (SPKI) bytes for advertising our public key to the peer. */
    fun encodePublicKey(publicKey: PublicKey): ByteArray = publicKey.encoded

    /** Reconstruct a peer public key from the bytes produced by [encodePublicKey]. */
    fun decodePublicKey(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    /**
     * Derive the shared AES-256 key from our private key and the peer's public key.
     * HKDF-SHA256 (RFC 5869) is applied so the key is uniform and domain-separated.
     */
    fun deriveSharedKey(ourPrivate: PrivateKey, peerPublic: PublicKey): SecretKey {
        val ka = KeyAgreement.getInstance("ECDH").apply {
            init(ourPrivate)
            doPhase(peerPublic, true)
        }
        val shared = ka.generateSecret()

        val salt = "ping-ecdh-v1".toByteArray()
        val info = "ping-aes-256-gcm".toByteArray()

        // HKDF-Extract
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prk = extract.doFinal(shared)
        // HKDF-Expand (single 32-byte block)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        expand.update(info)
        expand.update(0x01)
        val okm = expand.doFinal()

        shared.fill(0)
        prk.fill(0)
        return SecretKeySpec(okm, "AES")
    }

    /** AES-256-GCM seal. Returns IV(12) || ciphertext || tag(16). */
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plaintext)
    }

    /** AES-256-GCM open of a blob produced by [encrypt]. Throws on tamper/wrong key. */
    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > GCM_IV_SIZE) { "blob too short" }
        val iv = blob.copyOf(GCM_IV_SIZE)
        val body = blob.copyOfRange(GCM_IV_SIZE, blob.size)
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(body)
    }
}
