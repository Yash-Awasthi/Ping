package com.ping.app.rollcall

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
) : ViewModel() {

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
