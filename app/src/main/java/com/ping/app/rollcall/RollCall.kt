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
        /** The admin's advertisement acknowledged this phone, so it stopped announcing. */
        val confirmed: Boolean = false,
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
    private var ackLoop: Job? = null
    /** Admin side, main thread only: devices marked this session and those not yet acknowledged. */
    private val markedDevices = mutableListOf<String>()
    private val freshAcks = ArrayDeque<String>()

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

    /** The class this phone last checked in to; finding it again checks in without a tap. */
    private var savedClass: String
        get() = prefs.getString(KEY_CLASS, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_CLASS, v).apply()

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
            ackLoop = scope.launch { ackLoop(token, clean) }
        }
    }

    /** Re-advertises with fresh acks; Nearby offers no way to edit a live advertisement. */
    private suspend fun ackLoop(token: String, label: String) {
        var cursor = 0
        var last = ""
        while (true) {
            delay(ACK_EVERY_MS)
            if (markedDevices.isEmpty()) continue
            val (acks, next) = RollCallProtocol.nextAcks(freshAcks, markedDevices, cursor)
            cursor = next
            val advert = RollCallProtocol.adminAdvert(token, label, acks)
            if (advert == last) continue
            last = advert
            transport.stopAdvertising()
            transport.startAdvertising(advert, RollCallProtocol.SERVICE_ID)
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
            if (ad.device !in markedDevices) {
                markedDevices += ad.device
                freshAcks.addLast(ad.device)
            }
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
        val myAck = RollCallProtocol.ackOf(deviceTag)
        transport.onEndpointFound = onFound@{ _, name ->
            val ad = RollCallProtocol.parseAdmin(name) ?: return@onFound
            val s = _state.value as? RollCallState.CheckingIn ?: return@onFound
            when {
                s.joined?.session == ad.session -> if (myAck in ad.acks && !s.confirmed) confirmed(s)
                s.found.none { it.session == ad.session } -> {
                    _state.value = s.copy(found = s.found + ad)
                    if (s.joined == null && ad.label.isNotEmpty() && ad.label == savedClass) join(ad)
                }
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
        savedClass = ad.label
        // Stays on air until the admin acknowledges it; the timeout only covers a lost ack.
        stopAfter(ANNOUNCE_MS)
    }

    private fun confirmed(s: RollCallState.CheckingIn) {
        timeout?.cancel()
        runCatching {
            transport.onEndpointFound = null
            transport.stopAdvertising()
            transport.stopDiscovery()
        }
        _state.value = s.copy(confirmed = true)
    }

    private fun stopAfter(ms: Long) {
        timeout?.cancel()
        timeout = scope.launch { delay(ms); stop() }
    }

    fun stop() {
        timeout?.cancel()
        ackLoop?.cancel()
        markedDevices.clear()
        freshAcks.clear()
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
        const val KEY_CLASS = "class"
        const val ACK_EVERY_MS = 2_000L
        const val SCAN_MS = 5 * 60_000L
        const val ANNOUNCE_MS = 3 * 60_000L
    }
}
