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
