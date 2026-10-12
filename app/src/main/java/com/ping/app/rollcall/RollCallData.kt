package com.ping.app.rollcall

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** A student the admin's phone has seen; [device] is the phone they first checked in from. */
@Entity(tableName = "students")
data class Student(
    @androidx.room3.PrimaryKey val roll: String,
    val name: String,
    val device: String,
)

@Entity(tableName = "roll_sessions")
data class RollSession(
    @androidx.room3.PrimaryKey val id: String,
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

/** How many sessions [roll] was marked present in. */
data class RollRate(val roll: String, val present: Int)

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

    @Query("UPDATE students SET name = :name WHERE roll = :roll")
    suspend fun renameStudent(roll: String, name: String)

    /** Adds class-list rows; existing students keep their bound phone and only take the new name. */
    @androidx.room3.Transaction
    suspend fun importRoster(rows: List<Pair<String, String>>) {
        rows.forEach { (roll, name) ->
            if (student(roll) == null) upsertStudent(Student(roll, name, "")) else renameStudent(roll, name)
        }
    }

    @Query("SELECT roll, COUNT(*) AS present FROM attendance GROUP BY roll")
    fun observeRates(): Flow<List<RollRate>>

    @Query("SELECT COUNT(*) FROM roll_sessions")
    fun observeSessionCount(): Flow<Int>

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
