package com.attendancefr.data.export

import android.content.Context
import android.os.Environment
import android.util.Base64
import com.attendancefr.data.local.dao.FaceEmbeddingDao
import com.attendancefr.data.local.dao.StudentDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StudentFaceExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val studentDao: StudentDao,
    private val embeddingDao: FaceEmbeddingDao,
) {
    data class ExportSummary(
        val file: File,
        val studentCount: Int,
        val embeddingCount: Int,
    )

    suspend fun export(): ExportSummary = withContext(Dispatchers.IO) {
        val students = studentDao.getAll()
        val json = JSONObject()
        json.put("version", 1)
        json.put("exportedAt", System.currentTimeMillis())
        json.put("app", "AttendanceFR")

        val studentsArray = JSONArray()
        var totalEmbeddings = 0

        students.forEach { student ->
            val studentJson = JSONObject()
            studentJson.put("studentId", student.studentId)
            studentJson.put("name", student.name)
            studentJson.put("className", student.className)
            studentJson.put("dateEnrolled", student.dateEnrolled)

            val classes = studentDao.getClassesForStudent(student.id)
            val classesArray = JSONArray()
            classes.forEach { classesArray.put(it) }
            studentJson.put("classNames", classesArray)

            val embeddings = embeddingDao.getForStudent(student.id)
            val embeddingsArray = JSONArray()
            embeddings.forEach { emb ->
                val embJson = JSONObject()
                embJson.put("dateAdded", emb.dateAdded)
                embJson.put("vector", Base64.encodeToString(emb.embeddingVector, Base64.NO_WRAP))
                embeddingsArray.put(embJson)
                totalEmbeddings++
            }
            studentJson.put("embeddings", embeddingsArray)
            studentsArray.put(studentJson)
        }

        json.put("students", studentsArray)

        val dir = File(context.cacheDir, "student_exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "attendancefr_students_faces_$stamp.json")

        FileOutputStream(file).use { out ->
            out.write(json.toString(2).toByteArray())
        }

        ExportSummary(file, students.size, totalEmbeddings)
    }
}

