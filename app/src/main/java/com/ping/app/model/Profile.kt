package com.ping.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The user's own card — what gets sent during a Ping swap.
 *
 * Single profile, single row keyed [LOCAL_ID]. Only non-blank fields are put
 * on the wire (see [toShareableMap]).
 */
@Entity(tableName = "profile")
data class Profile(
    @PrimaryKey val id: String = LOCAL_ID,
    val displayName: String = "",
    val phone: String = "",
    val email: String = "",
    val social: String = "",
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toShareableMap(): Map<String, String> = buildMap {
        if (displayName.isNotBlank()) put("name", displayName)
        if (phone.isNotBlank()) put("phone", phone)
        if (email.isNotBlank()) put("email", email)
        if (social.isNotBlank()) put("social", social)
        if (note.isNotBlank()) put("note", note)
    }

    companion object {
        const val LOCAL_ID = "local_profile"
    }
}
