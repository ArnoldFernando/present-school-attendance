package com.attendancefr.data.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.apache.poi.hssf.usermodel.HSSFWorkbook

data class ImportedStudentRow(
    val name: String,
    val rollNumber: String,
    val className: String,
)

object ExcelStudentImporter {
    fun read(context: Context, uri: Uri): List<ImportedStudentRow> {
        val fileName = getFileName(context, uri).lowercase()
        return when {
            fileName.endsWith(".csv") || fileName.endsWith(".txt") -> readCsv(context, uri)
            fileName.endsWith(".xlsx") -> throw IllegalArgumentException(".xlsx is not supported on Android. Please save as .xls (Excel 97-2003) or .csv and try again.")
            else -> readXls(context, uri)
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx) ?: ""
                }
            }
        }
        if (name.isEmpty()) {
            name = uri.lastPathSegment ?: ""
        }
        return name
    }

    private fun readXls(context: Context, uri: Uri): List<ImportedStudentRow> {
        val results = mutableListOf<ImportedStudentRow>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val workbook = HSSFWorkbook(stream)
            val sheet = workbook.getSheetAt(0)
            val headerRow = sheet.getRow(0)
                ?: throw IllegalArgumentException("Excel file is empty")

            var nameCol = -1
            var idCol = -1
            var classCol = -1

            headerRow.cellIterator().forEach { cell ->
                val header = cell.stringCellValue.trim().lowercase()
                when {
                    header in setOf("full name", "name", "full_name", "student name") ->
                        nameCol = cell.columnIndex
                    header in setOf("student id", "id", "student_id", "roll number", "roll", "roll_no", "code") ->
                        idCol = cell.columnIndex
                    header in setOf("class", "class name", "class_name", "section", "class section") ->
                        classCol = cell.columnIndex
                }
            }

            require(nameCol != -1) { "Column 'Full name' not found" }
            require(idCol != -1) { "Column 'Student ID' not found" }

            for (i in 1..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                val name = row.getCell(nameCol)?.toString()?.trim() ?: continue
                val roll = row.getCell(idCol)?.toString()?.trim() ?: continue
                val cls = if (classCol >= 0) row.getCell(classCol)?.toString()?.trim().orEmpty() else ""
                if (name.isNotEmpty() && roll.isNotEmpty()) {
                    results.add(ImportedStudentRow(name, roll, cls))
                }
            }
            workbook.close()
        }
        return results.distinctBy { it.rollNumber }
    }

    private fun readCsv(context: Context, uri: Uri): List<ImportedStudentRow> {
        val results = mutableListOf<ImportedStudentRow>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val lines = stream.bufferedReader().readLines()
            if (lines.isEmpty()) throw IllegalArgumentException("CSV file is empty")

            val firstLine = lines[0].removePrefix("\uFEFF")
            val separator = if (firstLine.contains(",")) "," else if (firstLine.contains(";")) ";" else ","
            val headers = parseCsvLine(firstLine, separator).map { it.trim().lowercase() }

            val nameCol = headers.indexOfFirst { it in setOf("full name", "name", "full_name", "student name") }
            val idCol = headers.indexOfFirst { it in setOf("student id", "id", "student_id", "roll number", "roll", "roll_no", "code") }
            val classCol = headers.indexOfFirst { it in setOf("class", "class name", "class_name", "section", "class section") }

            if (nameCol == -1 || idCol == -1) {
                val found = headers.joinToString(", ")
                throw IllegalArgumentException("Could not find required columns. Found headers: [$found]. Expected: 'Full name', 'Student ID', and optionally 'Class'")
            }

            for (i in 1 until lines.size) {
                val cols = parseCsvLine(lines[i], separator)
                if (cols.size > maxOf(nameCol, idCol)) {
                    val name = cols[nameCol]
                    val roll = cols[idCol]
                    val cls = if (classCol >= 0 && classCol < cols.size) cols[classCol] else ""
                    if (name.isNotEmpty() && roll.isNotEmpty()) {
                        results.add(ImportedStudentRow(name, roll, cls))
                    }
                }
            }
        }
        return results.distinctBy { it.rollNumber }
    }

    private fun parseCsvLine(line: String, separator: String = ","): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c.toString() == separator && !inQuotes -> {
                    result.add(sb.toString().trim())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString().trim())
        return result
    }
}
