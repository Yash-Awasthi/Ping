package com.ping.app.rollcall

import com.ping.app.service.NearbyTransport
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

class RollCallTest {

    private val clock = VirtualClock()
    private val radio = FakeRadio()
    private val dao = FakeDao()
    private val prefs = object : RollCallPrefs {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }
    private val rollCall = RollCall(radio, dao, prefs, clock)

    private fun take(): String {
        rollCall.startTaking("Physics")
        clock.runCurrent()
        return (rollCall.state.value as RollCallState.Taking).sessionId
    }

    private fun token() = RollCallProtocol.parseAdmin(radio.advertising!!)!!.session

    private fun answer(roll: String, device: String) {
        radio.onEndpointFound!!("e-$device", RollCallProtocol.studentAdvert(token(), roll, device, "Ann"))
        clock.runCurrent()
    }

    private fun acks() = RollCallProtocol.parseAdmin(radio.advertising!!)!!.acks

    @Test
    fun `a known roll from a new phone is flagged`() {
        take(); answer("A1", "aaaaaaaa")
        val second = take(); answer("A1", "bbbbbbbb")
        assertEquals(Attendance.FLAG_OTHER_PHONE, dao.marks[second to "A1"]!!.flag)
        assertEquals("aaaaaaaa", dao.students["A1"]!!.device)
    }

    @Test
    fun `an imported roll binds the first phone that answers`() {
        runBlocking { rollCall.importRoster("roll,name\nA1,Ann") }
        val s = take(); answer("A1", "aaaaaaaa")
        assertEquals("aaaaaaaa", dao.students["A1"]!!.device)
        assertEquals("", dao.marks[s to "A1"]!!.flag)
    }

    @Test
    fun `after an import an unknown roll is flagged and not enrolled`() {
        runBlocking { rollCall.importRoster("roll,name\nA1,Ann") }
        val s = take(); answer("Z9", "aaaaaaaa")
        assertNull(dao.students["Z9"])
        assertEquals(RollCallProtocol.FLAG_NEW_ROLL, dao.marks[s to "Z9"]!!.flag)
    }

    @Test
    fun `a device leaves the ack rotation after its rounds`() {
        take()
        answer("A1", "aaaaaaaa")
        clock.advanceBy(RollCall.ACK_EVERY_MS * 2)
        answer("A2", "bbbbbbbb")
        clock.advanceBy(RollCall.ACK_EVERY_MS)
        assertEquals(setOf("aaaaaa", "bbbbbb"), acks())

        clock.advanceBy(RollCall.ACK_EVERY_MS * RollCall.ACK_ROUNDS)
        answer("A3", "cccccccc")
        clock.advanceBy(RollCall.ACK_EVERY_MS)
        assertEquals(setOf("cccccc"), acks())
    }

    @Test
    fun `csv lists absentees and unlisted marks`() {
        runBlocking { rollCall.importRoster("roll,name\nA1,Ann\nA2,Bob") }
        val s = take(); answer("A1", "aaaaaaaa"); answer("Z9", "bbbbbbbb")
        val rows = runBlocking { rollCall.csv(s, dao.students.values.sortedBy { it.roll }) }.lines()
        assertTrue(rows[1].startsWith("A1,Ann,present,"))
        assertEquals("A2,Bob,absent,,", rows[2])
        assertTrue(rows[3].startsWith("Z9,Z9,present,") && rows[3].endsWith(",new roll"))
    }

    @Test
    fun `stopping while the session is saved never starts the radios`() {
        dao.gate = CompletableDeferred()
        rollCall.startTaking("Physics")
        clock.runCurrent()
        rollCall.stop()
        dao.gate!!.complete(Unit)
        clock.advanceBy(RollCall.ACK_EVERY_MS * 3)
        assertNull(radio.advertising)
        assertFalse(radio.discovering)
        assertEquals(0, radio.starts)
        assertEquals(RollCallState.Idle, rollCall.state.value)
    }

    @Test
    fun `stop leaves radios it did not start alone`() {
        val swap: (String, String) -> Unit = { _, _ -> }
        radio.onEndpointFound = swap
        radio.advertising = "swap"
        rollCall.stop()
        assertSame(swap, radio.onEndpointFound)
        assertEquals("swap", radio.advertising)
    }

