package com.ping.app.service

import java.security.MessageDigest

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
     * Exactly one side of a matched pair must request the connection. The smaller
     * advertisement wins; equal advertisements (same nonce) leave both waiting.
     */
    fun shouldInitiate(localName: String, remoteName: String): Boolean = localName < remoteName

    /** The first already-discovered candidate for [code] not in [tried], from endpointId to advertisement. */
    fun firstMatch(code: String, seen: Map<String, String>, tried: Set<String> = emptySet()): Map.Entry<String, String>? =
        seen.entries.firstOrNull { it.key !in tried && matches(code, it.value) }

    /** Ping phones in range that certainly locked a different gesture. */
    fun othersNearby(code: String, seen: Map<String, String>): Int =
        seen.values.count { !matches(code, it) }
}
