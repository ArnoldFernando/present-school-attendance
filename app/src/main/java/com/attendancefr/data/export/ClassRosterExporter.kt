package com.attendancefr.data.export

import android.content.Context
import com.attendancefr.data.local.dao.StudentDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClassRosterExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val studentDao: StudentDao,
) {
    data class RosterResult(
        val file: File,
        val studentCount: Int,
    )

    /**
     * Exports roster as CSV in the exact format ExcelStudentImporter expects:
     * Full name,Student ID,Class
     */
    suspend fun export(className: String? = null): RosterResult = withContext(Dispatchers.IO) {
        val allStudents = studentDao.getAll()
        val students = if (className.isNullOrBlank()) {
            allStudents
        } else {
            allStudents.filter { student ->
                val classes = studentDao.getClassesForStudent(student.id)
                student.className == className || classes.contains(className)
            }
        }

        val dir = File(context.cacheDir, "rosters").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val classPart = className?.replace("\\s+".toRegex(), "_") ?: "all_classes"
        val file = File(dir, "roster_${classPart}_$stamp.csv")

        FileWriter(file).use { writer ->
            writer.appendLine("Full name,Student ID,Class")
            students.sortedBy { it.name.lowercase() }.forEach { s ->
                val classes = studentDao.getClassesForStudent(s.id)
                val classDisplay = classes.takeIf { it.isNotEmpty() }?.firstOrNull() ?: s.className
                val safeName = if (s.name.contains(",")) "\"${s.name}\"" else s.name
                writer.appendLine("$safeName,${s.studentId},$classDisplay")
            }
        }

        RosterResult(file, students.size)
    }
}
