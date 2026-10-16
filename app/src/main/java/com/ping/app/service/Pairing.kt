package com.ping.app.service

import com.ping.app.utils.CryptoUtils
import com.ping.app.utils.Pake
import java.security.MessageDigest
import java.security.spec.ECPoint

/** Advertisement format and matching rules for a gesture swap: `<token>|<nonce>`. */
object Pairing {

    /**
     * A 4-bit bucket of the gesture code, one hex digit. The code is the PAKE password, so the
     * advertisement may only narrow the search: a full hash would let a scanner enumerate it.
     */
    // ponytail: 16 buckets trade guesses for connections; widen if crowds cause many failed attempts
    fun token(code: String): String =
        "%x".format(MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))[0].toInt() and 0x0F)

    fun advertisement(code: String, nonce: String): String = "${token(code)}|$nonce"

    /** True when [remoteName] may hold the same gesture as [code]; only the PAKE confirms it. */
    fun matches(code: String, remoteName: String): Boolean =
        remoteName.substringBefore('|', missingDelimiterValue = "") == token(code)

    /**
     * The side that requests a matched connection first. The smaller advertisement wins; the
     * other waits a short grace for that request before sending its own.
     */
    fun shouldInitiate(localName: String, remoteName: String): Boolean = localName < remoteName

    /** The first already-discovered candidate for [code] not in [tried], from endpointId to advertisement. */
    fun firstMatch(code: String, seen: Map<String, String>, tried: Set<String> = emptySet()): Map.Entry<String, String>? =
        seen.entries.firstOrNull { it.key !in tried && matches(code, it.value) }

    /** Ping phones in range that certainly locked a different gesture. */
    fun othersNearby(code: String, seen: Map<String, String>): Int =
        seen.values.count { !matches(code, it) }

    /**
     * One connection's gesture check. The peer's card may arrive before its tag, but it is held
     * sealed and only opened once the tag proves the same gesture; a failed attempt drops it.
     */
    class Attempt(generator: ECPoint) {
        val pake = Pake.Session(generator)
        var keys: Pake.Keys? = null
            private set
        var verified = false
            private set
        private var pendingCard: ByteArray? = null

        /** Keys for the link [context], or null when the peer's share is unusable. */
        fun onShare(peerShare: ByteArray, context: ByteArray): Pake.Keys? =
            pake.finish(peerShare, context)?.also { keys = it }

        /** False when the tag does not prove the same gesture. */
        fun onConfirm(tag: ByteArray): Boolean {
            verified = verified || keys?.verify(tag) == true
            return verified
        }

        fun onCard(sealed: ByteArray) { pendingCard = sealed }

        /** The peer's card once its tag checked out, else null; throws when the seal does not open. */
        fun openCard(): ByteArray? {
            val key = keys?.session?.takeIf { verified } ?: return null
            val sealed = pendingCard ?: return null
            pendingCard = null
            return CryptoUtils.decrypt(key, sealed)
        }
    }
}
