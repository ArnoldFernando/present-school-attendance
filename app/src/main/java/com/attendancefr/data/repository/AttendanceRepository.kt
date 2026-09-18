package com.attendancefr.data.repository

import com.attendancefr.data.local.dao.AttendanceDao
import com.attendancefr.data.local.dao.StudentDao
import com.attendancefr.data.local.entity.AttendanceRecordEntity
import com.attendancefr.domain.model.AttendanceRecord
import com.attendancefr.domain.model.AttendanceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttendanceRepository @Inject constructor(
    private val attendanceDao: AttendanceDao,
    private val studentDao: StudentDao,
) {
    fun observeByDate(date: String): Flow<List<AttendanceRecord>> =
        combine(attendanceDao.observeByDate(date), studentDao.observeAll()) { records, students ->
            val map = students.associateBy { it.id }
            records.mapNotNull { rec ->
                val s = map[rec.studentId] ?: return@mapNotNull null
                rec.toDomain(s.name, s.studentId, s.className)
            }
        }

    fun observeByStudent(studentId: Long): Flow<List<AttendanceRecord>> =
        attendanceDao.observeByStudent(studentId).map { list ->
            val student = studentDao.getById(studentId)
            list.map {
                it.toDomain(
                    student?.name.orEmpty(),
                    student?.studentId.orEmpty(),
                    student?.className.orEmpty(),
                )
            }
        }

    fun observeInRange(from: String, to: String): Flow<List<AttendanceRecordEntity>> =
        attendanceDao.observeInRange(from, to)

    suspend fun getInRange(from: String, to: String): List<AttendanceRecordEntity> =
        attendanceDao.getInRange(from, to)

    suspend fun getForStudentOnDate(studentId: Long, date: String): AttendanceRecordEntity? =
        attendanceDao.getForStudentOnDate(studentId, date)

    suspend fun alreadyMarkedToday(studentId: Long, date: String = today()): Boolean =
        attendanceDao.getForStudentOnDate(studentId, date) != null

    /**
     * Insert or update today's record. Unique (studentId, date) so a second
     * automatic match is a no-op; a manual override *updates* the existing row.
     */
    suspend fun mark(
        studentId: Long,
        status: AttendanceStatus,
        confidence: Float?,
        isManual: Boolean,
        date: String = today(),
        timestamp: Long = System.currentTimeMillis(),
    ): MarkOutcome {
        val existing = attendanceDao.getForStudentOnDate(studentId, date)
        if (existing != null) {
            if (!isManual) return MarkOutcome.AlreadyMarked(existing)
            attendanceDao.update(
                existing.copy(
                    status = status.name,
                    matchConfidence = confidence ?: existing.matchConfidence,
                    isManual = true,
                    timestamp = timestamp,
                )
            )
            return MarkOutcome.Updated
        }
        val id = attendanceDao.insert(
            AttendanceRecordEntity(
                studentId = studentId,
                date = date,
                timestamp = timestamp,
                status = status.name,
                matchConfidence = confidence,
                isManual = isManual,
            )
        )
        return if (id == -1L) MarkOutcome.AlreadyMarked(null) else MarkOutcome.Inserted
    }

    suspend fun delete(id: Long) = attendanceDao.deleteById(id)

    sealed class MarkOutcome {
        data object Inserted : MarkOutcome()
        data object Updated : MarkOutcome()
        data class AlreadyMarked(val existing: AttendanceRecordEntity?) : MarkOutcome()
    }

    companion object {
        private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

        fun today(zone: ZoneId = ZoneId.systemDefault()): String =
            LocalDate.now(zone).format(ISO)

        fun formatMillis(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(ISO)
    }
}

private fun AttendanceRecordEntity.toDomain(
    studentName: String,
    studentRoll: String,
    className: String,
) = AttendanceRecord(
    id = id,
    studentId = studentId,
    studentName = studentName,
    studentRoll = studentRoll,
    className = className,
    date = date,
    timestamp = timestamp,
    status = AttendanceStatus.fromStorage(status),
    matchConfidence = matchConfidence,
    isManual = isManual,
)
