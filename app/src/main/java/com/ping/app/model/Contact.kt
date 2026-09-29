package com.ping.app.model

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
) {
    /** vCard 3.0 text for sharing; values are escaped per RFC 2426 so a card cannot inject fields. */
    fun toVCard(): String {
        fun esc(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
            .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")
        return buildString {
            append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
            append("FN:").append(esc(displayName)).append("\r\n")
            append("N:").append(esc(displayName)).append(";;;;\r\n")
            if (phone.isNotBlank()) append("TEL:").append(esc(phone)).append("\r\n")
            if (email.isNotBlank()) append("EMAIL:").append(esc(email)).append("\r\n")
            if (social.isNotBlank()) append("URL:").append(esc(social)).append("\r\n")
            if (note.isNotBlank()) append("NOTE:").append(esc(note)).append("\r\n")
            append("END:VCARD\r\n")
        }
    }

    companion object {
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
