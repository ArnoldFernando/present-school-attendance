package com.attendancefr.data.imports

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.attendancefr.data.local.dao.FaceEmbeddingDao
import com.attendancefr.data.local.dao.StudentDao
import com.attendancefr.data.local.entity.FaceEmbeddingEntity
import com.attendancefr.data.local.entity.StudentClassCrossRef
import com.attendancefr.data.local.entity.StudentEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StudentFaceImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val studentDao: StudentDao,
    private val embeddingDao: FaceEmbeddingDao,
) {
    data class ImportResult(
        val studentsImported: Int,
        val studentsSkipped: Int,
        val embeddingsImported: Int,
    )

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val jsonString = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8)
            ?: throw IllegalArgumentException("Cannot read file")

        val root = JSONObject(jsonString)
        val version = root.optInt("version", 1)
        if (version > 1) {
            throw IllegalArgumentException("Unsupported export version: $version")
        }

        val studentsArray = root.getJSONArray("students")
        var imported = 0
        var skipped = 0
        var embeddingsCount = 0

        for (i in 0 until studentsArray.length()) {
            val studentJson = studentsArray.getJSONObject(i)
            val studentId = studentJson.getString("studentId")
            val name = studentJson.getString("name")

            val existing = studentDao.getByRoll(studentId)
            val studentDbId = if (existing != null) {
                skipped++
                existing.id
            } else {
                val entity = StudentEntity(
                    studentId = studentId,
                    name = name,
                    className = studentJson.optString("className", ""),
                    dateEnrolled = studentJson.optLong("dateEnrolled", System.currentTimeMillis()),
                )
                val newId = studentDao.insert(entity)
                imported++

                val classesArray = studentJson.optJSONArray("classNames")
                if (classesArray != null) {
                    for (j in 0 until classesArray.length()) {
                        studentDao.insertStudentClass(
                            StudentClassCrossRef(newId, classesArray.getString(j))
                        )
                    }
                }
                newId
            }

            val embeddingsArray = studentJson.optJSONArray("embeddings")
            if (embeddingsArray != null) {
                for (j in 0 until embeddingsArray.length()) {
                    val embJson = embeddingsArray.getJSONObject(j)
                    val vectorBytes = Base64.decode(embJson.getString("vector"), Base64.DEFAULT)
                    val entity = FaceEmbeddingEntity(
                        studentId = studentDbId,
                        embeddingVector = vectorBytes,
                        dateAdded = embJson.optLong("dateAdded", System.currentTimeMillis()),
                    )
                    embeddingDao.insert(entity)
                    embeddingsCount++
                }
            }
        }

        ImportResult(imported, skipped, embeddingsCount)
    }
}
