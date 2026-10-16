package com.ping.app.ui.contacts

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.data.BackupLockedException
import com.ping.app.data.ContactRepository
import com.ping.app.data.decodeBackup
import com.ping.app.data.encodeBackup
import com.ping.app.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _imported = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** How many cards the last import saved; -1 when the file could not be read. */
    val imported: SharedFlow<Int> = _imported

    private val _backedUp = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** Whether the last backup was written. */
    val backedUp: SharedFlow<Boolean> = _backedUp

    private val _restored = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** How many contacts the last restore held; -1 when the file was not a readable backup. */
    val restored: SharedFlow<Int> = _restored

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** Favourites first, then newest; narrowed by the search box. */
    val contacts: StateFlow<List<Contact>> = combine(contactRepository.contacts, _query) { all, q ->
        filterContacts(all, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) { _query.value = q }

    fun importVCards(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readNBytesCompat(MAX_VCF_BYTES + 1) }
                require(bytes.size <= MAX_VCF_BYTES) { "file too large" }
                contactRepository.import(Contact.fromVCards(bytes.toString(Charsets.UTF_8)))
            }.getOrDefault(-1)
            _imported.tryEmit(count)
        }
    }

    private val _locked = MutableSharedFlow<Pair<Uri, Boolean>>(extraBufferCapacity = 1)
    /** A restore that needs a passphrase: the file, and whether the last one tried was wrong. */
    val locked: SharedFlow<Pair<Uri, Boolean>> = _locked

    // Held here, not in saved UI state, so the passphrase never lands in a saved-state bundle.
    private var backupPassphrase: CharArray? = null

    /** Passphrase for the next [backUp]; null or blank writes a plain file. */
    fun setBackupPassphrase(passphrase: CharArray?) {
        backupPassphrase?.fill(' ')
        backupPassphrase = passphrase
    }

    fun backUp(uri: Uri) {
        val passphrase = backupPassphrase.also { backupPassphrase = null }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val file = encodeBackup(contactRepository.contacts.first(), passphrase)
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(file) }
            }.isSuccess
            passphrase?.fill(' ')
            _backedUp.tryEmit(ok)
        }
    }

    fun restore(uri: Uri, passphrase: CharArray? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readNBytesCompat(MAX_BACKUP_BYTES + 1) }
                require(bytes.size <= MAX_BACKUP_BYTES) { "file too large" }
                contactRepository.restore(decodeBackup(bytes, passphrase))
            }.onSuccess { _restored.tryEmit(it) }.onFailure {
                if (it is BackupLockedException) _locked.tryEmit(uri to it.wrongPassphrase) else _restored.tryEmit(-1)
            }
            passphrase?.fill(' ')
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch { contactRepository.delete(contact) }
    }
}

private const val MAX_VCF_BYTES = 5 * 1024 * 1024
private const val MAX_BACKUP_BYTES = 10 * 1024 * 1024

/** Reads at most [limit] bytes; InputStream.readNBytes needs API 33. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
