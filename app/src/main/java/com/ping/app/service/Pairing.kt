package com.ping.app.service

import java.security.MessageDigest

/** Advertisement format and matching rules for a gesture swap: `<token>|<nonce>`. */
object Pairing {

    /**
     * Truncated SHA-256 of the gesture code. The space is 128 codes, so this hides the
     * code from a casual scanner but not from anyone who enumerates it.
     */
    fun token(code: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }

    fun advertisement(code: String, nonce: String): String = "${token(code)}|$nonce"

    /** True when [remoteName] was advertised by a phone holding the same gesture as [code]. */
    fun matches(code: String, remoteName: String): Boolean =
        remoteName.substringBefore('|', missingDelimiterValue = "") == token(code)

    /**
     * Exactly one side of a matched pair must request the connection. The smaller
     * advertisement wins; equal advertisements (same nonce) leave both waiting.
     */
    fun shouldInitiate(localName: String, remoteName: String): Boolean = localName < remoteName
}
