package com.ping.app.utils

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric half of a Ping swap: HKDF-SHA256 for key derivation and AES-256-GCM to seal the
 * card. The key itself comes from the gesture-keyed exchange in [Pake].
 */
object CryptoUtils {

    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_IV_SIZE = 12
    private const val GCM_TAG_BITS = 128

    /** RFC 5869 HKDF-SHA256 with a non-empty [salt], returning one 32-byte output block. */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        expand.update(info)
        expand.update(0x01)
        prk.fill(0)
        return expand.doFinal()
    }

    /** PBKDF2-HMAC-SHA256 to a 256-bit AES key; wiping [passphrase] is left to the caller. */
    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES").also { raw.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    /** AES-256-GCM seal that also authenticates [aad]. Returns IV(12) || ciphertext || tag(16). */
    fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key) }
        if (aad != null) cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    /** AES-256-GCM open of a blob from [encrypt] with the same [aad]. Throws on tamper/wrong key. */
    fun decrypt(key: SecretKey, blob: ByteArray, aad: ByteArray? = null): ByteArray {
        require(blob.size > GCM_IV_SIZE) { "blob too short" }
        val iv = blob.copyOf(GCM_IV_SIZE)
        val body = blob.copyOfRange(GCM_IV_SIZE, blob.size)
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(body)
    }
}
