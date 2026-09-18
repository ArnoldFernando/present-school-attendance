package com.attendancefr.data.repository

import com.attendancefr.data.local.EmbeddingCodec
import com.attendancefr.data.local.dao.EmbeddingCount
import com.attendancefr.data.local.dao.FaceEmbeddingDao
import com.attendancefr.data.local.dao.StudentDao
import com.attendancefr.data.local.entity.FaceEmbeddingEntity
import com.attendancefr.data.local.entity.StudentEntity
import com.attendancefr.domain.model.Student
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StudentRepository @Inject constructor(
    private val studentDao: StudentDao,
    private val embeddingDao: FaceEmbeddingDao,
) {
    fun observeStudents(): Flow<List<Student>> =
        combine(studentDao.observeAll(), embeddingDao.observeCounts()) { students, counts ->
            val map = counts.associate { it.studentId to it.cnt }
            students.map { it.toDomain(map[it.id] ?: 0) }
        }

    fun observeByClass(className: String): Flow<List<Student>> =
        combine(studentDao.observeByClass(className), embeddingDao.observeCounts()) { students, counts ->
            val map = counts.associate { it.studentId to it.cnt }
            students.map { it.toDomain(map[it.id] ?: 0) }
        }

    fun search(query: String): Flow<List<Student>> =
        combine(studentDao.search(query), embeddingDao.observeCounts()) { students, counts ->
            val map = counts.associate { it.studentId to it.cnt }
            students.map { it.toDomain(map[it.id] ?: 0) }
        }

    suspend fun getById(id: Long): Student? {
        val entity = studentDao.getById(id) ?: return null
        val count = embeddingDao.countForStudent(id)
        return entity.toDomain(count)
    }

    suspend fun getByRoll(roll: String): StudentEntity? = studentDao.getByRoll(roll)

    suspend fun getAllEntities(): List<StudentEntity> = studentDao.getAll()

    suspend fun getByClass(className: String): List<StudentEntity> = studentDao.getByClass(className)

    suspend fun enroll(
        studentId: String,
        name: String,
        className: String,
        embeddings: List<FloatArray>,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val existing = studentDao.getByRoll(studentId)
        if (existing != null) {
            throw IllegalArgumentException("A student with ID \"$studentId\" already exists.")
        }
        val id = studentDao.insert(
            StudentEntity(
                studentId = studentId.trim(),
                name = name.trim(),
                className = className.trim(),
                dateEnrolled = now,
            )
        )
        embeddingDao.insertAll(
            embeddings.map { vec ->
                FaceEmbeddingEntity(
                    studentId = id,
                    embeddingVector = EmbeddingCodec.toBytes(vec),
                    dateAdded = now,
                )
            }
        )
        return id
    }

    suspend fun updateDetails(id: Long, studentId: String, name: String, className: String) {
        val current = studentDao.getById(id) ?: return
        studentDao.update(
            current.copy(
                studentId = studentId.trim(),
                name = name.trim(),
                className = className.trim(),
            )
        )
    }

    suspend fun replaceEmbeddings(studentId: Long, embeddings: List<FloatArray>, now: Long = System.currentTimeMillis()) {
        embeddingDao.deleteForStudent(studentId)
        embeddingDao.insertAll(
            embeddings.map { vec ->
                FaceEmbeddingEntity(
                    studentId = studentId,
                    embeddingVector = EmbeddingCodec.toBytes(vec),
                    dateAdded = now,
                )
            }
        )
    }

    suspend fun delete(id: Long) {
        studentDao.deleteById(id)
    }

    suspend fun getEmbeddingsForStudent(studentId: Long): List<FloatArray> =
        embeddingDao.getForStudent(studentId).map { EmbeddingCodec.toFloats(it.embeddingVector) }

    suspend fun getAllEmbeddings(): List<Pair<Long, FloatArray>> =
        embeddingDao.getAll().map { it.studentId to EmbeddingCodec.toFloats(it.embeddingVector) }

    private fun StudentEntity.toDomain(count: Int) = Student(
        id = id,
        studentId = studentId,
        name = name,
        className = className,
        dateEnrolled = dateEnrolled,
        embeddingCount = count,
    )
}
