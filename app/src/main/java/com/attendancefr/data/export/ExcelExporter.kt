package com.attendancefr.data.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.attendancefr.data.local.dao.AttendanceDao
import com.attendancefr.data.local.dao.StudentDao
import com.attendancefr.data.local.entity.AttendanceRecordEntity
import com.attendancefr.data.local.entity.StudentEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExcelExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val studentDao: StudentDao,
    private val attendanceDao: AttendanceDao,
) {
    data class ExportRequest(
        val fromDate: String,
        val toDate: String,
        val className: String?,
        val allClassNames: List<String> = emptyList(),
    )

    data class ExportResult(
        val file: File,
        val publicUri: Uri? = null,
    )

    fun exportsDir(): File = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "exports").apply { mkdirs() }
    } else {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Present").apply { mkdirs() }
    }

    suspend fun export(request: ExportRequest): ExportResult {
        val allRecords = attendanceDao.getInRange(request.fromDate, request.toDate)

        val classesToExport = if (!request.className.isNullOrBlank()) {
            listOf(request.className)
        } else if (request.allClassNames.isNotEmpty()) {
            request.allClassNames
        } else {
            allRecords.map { it.className }.distinct().filter { it.isNotBlank() }.sorted()
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val classPart = request.className?.replace("\\s+".toRegex(), "_") ?: "all"
        val fileName = "attendance_${classPart}_${request.fromDate}_to_${request.toDate}_$stamp.xlsx"

        XSSFWorkbook().use { wb ->
            val headerStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
                fillForegroundColor = IndexedColors.DARK_BLUE.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
                alignment = HorizontalAlignment.CENTER
                val font = wb.createFont()
                font.bold = true
                font.color = IndexedColors.WHITE.index
                setFont(font)
            }
            val presentStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
                fillForegroundColor = IndexedColors.LIGHT_GREEN.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }
            val absentStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
                fillForegroundColor = IndexedColors.ROSE.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }
            val lateStyle = (wb.createCellStyle() as XSSFCellStyle).apply {
                fillForegroundColor = IndexedColors.LIGHT_YELLOW.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }

            val usedSheetNames = mutableSetOf<String>()
            usedSheetNames.add("Summary")

            // One detail sheet per class
            classesToExport.forEach { className ->
                val studentsInClass = studentDao.getByClass(className)
                val studentMap = studentsInClass.associateBy { it.id }
                val recordsInClass = allRecords.filter {
                    it.className == className && studentMap.containsKey(it.studentId)
                }

                if (studentsInClass.isNotEmpty() || recordsInClass.isNotEmpty()) {
                    writeDetailSheet(
                        wb = wb,
                        sheetName = sanitizeSheetName(className, usedSheetNames),
                        className = className,
                        students = studentsInClass,
                        records = recordsInClass,
                        studentMap = studentMap,
                        headerStyle = headerStyle,
                        presentStyle = presentStyle,
                        absentStyle = absentStyle,
                        lateStyle = lateStyle,
                    )
                }
            }

            // Summary sheet
            val summaryStudents = if (!request.className.isNullOrBlank()) {
                studentDao.getByClass(request.className)
            } else {
                studentDao.getAll()
            }
            val summaryStudentMap = summaryStudents.associateBy { it.id }
            val summaryRecords = if (!request.className.isNullOrBlank()) {
                allRecords.filter { it.className == request.className }
            } else {
                allRecords
            }

            writeSummarySheet(
                wb = wb,
                students = summaryStudents,
                records = summaryRecords,
                studentMap = summaryStudentMap,
                request = request,
                headerStyle = headerStyle,
            )

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Present")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Failed to create MediaStore entry")

                resolver.openOutputStream(uri)?.use { wb.write(it) }
                    ?: throw IllegalStateException("Failed to open output stream")

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                val privateFile = File(exportsDir(), fileName)
                FileOutputStream(privateFile).use { wb.write(it) }

                ExportResult(file = privateFile, publicUri = uri)
            } else {
                val file = File(exportsDir(), fileName)
                FileOutputStream(file).use { wb.write(it) }
                ExportResult(file = file)
            }
        }
    }

    private fun sanitizeSheetName(name: String, usedNames: MutableSet<String>): String {
        // Excel sheet names: max 31 chars, cannot contain: \ / ? * [ ]
        val base = name.replace(Regex("[\\\\/\\?\\*\\[\\]]"), "_").take(31)
        if (base !in usedNames) {
            usedNames.add(base)
            return base
        }
        (2..99).forEach { i ->
            val candidate = "${base.take(29 - i.toString().length)}_$i"
            if (candidate !in usedNames) {
                usedNames.add(candidate)
                return candidate
            }
        }
        val fallback = "${base.take(20)}_${System.currentTimeMillis() % 10000}"
        usedNames.add(fallback)
        return fallback
    }

    private fun writeDetailSheet(
        wb: XSSFWorkbook,
        sheetName: String,
        className: String,
        students: List<StudentEntity>,
        records: List<AttendanceRecordEntity>,
        studentMap: Map<Long, StudentEntity>,
        headerStyle: XSSFCellStyle,
        presentStyle: XSSFCellStyle,
        absentStyle: XSSFCellStyle,
        lateStyle: XSSFCellStyle,
    ) {
        val sheet = wb.createSheet(sheetName)
        val headers = listOf(
            "Student ID", "Name", "Class", "Date", "Time", "Status", "Match Confidence", "Marked By"
        )
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply {
                setCellValue(h)
                cellStyle = headerStyle
            }
        }

        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        var rowIdx = 1
        val sorted = records.sortedWith(compareBy({ it.date }, { studentMap[it.studentId]?.name.orEmpty() }))
        for (rec in sorted) {
            val s = studentMap[rec.studentId] ?: continue
            val row = sheet.createRow(rowIdx++)
            row.createCell(0).setCellValue(s.studentId)
            row.createCell(1).setCellValue(s.name)
            row.createCell(2).setCellValue(rec.className)
            row.createCell(3).setCellValue(rec.date)
            row.createCell(4).setCellValue(timeFmt.format(Date(rec.timestamp)))
            val statusCell = row.createCell(5)
            statusCell.setCellValue(rec.status)
            statusCell.cellStyle = when (rec.status) {
                "Present" -> presentStyle
                "Absent" -> absentStyle
                "Late" -> lateStyle
                else -> presentStyle
            }
            row.createCell(6).setCellValue(
                rec.matchConfidence?.let { String.format(Locale.US, "%.3f", it) } ?: ""
            )
            row.createCell(7).setCellValue(if (rec.isManual) "Manual" else "Face match")
        }

        val datesWithRecords = records.map { it.date }.toSortedSet()
        val recordedPairs = records.map { it.studentId to it.date }.toSet()
        for (date in datesWithRecords) {
            for (s in students) {
                if ((s.id to date) in recordedPairs) continue
                val row = sheet.createRow(rowIdx++)
                row.createCell(0).setCellValue(s.studentId)
                row.createCell(1).setCellValue(s.name)
                row.createCell(2).setCellValue(className)
                row.createCell(3).setCellValue(date)
                row.createCell(4).setCellValue("")
                val statusCell = row.createCell(5)
                statusCell.setCellValue("Absent")
                statusCell.cellStyle = absentStyle
                row.createCell(6).setCellValue("")
                row.createCell(7).setCellValue("Implied")
            }
        }

        headers.indices.forEach { sheet.setColumnWidth(it, 18 * 256) }
        sheet.setColumnWidth(1, 28 * 256)
    }

    private fun writeSummarySheet(
        wb: XSSFWorkbook,
        students: List<StudentEntity>,
        records: List<AttendanceRecordEntity>,
        studentMap: Map<Long, StudentEntity>,
        request: ExportRequest,
        headerStyle: XSSFCellStyle,
    ) {
        val sheet = wb.createSheet("Summary")
        val title = sheet.createRow(0)
        title.createCell(0).setCellValue(
            "Attendance summary  ${request.fromDate} → ${request.toDate}" +
                (request.className?.let { "  ·  $it" } ?: "  ·  All classes")
        )

        val headers = listOf(
            "Student ID", "Name", "Class", "Present", "Late", "Absent (implied)", "Sessions", "Attendance rate %"
        )
        val headerRow = sheet.createRow(2)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply {
                setCellValue(h)
                cellStyle = headerStyle
            }
        }

        val datesWithRecords = records.map { it.date }.toSet()
        val sessions = datesWithRecords.size.coerceAtLeast(1)
        val byStudent = records.groupBy { it.studentId }

        var rowIdx = 3
        for (s in students.sortedBy { it.name.lowercase(Locale.US) }) {
            val recs = byStudent[s.id].orEmpty()
            val present = recs.count { it.status == "Present" || it.status == "ManualOverride" }
            val late = recs.count { it.status == "Late" }
            val explicitAbsent = recs.count { it.status == "Absent" }
            val impliedAbsent = (sessions - recs.size).coerceAtLeast(0) + explicitAbsent
            val attended = present + late
            val rate = if (sessions == 0) 0.0 else 100.0 * attended / sessions

            val row = sheet.createRow(rowIdx++)
            row.createCell(0).setCellValue(s.studentId)
            row.createCell(1).setCellValue(s.name)
            row.createCell(2).setCellValue(s.className)
            row.createCell(3).setCellValue(present.toDouble())
            row.createCell(4).setCellValue(late.toDouble())
            row.createCell(5).setCellValue(impliedAbsent.toDouble())
            row.createCell(6).setCellValue(sessions.toDouble())
            row.createCell(7).setCellValue(String.format(Locale.US, "%.1f", rate))
        }
        headers.indices.forEach { sheet.setColumnWidth(it, 20 * 256) }
        sheet.setColumnWidth(1, 28 * 256)
    }
}