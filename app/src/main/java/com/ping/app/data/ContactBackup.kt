package com.ping.app.data

import com.ping.app.model.Contact
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** Bump when a field changes meaning; older apps then refuse the file instead of misreading it. */
const val BACKUP_VERSION = 1
private const val MAX_BACKUP_CONTACTS = 5000

@Serializable
private class ContactBackup(val version: Int, val contacts: List<Contact>)

// encodeDefaults: id and receivedAt default to fresh values, so a skipped field would not round-trip.
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** Every field of [contacts], memo and favourite included; unlike vCard this is not for sharing. */
fun encodeBackup(contacts: List<Contact>): String =
    json.encodeToString(ContactBackup.serializer(), ContactBackup(BACKUP_VERSION, contacts))

/** Contacts in a backup file; throws when [text] is not a backup this version can read. */
fun decodeBackup(text: String): List<Contact> {
    val backup = json.decodeFromString(ContactBackup.serializer(), text)
    require(backup.version == BACKUP_VERSION) { "unsupported backup version ${backup.version}" }
    require(backup.contacts.size <= MAX_BACKUP_CONTACTS) { "too many contacts" }
    return backup.contacts.filterNot { it.isEmptyCard }
        .map { if (it.id.isBlank()) it.copy(id = UUID.randomUUID().toString()) else it }
}

/**
 * The contacts to write so [restored] lands in [existing] without duplicates. A match (same id or
 * [Contact.isSamePerson]) keeps its id and the newer card; a star from either side sticks, and a
 * memo already on the phone wins over the backup's.
 */
fun mergeBackup(existing: List<Contact>, restored: List<Contact>): List<Contact> {
    val before = existing.associateBy { it.id }
    val all = existing.toMutableList()
    val changed = LinkedHashMap<String, Contact>()
    for (r in restored) {
        val i = all.indexOfFirst { it.id == r.id || it.isSamePerson(r) }
        val merged = if (i < 0) r.also { all += it } else {
            val e = all[i]
            val card = if (r.receivedAt > e.receivedAt) r else e
            card.copy(id = e.id, isFavorite = e.isFavorite || r.isFavorite, memo = e.memo.ifBlank { r.memo })
                .also { all[i] = it }
        }
        if (merged != before[merged.id]) changed[merged.id] = merged else changed.remove(merged.id)
    }
    return changed.values.toList()
}
