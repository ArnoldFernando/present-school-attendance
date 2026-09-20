package com.attendancefr.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.attendancefr.data.local.entity.StudentClassCrossRef
import com.attendancefr.data.local.entity.StudentEntity
import com.attendancefr.data.local.relation.StudentWithClasses
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentDao {
    @Transaction
    @Query("SELECT * FROM students ORDER BY name COLLATE NOCASE ASC")
    fun observeAllWithClasses(): Flow<List<StudentWithClasses>>

    @Transaction
    @Query("""
        SELECT s.* FROM students s
        INNER JOIN student_classes sc ON s.id = sc.studentId
        WHERE sc.className = :className
        ORDER BY s.name COLLATE NOCASE ASC
    """)
    fun observeByClassWithClasses(className: String): Flow<List<StudentWithClasses>>

    @Query("SELECT * FROM students WHERE id = :id")
    suspend fun getById(id: Long): StudentEntity?

    @Query("SELECT * FROM students WHERE studentId = :studentId LIMIT 1")
    suspend fun getByRoll(studentId: String): StudentEntity?

    @Query("SELECT * FROM students")
    suspend fun getAll(): List<StudentEntity>

    @Query("""
        SELECT s.* FROM students s
        INNER JOIN student_classes sc ON s.id = sc.studentId
        WHERE sc.className = :className
    """)
    suspend fun getByClass(className: String): List<StudentEntity>

    @Query("""
        SELECT * FROM students 
        WHERE name LIKE '%' || :q || '%' OR studentId LIKE '%' || :q || '%' 
        ORDER BY name COLLATE NOCASE ASC
    """)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudentClass(crossRef: StudentClassCrossRef)

    @Query("DELETE FROM student_classes WHERE studentId = :studentId")
    suspend fun deleteStudentClasses(studentId: Long)

    @Query("SELECT className FROM student_classes WHERE studentId = :studentId")
    suspend fun getClassesForStudent(studentId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertAll(students: List<StudentEntity>): List<Long>
    

}
