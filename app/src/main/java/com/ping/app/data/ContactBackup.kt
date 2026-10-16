package com.ping.app.data

import com.ping.app.model.Contact
import com.ping.app.utils.CryptoUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID

/** Bump when a field changes meaning; older apps then refuse the file instead of misreading it. */
const val BACKUP_VERSION = 1
private const val MAX_BACKUP_CONTACTS = 5000

@Serializable
private class ContactBackup(val version: Int, val contacts: List<Contact>)

// encodeDefaults: id and receivedAt default to fresh values, so a skipped field would not round-trip.
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

// Sealed file: MAGIC(8) | SEALED_VERSION(1) | salt(16) | iterations(4, big-endian) | AES-GCM blob.
private val MAGIC = "PINGSEAL".toByteArray()
private const val SEALED_VERSION: Byte = 1
private const val SALT_SIZE = 16
private const val HEADER_SIZE = 8 + 1 + SALT_SIZE + 4
private const val PBKDF2_ITERATIONS = 310_000
// Bounds a crafted file's iteration count, which would otherwise stall the import.
private val ITERATION_RANGE = 100_000..5_000_000

/** A sealed backup needs a passphrase, or the one given did not open it. */
class BackupLockedException(val wrongPassphrase: Boolean) :
    IllegalArgumentException(if (wrongPassphrase) "wrong passphrase or damaged file" else "passphrase required")

/**
 * Every field of [contacts], memo and favourite included; unlike vCard this is not for sharing.
 * A non-blank [passphrase] seals the JSON with a PBKDF2-derived AES-GCM key; otherwise it is plain.
 */
fun encodeBackup(contacts: List<Contact>, passphrase: CharArray? = null): ByteArray {
    val plain = json.encodeToString(ContactBackup.serializer(), ContactBackup(BACKUP_VERSION, contacts)).toByteArray()
    if (passphrase == null || passphrase.all(Char::isWhitespace)) return plain
    val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
    val header = ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).put(SEALED_VERSION).put(salt).putInt(PBKDF2_ITERATIONS).array()
    val key = CryptoUtils.deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
    return header + CryptoUtils.encrypt(key, plain, aad = header).also { plain.fill(0) }
}

/** Contacts in a plain or sealed backup file; throws when [file] is not a backup this version can read. */
fun decodeBackup(file: ByteArray, passphrase: CharArray? = null): List<Contact> {
    val text = if (file.size >= MAGIC.size && file.copyOf(MAGIC.size).contentEquals(MAGIC)) {
        unseal(file, passphrase)
    } else file
    val backup = json.decodeFromString(ContactBackup.serializer(), text.decodeToString())
    require(backup.version == BACKUP_VERSION) { "unsupported backup version ${backup.version}" }
    require(backup.contacts.size <= MAX_BACKUP_CONTACTS) { "too many contacts" }
    return backup.contacts.filterNot { it.isEmptyCard }
        .map { if (it.id.isBlank()) it.copy(id = UUID.randomUUID().toString()) else it }
}

private fun unseal(file: ByteArray, passphrase: CharArray?): ByteArray {
    require(file.size > HEADER_SIZE) { "truncated backup" }
    val header = ByteBuffer.wrap(file, MAGIC.size, HEADER_SIZE - MAGIC.size)
    require(header.get() == SEALED_VERSION) { "unsupported sealed backup version" }
    val salt = ByteArray(SALT_SIZE).also { header.get(it) }
    val iterations = header.int
    require(iterations in ITERATION_RANGE) { "bad iteration count" }
    if (passphrase == null || passphrase.all(Char::isWhitespace)) throw BackupLockedException(wrongPassphrase = false)
    val key = CryptoUtils.deriveKey(passphrase, salt, iterations)
    return try {
        CryptoUtils.decrypt(key, file.copyOfRange(HEADER_SIZE, file.size), aad = file.copyOf(HEADER_SIZE))
    } catch (e: GeneralSecurityException) {
        throw BackupLockedException(wrongPassphrase = true)
    }
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
