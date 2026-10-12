package com.ping.app.rollcall

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RollCallViewModel @Inject constructor(
    private val rollCall: RollCall,
    dao: RollCallDao,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** roll to sessions present, for each student's attendance rate. */
    val rates: StateFlow<Map<String, Int>> = dao.observeRates().map { list -> list.associate { it.roll to it.present } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val sessionCount: StateFlow<Int> =
        dao.observeSessionCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _imported = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** Rows read from the last class-list import; -1 when the file could not be read. */
    val imported: SharedFlow<Int> = _imported

    fun importRoster(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val n = runCatching {
                val text = appContext.contentResolver.openInputStream(uri)!!.use { input ->
                    val bytes = input.readNBytesCapped(MAX_ROSTER_BYTES + 1)
                    require(bytes.size <= MAX_ROSTER_BYTES) { "class list too large" }
                    bytes.toString(Charsets.UTF_8)
                }
                rollCall.importRoster(text)
            }.getOrDefault(-1)
            _imported.tryEmit(n)
        }
    }

    fun renameStudent(roll: String, name: String) { viewModelScope.launch { rollCall.renameStudent(roll, name) } }

    fun removeStudent(roll: String) { viewModelScope.launch { rollCall.removeStudent(roll) } }

    val state = rollCall.state

    val students: StateFlow<List<Student>> =
        dao.observeStudents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sessions: StateFlow<List<SessionSummary>> =
        dao.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The session the admin is looking at: the live one, or one opened from history. */
    val openSession = MutableStateFlow<String?>(null)

    val attendance: StateFlow<List<Attendance>> = openSession
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeAttendance(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var roll: String
        get() = rollCall.savedRoll
        set(v) { rollCall.savedRoll = v }

    var name: String
        get() = rollCall.savedName
        set(v) { rollCall.savedName = v }

    fun startTaking(label: String) {
        rollCall.startTaking(label)
        openSession.value = (rollCall.state.value as? RollCallState.Taking)?.sessionId
    }

    fun stop() = rollCall.stop()

    override fun onCleared() = rollCall.stop()

    fun startCheckingIn() = rollCall.startCheckingIn()

    fun join(ad: RollCallProtocol.AdminAdvert) = rollCall.join(ad)

    fun setPresent(roll: String, present: Boolean) {
        openSession.value?.let { rollCall.setPresent(it, roll, present) }
    }

    fun deleteOpenSession() {
        val id = openSession.value ?: return
        openSession.value = null
        viewModelScope.launch { rollCall.deleteSession(id) }
    }

    fun exportCsv(context: Context, label: String) {
        val id = openSession.value ?: return
        viewModelScope.launch {
            runCatching {
                val dir = File(context.cacheDir, "export").apply { mkdirs() }
                val safe = label.filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "class" }
                val file = File(dir, "attendance-$safe.csv")
                file.writeText(rollCall.csv(id, students.value))
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/csv")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, null))
            }
        }
    }
}

private const val MAX_ROSTER_BYTES = 1024 * 1024

private fun java.io.InputStream.readNBytesCapped(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
