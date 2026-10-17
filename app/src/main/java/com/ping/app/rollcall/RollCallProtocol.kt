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
    /** Attendance flag for a roll that answered but is not on the imported class list. */
    const val FLAG_NEW_ROLL = "new roll"

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

    /**
     * Trust-the-room: anyone in radio range can claim any roll, and nothing proves who holds the
     * phone. The admin's only checks are the headcount, [FLAG_NEW_ROLL] and the other-phone flag.
     */
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

    const val MAX_ROSTER = 2000

    /**
     * Roll numbers and names from a class list: comma, semicolon or tab separated, quotes
     * allowed, an optional header row, name optional. Later rows win on a repeated roll.
     */
    fun parseRoster(text: String): List<Pair<String, String>> {
        val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val sep = listOf(',', ';', '\t').maxBy { c -> lines.first().count { it == c } }
        val rows = lines.map { splitCsvLine(it, sep) }
        val header = rows.first().map { it.trim().lowercase() }
        // A header names its columns and holds no digits; a first data row always has a roll number.
        val looksLikeHeader = header.none { f -> f.any(Char::isDigit) }
        val rollCol = if (!looksLikeHeader) -1 else header.indexOfFirst {
            it.startsWith("roll") || it == "id" || it.startsWith("enrol") || it.startsWith("reg")
        }
        val nameCol = if (!looksLikeHeader) -1 else header.indexOfFirst { "name" in it }
        val hasHeader = looksLikeHeader && (rollCol >= 0 || nameCol >= 0)
        val r = if (rollCol >= 0) rollCol else 0
        val n = if (nameCol >= 0) nameCol else if (r == 0) 1 else 0
        val out = LinkedHashMap<String, String>()
        for (row in if (hasHeader) rows.drop(1) else rows) {
            val roll = cleanRoll(row.getOrNull(r).orEmpty())
            if (roll.isEmpty()) continue
            out[roll] = cleanName(row.getOrNull(n).orEmpty()).ifEmpty { roll }
            if (out.size >= MAX_ROSTER) break
        }
        return out.toList()
    }

    private fun splitCsvLine(line: String, sep: Char): List<String> {
        val fields = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> { fields += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        fields += cur.toString()
        return fields
    }

    /** One CSV field; a leading formula character is neutralised so spreadsheets do not run it. */
    fun csvField(v: String): String {
        val safe = if (v.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
    }
}
