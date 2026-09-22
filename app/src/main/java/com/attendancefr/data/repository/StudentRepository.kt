package com.attendancefr.data.repository

import com.attendancefr.data.local.EmbeddingCodec
import com.attendancefr.data.local.dao.EmbeddingCount
import com.attendancefr.data.local.dao.FaceEmbeddingDao
import com.attendancefr.data.local.dao.StudentDao
import com.attendancefr.data.local.entity.FaceEmbeddingEntity
import com.attendancefr.data.local.entity.StudentClassCrossRef
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
        combine(studentDao.observeAllWithClasses(), embeddingDao.observeCounts()) { students, counts ->
            val map = counts.associate { it.studentId to it.cnt }
            students.map { it.toDomain(map[it.student.id] ?: 0) }
        }

    fun observeByClass(className: String): Flow<List<Student>> =
        combine(studentDao.observeByClassWithClasses(className), embeddingDao.observeCounts()) { students, counts ->
            val map = counts.associate { it.studentId to it.cnt }
            students.map { it.toDomain(map[it.student.id] ?: 0) }
        }

    fun search(query: String): Flow<List<Student>> =
        combine(studentDao.search(query), embeddingDao.observeCounts()) { students, counts ->
            val map = counts.associate { it.studentId to it.cnt }
            students.map { it.toDomain(map[it.id] ?: 0) }
        }

    suspend fun getById(id: Long): Student? {
        val entity = studentDao.getById(id) ?: return null
        val count = embeddingDao.countForStudent(id)
        val classes = studentDao.getClassesForStudent(id)
        return entity.toDomain(count, classes)
    }

    suspend fun getByRoll(roll: String): StudentEntity? = studentDao.getByRoll(roll)

    suspend fun getAllEntities(): List<StudentEntity> = studentDao.getAll()

    suspend fun getByClass(className: String): List<StudentEntity> = studentDao.getByClass(className)

    suspend fun enroll(
        studentId: String,
        name: String,
        classNames: List<String>,
        embeddings: List<FloatArray>,
        photoPath: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val existing = studentDao.getByRoll(studentId)
        if (existing != null) {
            throw IllegalArgumentException("A student with ID \"$studentId\" already exists.")
        }
        val primaryClass = classNames.firstOrNull()?.trim() ?: ""
        val id = studentDao.insert(
            StudentEntity(
                studentId = studentId.trim(),
                name = name.trim(),
                className = primaryClass,
                dateEnrolled = now,
                photoPath = photoPath,
        )
        )
        classNames.distinct().forEach { cls ->
            studentDao.insertStudentClass(StudentClassCrossRef(id, cls.trim()))
        }
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

    suspend fun importIfNotExists(
        studentId: String,
        name: String,
        classNames: List<String>,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (studentDao.getByRoll(studentId) != null) return false

        val entity = StudentEntity(
            studentId = studentId,
            name = name,
            className = classNames.firstOrNull().orEmpty(),
            dateEnrolled = now,
        )
        val id = studentDao.insert(entity)
        classNames.distinct().forEach { className ->
            studentDao.insertStudentClass(StudentClassCrossRef(studentId = id, className = className))
        }
        return true
    }

    suspend fun updateDetails(id: Long, studentId: String, name: String, classNames: List<String>, photoPath: String? = null) {
        val current = studentDao.getById(id) ?: return
        studentDao.update(
            current.copy(
                studentId = studentId.trim(),
                photoPath = photoPath ?: current.photoPath,
                name = name.trim(),
                className = classNames.firstOrNull()?.trim() ?: current.className,
            )
        )
        studentDao.deleteStudentClasses(id)
        classNames.distinct().forEach { cls ->
            studentDao.insertStudentClass(StudentClassCrossRef(id, cls.trim()))
        }
    }

    suspend fun replaceEmbeddings(studentId: Long, embeddings: List<FloatArray>, photoPath: String? = null, now: Long = System.currentTimeMillis()) {
        if (photoPath != null) {
            val student = studentDao.getById(studentId)
            if (student != null) {
                studentDao.update(student.copy(photoPath = photoPath))
            }
        }
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
        studentDao.deleteStudentClasses(id)
        studentDao.deleteById(id)
    }


    suspend fun addClassToStudent(studentId: Long, className: String) {
        studentDao.insertStudentClass(StudentClassCrossRef(studentId, className.trim()))
    }

    suspend fun getClassesForStudent(studentId: Long): List<String> {
        return studentDao.getClassesForStudent(studentId)
    }

    suspend fun getActiveClassNames(): List<String> = studentDao.getActiveClassNames()
    suspend fun getEmbeddingsForStudent(studentId: Long): List<FloatArray> =
        embeddingDao.getForStudent(studentId).map { EmbeddingCodec.toFloats(it.embeddingVector) }

    suspend fun getAllEmbeddings(): List<Pair<Long, FloatArray>> =
        embeddingDao.getAll().map { it.studentId to EmbeddingCodec.toFloats(it.embeddingVector) }

    private fun com.attendancefr.data.local.relation.StudentWithClasses.toDomain(count: Int) = Student(
        id = student.id,
        studentId = student.studentId,
        name = student.name,
        className = student.className,
        classNames = classes.map { it.className },
        dateEnrolled = student.dateEnrolled,
        embeddingCount = count,
        photoPath = student.photoPath,
    )

    private fun StudentEntity.toDomain(count: Int, classes: List<String>) = Student(
        id = id,
        studentId = studentId,
        name = name,
        className = className,
        classNames = classes,
        dateEnrolled = dateEnrolled,
        embeddingCount = count,
        photoPath = photoPath,
    )

    private fun StudentEntity.toDomain(count: Int) = Student(
        id = id,
        studentId = studentId,
        name = name,
        className = className,
        classNames = emptyList(),
        dateEnrolled = dateEnrolled,
        embeddingCount = count,
        photoPath = photoPath,
    )
}







