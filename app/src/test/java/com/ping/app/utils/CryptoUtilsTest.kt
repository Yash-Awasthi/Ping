package com.ping.app.utils

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.GeneralSecurityException

/**
 * Covers the swap's crypto contract: both sides agree on a key, nobody else does,
 * and a tampered card is rejected rather than silently accepted.
 */
class CryptoUtilsTest {

    private val card = """{"name":"Yash","phone":"+10000000000"}""".toByteArray()

    private fun sharedKeys(): Pair<javax.crypto.SecretKey, javax.crypto.SecretKey> {
        val alice = CryptoUtils.generateEphemeralKeyPair()
        val bob = CryptoUtils.generateEphemeralKeyPair()
        return CryptoUtils.deriveSharedKey(alice.private, bob.public) to
            CryptoUtils.deriveSharedKey(bob.private, alice.public)
    }

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
    fun `both sides derive the same 256-bit key`() {
        val (aliceKey, bobKey) = sharedKeys()
        assertArrayEquals(aliceKey.encoded, bobKey.encoded)
        assertEquals(32, aliceKey.encoded.size)
    }

    @Test
    fun `an unpaired third party derives a different key`() {
        val (aliceKey, _) = sharedKeys()
        val mallory = CryptoUtils.generateEphemeralKeyPair()
        val intruderKey = CryptoUtils.deriveSharedKey(mallory.private, mallory.public)
        assert(!aliceKey.encoded.contentEquals(intruderKey.encoded))
    }

    @Test
    fun `a public key survives its wire encoding`() {
        val keyPair = CryptoUtils.generateEphemeralKeyPair()
        val encoded = CryptoUtils.encodePublicKey(keyPair.public)
        assertEquals(keyPair.public, CryptoUtils.decodePublicKey(encoded))
    }

    @Test
    fun `a card round-trips through the sealed blob`() {
        val (aliceKey, _) = sharedKeys()
        assertArrayEquals(card, CryptoUtils.decrypt(aliceKey, CryptoUtils.encrypt(aliceKey, card)))
    }

    @Test
    fun `a flipped ciphertext bit is rejected`() {
        val (aliceKey, _) = sharedKeys()
        val blob = CryptoUtils.encrypt(aliceKey, card)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        assertThrows(GeneralSecurityException::class.java) { CryptoUtils.decrypt(aliceKey, blob) }
    }

    @Test
    fun `a blob shorter than the iv is rejected`() {
        val (aliceKey, _) = sharedKeys()
        assertThrows(IllegalArgumentException::class.java) {
            CryptoUtils.decrypt(aliceKey, ByteArray(4))
        }
    }

    @Test
    fun `both phones compute the same short authentication string`() {
        val a = CryptoUtils.encodePublicKey(CryptoUtils.generateEphemeralKeyPair().public)
        val b = CryptoUtils.encodePublicKey(CryptoUtils.generateEphemeralKeyPair().public)
        val sas = CryptoUtils.shortAuthString(a, b)
        assertEquals(sas, CryptoUtils.shortAuthString(b, a))
        assertEquals(6, sas.length)
    }

    @Test
    fun `a substituted key changes the short authentication string`() {
        val a = CryptoUtils.encodePublicKey(CryptoUtils.generateEphemeralKeyPair().public)
        val b = CryptoUtils.encodePublicKey(CryptoUtils.generateEphemeralKeyPair().public)
        val mitm = CryptoUtils.encodePublicKey(CryptoUtils.generateEphemeralKeyPair().public)
        assert(CryptoUtils.shortAuthString(a, b) != CryptoUtils.shortAuthString(a, mitm))
    }
}