    @Test
    fun `stop ends the ack loop`() {
        take(); answer("A1", "aaaaaaaa")
        rollCall.stop()
        val starts = radio.starts
        clock.advanceBy(RollCall.ACK_EVERY_MS * 3)
        assertEquals(starts, radio.starts)
        assertNull(radio.advertising)
    }
}

/** Virtual time: tasks run only when the test calls [runCurrent] or [advanceBy]. */
@OptIn(InternalCoroutinesApi::class)
private class VirtualClock : CoroutineDispatcher(), Delay {
    private class Task(val at: Long, val seq: Long, val block: Runnable)
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
    private var now = 0L
    private var seq = 0L

    override fun dispatch(context: CoroutineContext, block: Runnable) { queue += Task(now, seq++, block) }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        queue += Task(now + timeMillis, seq++) { continuation.resume(Unit) }
    }

    fun runCurrent() = advanceBy(0)

    fun advanceBy(ms: Long) {
        val end = now + ms
        while (true) {
            val t = queue.peek()?.takeIf { it.at <= end } ?: break
            queue.poll()
            now = t.at
            t.block.run()
        }
        now = end
    }
}

private class FakeRadio : NearbyTransport {
    var advertising: String? = null
    var discovering = false
    var starts = 0
    override var onPayloadReceived: ((String, ByteArray) -> Unit)? = null
    override var onConnected: ((String, String, Boolean) -> Unit)? = null
    override var onDisconnected: ((String) -> Unit)? = null
    override var onEndpointFound: ((String, String) -> Unit)? = null
    override var onConnectionInitiated: ((String, String) -> Unit)? = null
    override fun sendBytes(endpointId: String, data: ByteArray) {}
    override fun startAdvertising(localName: String, serviceId: String, star: Boolean) { advertising = localName; starts++ }
    override fun startDiscovery(serviceId: String, star: Boolean) { discovering = true }
    override fun requestConnection(localName: String, endpointId: String) {}
    override fun acceptConnection(endpointId: String) {}
    override fun rejectConnection(endpointId: String) {}
    override fun stopAdvertising() { advertising = null }
    override fun stopDiscovery() { discovering = false }
    override fun stopAllEndpoints() {}
}

private class FakeDao : RollCallDao {
    val students = sortedMapOf<String, Student>()
    val marks = LinkedHashMap<Pair<String, String>, Attendance>()
    val sessions = mutableListOf<RollSession>()
    /** When set, saving a session waits for it, like a slow disk. */
    var gate: CompletableDeferred<Unit>? = null

    override fun observeStudents(): Flow<List<Student>> = MutableStateFlow(students.values.toList())
    override suspend fun student(roll: String) = students[roll]
    override suspend fun upsertStudent(student: Student) { students[student.roll] = student }
    override suspend fun deleteStudent(roll: String) { students.remove(roll) }
    override suspend fun renameStudent(roll: String, name: String) {
        students[roll]?.let { students[roll] = it.copy(name = name) }
    }
    override fun observeRates(): Flow<List<RollRate>> = MutableStateFlow(emptyList())
    override fun observeSessionCount(): Flow<Int> = MutableStateFlow(sessions.size)
    override suspend fun insertSession(session: RollSession) { gate?.await(); sessions += session }
    override fun observeSessions(): Flow<List<SessionSummary>> = MutableStateFlow(emptyList())
    override suspend fun session(id: String) = sessions.firstOrNull { it.id == id }
    override fun observeAttendance(sessionId: String): Flow<List<Attendance>> = MutableStateFlow(emptyList())
    override suspend fun attendance(sessionId: String) = marks.values.filter { it.sessionId == sessionId }
    override suspend fun mark(attendance: Attendance) { marks.putIfAbsent(attendance.sessionId to attendance.roll, attendance) }
    override suspend fun unmark(sessionId: String, roll: String) { marks.remove(sessionId to roll) }
    override suspend fun deleteSessionRow(id: String) { sessions.removeIf { it.id == id } }
    override suspend fun deleteSessionAttendance(id: String) { marks.keys.removeIf { it.first == id } }
}
