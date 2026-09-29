package com.ping.app.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
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
