package com.ping.app.rollcall

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A student the admin's phone has seen; [device] is the phone they first checked in from. */
@Entity(tableName = "students")
data class Student(
    @androidx.room.PrimaryKey val roll: String,
    val name: String,
    val device: String,
)

@Entity(tableName = "roll_sessions")
data class RollSession(
    @androidx.room.PrimaryKey val id: String,
    val label: String,
    val startedAt: Long,
)

/** One student marked in one session. [flag] notes a manual mark or a different phone. */
@Entity(tableName = "attendance", primaryKeys = ["sessionId", "roll"])
data class Attendance(
    val sessionId: String,
    val roll: String,
    val seenAt: Long,
    val flag: String = "",
) {
    companion object {
        const val FLAG_MANUAL = "manual"
        const val FLAG_OTHER_PHONE = "other phone"
    }
}

data class SessionSummary(val id: String, val label: String, val startedAt: Long, val present: Int)

@Dao
interface RollCallDao {
    @Query("SELECT * FROM students ORDER BY roll")
    fun observeStudents(): Flow<List<Student>>

    @Query("SELECT * FROM students WHERE roll = :roll")
    suspend fun student(roll: String): Student?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStudent(student: Student)

    @Query("DELETE FROM students WHERE roll = :roll")
    suspend fun deleteStudent(roll: String)

    @Insert
    suspend fun insertSession(session: RollSession)

    @Query(
        "SELECT s.id, s.label, s.startedAt, COUNT(a.roll) AS present FROM roll_sessions s " +
            "LEFT JOIN attendance a ON a.sessionId = s.id GROUP BY s.id ORDER BY s.startedAt DESC",
    )
    fun observeSessions(): Flow<List<SessionSummary>>

    @Query("SELECT * FROM roll_sessions WHERE id = :id")
    suspend fun session(id: String): RollSession?

    @Query("SELECT * FROM attendance WHERE sessionId = :sessionId")
    fun observeAttendance(sessionId: String): Flow<List<Attendance>>

    @Query("SELECT * FROM attendance WHERE sessionId = :sessionId")
    suspend fun attendance(sessionId: String): List<Attendance>

    /** First sighting wins; later sightings of the same student change nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun mark(attendance: Attendance)

    @Query("DELETE FROM attendance WHERE sessionId = :sessionId AND roll = :roll")
    suspend fun unmark(sessionId: String, roll: String)

    @Query("DELETE FROM roll_sessions WHERE id = :id")
    suspend fun deleteSessionRow(id: String)

    @Query("DELETE FROM attendance WHERE sessionId = :id")
    suspend fun deleteSessionAttendance(id: String)
}
