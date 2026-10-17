package com.ping.app.utils

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec

/**
 * Covers the card seal: HKDF matches the RFC, and a tampered card is rejected rather than
 * silently accepted. Key agreement is covered by [PakeTest].
 */
class CryptoUtilsTest {

    private val card = """{"name":"Yash","phone":"+10000000000"}""".toByteArray()

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun range(from: Int, to: Int) = (from..to).map { it.toByte() }.toByteArray()

    // RFC 5869 appendix A.1 and A.2; only the first 32 bytes, since the key uses one block.
    @Test
    fun `hkdf matches rfc 5869 test case 1`() {
        val okm = CryptoUtils.hkdfSha256(ByteArray(22) { 0x0b }, range(0x00, 0x0c), range(0xf0, 0xf9))
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"), okm)
    }

    @Test
    fun `hkdf matches rfc 5869 test case 2`() {
        val okm = CryptoUtils.hkdfSha256(range(0x00, 0x4f), range(0x60, 0xaf), range(0xb0, 0xff))
        assertArrayEquals(hex("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c"), okm)
    }

    @Test
    fun `a card round-trips through the sealed blob`() {
        assertArrayEquals(card, CryptoUtils.decrypt(key, CryptoUtils.encrypt(key, card)))
    }

    @Test
    fun `a flipped ciphertext bit is rejected`() {
        val blob = CryptoUtils.encrypt(key, card)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        assertThrows(GeneralSecurityException::class.java) { CryptoUtils.decrypt(key, blob) }
    }

    @Test
    fun `a blob shorter than the iv is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CryptoUtils.decrypt(key, ByteArray(4))
        }
    }
}
