package com.ping.app.rollcall

import android.content.Context
import com.ping.app.service.NearbyTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class RollCallState {
    object Idle : RollCallState()

    /** This phone is taking attendance for [sessionId]. */
    data class Taking(val sessionId: String, val label: String) : RollCallState()

    /** This phone is a student: [found] roll calls in range, [joined] the one it answers. */
    data class CheckingIn(
        val found: List<RollCallProtocol.AdminAdvert> = emptyList(),
        val joined: RollCallProtocol.AdminAdvert? = null,
    ) : RollCallState()
}

/**
 * Runs one side of a roll call over the shared [NearbyTransport]. Callbacks arrive on the main
 * thread; database writes go to [scope].
 */
@Singleton
class RollCall @Inject constructor(
    private val transport: NearbyTransport,
    private val dao: RollCallDao,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("rollcall", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<RollCallState>(RollCallState.Idle)
    val state: StateFlow<RollCallState> = _state

    private var timeout: Job? = null

    /** Stable per install, so the admin can notice a student checking in from a new phone. */
    val deviceTag: String
        get() = prefs.getString(KEY_DEVICE, null)
            ?: RollCallProtocol.newToken().also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    var savedRoll: String
        get() = prefs.getString(KEY_ROLL, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_ROLL, v).apply()

    var savedName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_NAME, v).apply()

    // Admin

    fun startTaking(label: String) {
        stop()
        val sessionId = UUID.randomUUID().toString()
        val token = RollCallProtocol.newToken()
        val clean = RollCallProtocol.cleanName(label).ifBlank { "Class" }
        _state.value = RollCallState.Taking(sessionId, clean)
        scope.launch {
            dao.insertSession(RollSession(sessionId, clean, System.currentTimeMillis()))
            transport.onEndpointFound = { _, name ->
                RollCallProtocol.parseStudent(name)?.takeIf { it.session == token }?.let { onStudent(sessionId, it) }
            }
            transport.startAdvertising(RollCallProtocol.adminAdvert(token, clean), RollCallProtocol.SERVICE_ID)
            transport.startDiscovery(RollCallProtocol.SERVICE_ID)
            Timber.i("Roll call %s started", clean)
        }
    }

    private fun onStudent(sessionId: String, ad: RollCallProtocol.StudentAdvert) {
        scope.launch {
            val known = dao.student(ad.roll)
            val flag = when {
                known == null -> { dao.upsertStudent(Student(ad.roll, ad.name.ifBlank { ad.roll }, ad.device)); "" }
                known.device != ad.device -> Attendance.FLAG_OTHER_PHONE
                else -> ""
            }
            dao.mark(Attendance(sessionId, ad.roll, System.currentTimeMillis(), flag))
        }
    }

    /** The admin ticks or unticks a student by hand, during or after the session. */
    fun setPresent(sessionId: String, roll: String, present: Boolean) {
        scope.launch {
            if (present) dao.mark(Attendance(sessionId, roll, System.currentTimeMillis(), Attendance.FLAG_MANUAL))
            else dao.unmark(sessionId, roll)
        }
    }

    // Student

    fun startCheckingIn() {
        stop()
        _state.value = RollCallState.CheckingIn()
        transport.onEndpointFound = { _, name ->
            val ad = RollCallProtocol.parseAdmin(name)
            val s = _state.value as? RollCallState.CheckingIn
            if (ad != null && s != null && s.found.none { it.session == ad.session }) {
                _state.value = s.copy(found = s.found + ad)
            }
        }
        transport.startDiscovery(RollCallProtocol.SERVICE_ID)
        stopAfter(SCAN_MS)
    }

    fun join(ad: RollCallProtocol.AdminAdvert) {
        val s = _state.value as? RollCallState.CheckingIn ?: return
        val roll = RollCallProtocol.cleanRoll(savedRoll).ifEmpty { return }
        transport.stopAdvertising()
        transport.startAdvertising(
            RollCallProtocol.studentAdvert(ad.session, roll, deviceTag, savedName),
            RollCallProtocol.SERVICE_ID,
        )
        _state.value = s.copy(joined = ad)
        // The admin only needs one sighting; staying on air a while covers a slow scan.
        stopAfter(ANNOUNCE_MS)
    }

    private fun stopAfter(ms: Long) {
        timeout?.cancel()
        timeout = scope.launch { delay(ms); stop() }
    }

    fun stop() {
        timeout?.cancel()
        if (_state.value == RollCallState.Idle) return
        runCatching {
            transport.onEndpointFound = null
            transport.stopAdvertising()
            transport.stopDiscovery()
        }
        _state.value = RollCallState.Idle
    }

    suspend fun deleteSession(id: String) {
        dao.deleteSessionAttendance(id)
        dao.deleteSessionRow(id)
    }

    /** Present and absent students for [sessionId] as CSV, everyone ever seen counting as enrolled. */
    suspend fun csv(sessionId: String, students: List<Student>): String {
        val marks = dao.attendance(sessionId).associateBy { it.roll }
        val f = RollCallProtocol::csvField
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return buildString {
            append("roll,name,status,time,note\n")
            students.forEach { st ->
                val m = marks[st.roll]
                append(listOf(st.roll, st.name, if (m != null) "present" else "absent",
                    m?.let { time.format(java.util.Date(it.seenAt)) }.orEmpty(), m?.flag.orEmpty()).joinToString(",") { f(it) })
                append('\n')
            }
        }
    }

    private companion object {
        const val KEY_DEVICE = "device"
        const val KEY_ROLL = "roll"
        const val KEY_NAME = "name"
        const val SCAN_MS = 5 * 60_000L
        const val ANNOUNCE_MS = 3 * 60_000L
    }
}
