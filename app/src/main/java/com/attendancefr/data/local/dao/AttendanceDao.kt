package com.attendancefr.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.attendancefr.data.local.entity.AttendanceRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance_records ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records WHERE date = :date ORDER BY timestamp DESC")
    fun observeByDate(date: String): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records WHERE studentId = :studentId ORDER BY date DESC")
    fun observeByStudent(studentId: Long): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records WHERE date = :date")
    suspend fun getByDate(date: String): List<AttendanceRecordEntity>

    @Query("SELECT * FROM attendance_records WHERE studentId = :studentId AND date = :date LIMIT 1")
    suspend fun getForStudentOnDate(studentId: Long, date: String): AttendanceRecordEntity?

    @Query(
        "SELECT * FROM attendance_records WHERE date BETWEEN :from AND :to ORDER BY date ASC, timestamp ASC"
    )
    suspend fun getInRange(from: String, to: String): List<AttendanceRecordEntity>

    @Query(
        "SELECT * FROM attendance_records WHERE date BETWEEN :from AND :to ORDER BY date ASC, timestamp ASC"
    )
    fun observeInRange(from: String, to: String): Flow<List<AttendanceRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: AttendanceRecordEntity): Long

    @Update
    suspend fun update(record: AttendanceRecordEntity)

    @Query("DELETE FROM attendance_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM attendance_records WHERE studentId = :studentId")
    suspend fun deleteForStudent(studentId: Long)
}
