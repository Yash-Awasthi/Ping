package com.ping.app.rollcall

import java.security.SecureRandom

/**
 * Advertisements for a roll call. Nobody connects: the admin advertises `A|<session>|<label>|<acks>` and
 * each student answers by advertising `S|<session>|<roll>|<device>|<name>`, which the admin only scans.
 * `<acks>` packs the [ACK_LEN]-char prefix of recently marked devices so students learn they were seen.
 * That keeps a class of any size inside one Bluetooth scan instead of a handful of connections.
 */
object RollCallProtocol {

    const val SERVICE_ID = "com.ping.app.rollcall"
    const val MAX_ROLL = 16
    const val MAX_NAME = 24
    const val ACK_LEN = 6
    /** Keeps the admin advertisement inside the Nearby endpoint-info limit. */
    const val MAX_ACKS = 12

    data class AdminAdvert(val session: String, val label: String, val acks: Set<String> = emptySet())

    data class StudentAdvert(val session: String, val roll: String, val device: String, val name: String)

    private val hex = Regex("[0-9a-f]{8}")
    private val random = SecureRandom()

    fun newToken(): String = ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** The label lets students pick their own class when two roll calls run side by side. */
    fun adminAdvert(session: String, label: String, acks: List<String> = emptyList()) =
        "A|$session|${cleanName(label)}|" + acks.take(MAX_ACKS).joinToString("") { ackOf(it) }

    fun parseAdmin(name: String): AdminAdvert? {
        val parts = name.split('|')
        if (parts.size != 4 || parts[0] != "A" || !hex.matches(parts[1])) return null
        val acks = parts[3].chunked(ACK_LEN).filter { it.length == ACK_LEN && it.all { c -> c in "0123456789abcdef" } }
        return AdminAdvert(parts[1], cleanName(parts[2]), acks.toSet())
    }

    fun ackOf(device: String) = device.take(ACK_LEN)

    /**
     * Next devices to acknowledge: unannounced marks first, then a round-robin over everyone
     * already marked, so a student who missed one advertisement sees their ack on a later one.
     */
    fun nextAcks(fresh: ArrayDeque<String>, all: List<String>, cursor: Int): Pair<List<String>, Int> {
        val batch = mutableListOf<String>()
        while (batch.size < MAX_ACKS && fresh.isNotEmpty()) fresh.removeFirst().let { if (it !in batch) batch += it }
        var c = cursor
        var steps = 0
        while (batch.size < MAX_ACKS && steps < all.size) {
            val d = all[c % all.size]
            if (d !in batch) batch += d
            c++; steps++
        }
        return batch to (if (all.isEmpty()) 0 else c % all.size)
    }

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
