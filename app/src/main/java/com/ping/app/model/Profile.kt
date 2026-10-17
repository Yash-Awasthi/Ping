package com.ping.app.model

import androidx.room3.Entity
import androidx.room3.PrimaryKey

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
    /** The filled fields whose wire keys are in [fields]. */
    fun toShareableMap(fields: Set<String> = FIELDS): Map<String, String> =
        mapOf("name" to displayName, "phone" to phone, "email" to email, "social" to social, "note" to note)
            .filter { (key, value) -> key in fields && value.isNotBlank() }

    companion object {
        const val LOCAL_ID = "local_profile"
        /** Every wire key a card can carry. */
        val FIELDS = setOf("name", "phone", "email", "social", "note")
    }
}
