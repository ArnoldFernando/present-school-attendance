package com.attendancefr.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.attendancefr.data.local.entity.StudentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentDao {
    @Query("SELECT * FROM students ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students WHERE className = :className ORDER BY name COLLATE NOCASE ASC")
    fun observeByClass(className: String): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students WHERE id = :id")
    suspend fun getById(id: Long): StudentEntity?

    @Query("SELECT * FROM students WHERE studentId = :studentId LIMIT 1")
    suspend fun getByRoll(studentId: String): StudentEntity?

    @Query("SELECT * FROM students")
    suspend fun getAll(): List<StudentEntity>

    @Query("SELECT * FROM students WHERE className = :className")
    suspend fun getByClass(className: String): List<StudentEntity>

    @Query(
        "SELECT * FROM students WHERE name LIKE '%' || :q || '%' OR studentId LIKE '%' || :q || '%' ORDER BY name COLLATE NOCASE ASC"
    )
    fun search(q: String): Flow<List<StudentEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(student: StudentEntity): Long

    @Update
    suspend fun update(student: StudentEntity)

    @Delete
    suspend fun delete(student: StudentEntity)

    @Query("DELETE FROM students WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM students")
    suspend fun count(): Int
}
