package com.ping.app.rollcall

import java.security.SecureRandom

/**
 * Advertisements for a roll call. Nobody connects: the admin advertises `A|<session>|<label>` and each
 * student answers by advertising `S|<session>|<roll>|<device>|<name>`, which the admin only scans.
 * That keeps a class of any size inside one Bluetooth scan instead of a handful of connections.
 */
object RollCallProtocol {

    const val SERVICE_ID = "com.ping.app.rollcall"
    const val MAX_ROLL = 16
    const val MAX_NAME = 24

    data class AdminAdvert(val session: String, val label: String)

    data class StudentAdvert(val session: String, val roll: String, val device: String, val name: String)

    private val hex = Regex("[0-9a-f]{8}")
    private val random = SecureRandom()

    fun newToken(): String = ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** The label lets students pick their own class when two roll calls run side by side. */
    fun adminAdvert(session: String, label: String) = "A|$session|${cleanName(label)}"

    fun parseAdmin(name: String): AdminAdvert? =
        name.split('|').takeIf { it.size == 3 && it[0] == "A" && hex.matches(it[1]) }
            ?.let { AdminAdvert(it[1], cleanName(it[2])) }

    fun studentAdvert(session: String, roll: String, device: String, name: String) =
        "S|$session|${cleanRoll(roll)}|$device|${cleanName(name)}"

    fun parseStudent(name: String): StudentAdvert? {
        val parts = name.split('|')
        if (parts.size != 5 || parts[0] != "S" || !hex.matches(parts[1]) || !hex.matches(parts[3])) return null
        val roll = cleanRoll(parts[2]).ifEmpty { return null }
        return StudentAdvert(parts[1], roll, parts[3], cleanName(parts[4]))
    }

    /** Roll numbers are compared as typed, so keep only letters, digits and dashes, upper-cased. */
    fun cleanRoll(raw: String): String =
        raw.filter { it.isLetterOrDigit() || it == '-' }.uppercase().take(MAX_ROLL)

    fun cleanName(raw: String): String =
        raw.filter { it >= ' ' && it != '|' && it != '\u007f' }.trim().take(MAX_NAME)

    /** One CSV field; a leading formula character is neutralised so spreadsheets do not run it. */
    fun csvField(v: String): String {
        val safe = if (v.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
    }
}
