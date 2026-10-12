package com.ping.app.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A contact received via a Ping swap. Mirrors the shareable card fields in
 * [Profile]: name, phone, email, social handle, and a short free-text note.
 */
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val displayName: String = "",
    val phone: String = "",
    val email: String = "",
    val social: String = "",
    val note: String = "",
    val receivedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    /** The owner's private memo, such as where they met; never sent or exported. */
    @ColumnInfo(defaultValue = "") val memo: String = "",
) {
    /** True when the sender had not filled in any field; such a card is not worth saving. */
    val isEmptyCard: Boolean
        get() = listOf(displayName, phone, email, social, note).all { it.isBlank() }

    /** True when [other] is the same person: a shared phone number (digits only) or email. */
    fun isSamePerson(other: Contact): Boolean {
        fun digits(v: String) = v.filter(Char::isDigit)
        val phoneMatch = digits(phone).isNotEmpty() && digits(phone) == digits(other.phone)
        val emailMatch = email.isNotBlank() && email.trim().equals(other.email.trim(), ignoreCase = true)
        return phoneMatch || emailMatch
    }

    /** vCard 3.0 text for sharing; values are escaped per RFC 2426 so a card cannot inject fields. */
    fun toVCard(): String {
        fun esc(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
            .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")
        return buildString {
            append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
            val fn = displayName.ifBlank { email.ifBlank { phone.ifBlank { social } } }
            append("FN:").append(esc(fn)).append("\r\n")
            append("N:").append(esc(fn)).append(";;;;\r\n")
            if (phone.isNotBlank()) append("TEL:").append(esc(phone)).append("\r\n")
            if (email.isNotBlank()) append("EMAIL:").append(esc(email)).append("\r\n")
            if (social.isNotBlank()) append("URL:").append(esc(social)).append("\r\n")
            if (note.isNotBlank()) append("NOTE:").append(esc(note)).append("\r\n")
            append("END:VCARD\r\n")
        }
    }

    companion object {
        private const val MAX_IMPORT = 5000

        /**
         * Cards read from a .vcf body (vCard 2.1, 3.0 or 4.0). Only the fields Ping keeps are
         * read; cards with none of them are dropped.
         */
        fun fromVCards(text: String): List<Contact> {
            // Folded lines continue with a leading space or tab.
            val folded = text.replace("\r\n", "\n").replace("\r", "\n")
                .replace(Regex("\n[ \t]"), "").split('\n')
            // vCard 2.1 quoted-printable values wrap with a soft '=' at the line end.
            val lines = mutableListOf<String>()
            for (line in folded) {
                val prev = lines.lastOrNull()
                if (prev != null && prev.endsWith('=') && prev.substringBefore(':').contains("QUOTED-PRINTABLE", true)) {
                    lines[lines.size - 1] = prev.dropLast(1) + line
                } else lines += line
            }
            val cards = mutableListOf<Contact>()
            var fields: MutableMap<String, String>? = null
            for (line in lines) {
                val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
                val params = line.substring(0, colon).split(';')
                val key = params[0].substringAfterLast('.').uppercase()
                val raw = line.substring(colon + 1)
                when {
                    key == "BEGIN" && raw.equals("VCARD", true) -> fields = mutableMapOf()
                    key == "END" && raw.equals("VCARD", true) -> {
                        fields?.let { f ->
                            val name = f["FN"] ?: f["N"]?.split(';')?.let { n ->
                                listOf(n.getOrNull(1), n.getOrNull(0)).filterNot { it.isNullOrBlank() }.joinToString(" ")
                            }.orEmpty()
                            val c = Contact(
                                displayName = name.trim(),
                                phone = f["TEL"].orEmpty().removePrefix("tel:").trim(),
                                email = f["EMAIL"].orEmpty().trim(),
                                social = f["URL"].orEmpty().trim(),
                                note = f["NOTE"].orEmpty().trim(),
                            )
                            if (!c.isEmptyCard) cards += c
                        }
                        fields = null
                        if (cards.size >= MAX_IMPORT) break
                    }
                    else -> {
                        val f = fields ?: continue
                        if (key !in setOf("FN", "N", "TEL", "EMAIL", "URL", "NOTE") || key in f) continue
                        val qp = params.any { it.equals("ENCODING=QUOTED-PRINTABLE", true) || it.equals("QUOTED-PRINTABLE", true) }
                        val value = if (qp) decodeQuotedPrintable(raw) else raw
                        f[key] = if (key == "N") value else unescape(value)
                    }
                }
            }
            return cards
        }

        private fun unescape(v: String): String = buildString {
            var i = 0
            while (i < v.length) {
                val ch = v[i]
                if (ch == '\\' && i + 1 < v.length) {
                    val next = v[i + 1]
                    append(if (next == 'n' || next == 'N') '\n' else next)
                    i += 2
                } else {
                    append(ch); i++
                }
            }
        }

        private fun decodeQuotedPrintable(v: String): String {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < v.length) {
                val hex = if (v[i] == '=' && i + 2 < v.length) v.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) { out.write(hex); i += 3 } else { out.write(v[i].toString().toByteArray()); i++ }
            }
            return out.toString(Charsets.UTF_8.name())
        }

        /** One .vcf body holding every card in [contacts]. */
        fun toVCards(contacts: List<Contact>): String = contacts.joinToString("") { it.toVCard() }

        /** Field keys used on the wire (see [Profile.toShareableMap]). */
        fun fromMap(map: Map<String, String>): Contact = Contact(
            displayName = map["name"].orEmpty(),
            phone = map["phone"].orEmpty(),
            email = map["email"].orEmpty(),
            social = map["social"].orEmpty(),
            note = map["note"].orEmpty(),
        )
    }
}
