package com.attendancefr.ui.screens.reports

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.attendancefr.data.export.ClassRosterExporter
import com.attendancefr.data.export.ExcelExporter
import com.attendancefr.data.local.entity.AttendanceRecordEntity
import com.attendancefr.data.repository.AttendanceRepository
import com.attendancefr.data.repository.ClassRepository
import com.attendancefr.data.repository.StudentRepository
import com.attendancefr.domain.model.ClassSection
import com.attendancefr.domain.model.Student
import com.attendancefr.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class StudentStat(
    val student: Student,
    val present: Int,
    val late: Int,
    val absent: Int,
    val sessions: Int,
) {
    val rate: Float
        get() = if (sessions == 0) 0f else 100f * (present + late) / sessions
}

data class ReportsUiState(
    val classes: List<String> = emptyList(),
    val classFilter: String? = null,
    val from: String = DateUtils.minusDays(DateUtils.today(), 30),
    val to: String = DateUtils.today(),
    val stats: List<StudentStat> = emptyList(),
    val records: List<AttendanceRecordEntity> = emptyList(),
    val exporting: Boolean = false,
    val lastExportFile: File? = null,
    val lastExportUri: Uri? = null,
    val rosterExporting: Boolean = false,
    val lastRosterFile: File? = null,
    val error: String? = null,
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val students: StudentRepository,
    private val attendance: AttendanceRepository,
    classesRepo: ClassRepository,
    private val exporter: ExcelExporter,
    private val rosterExporter: ClassRosterExporter,
) : ViewModel() {

    private val classFilter = MutableStateFlow<String?>(null)
    private val from = MutableStateFlow(DateUtils.minusDays(DateUtils.today(), 30))
    private val to = MutableStateFlow(DateUtils.today())
    private val exporting = MutableStateFlow(false)
    private val lastExportFile = MutableStateFlow<File?>(null)
    private val lastExportUri = MutableStateFlow<Uri?>(null)
    private val rosterExporting = MutableStateFlow(false)
    private val lastRosterFile = MutableStateFlow<File?>(null)
    private val error = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recordsFlow = combine(from, to) { f, t -> f to t }
        .flatMapLatest { (f, t) -> attendance.observeInRange(f, t) }

    private val dataFlow = combine(
        students.observeStudents(),
        recordsFlow,
        classesRepo.observeAll(),
    ) { studentList, records, classes ->
        Triple(studentList, records, classes)
    }

    private val uiStateFlow = combine(
        classFilter,
        from,
        to,
        exporting,
        lastExportFile,
        lastExportUri,
        rosterExporting,
        lastRosterFile,
        error,
    ) { array ->
        val cf = array[0] as String?
        val f = array[1] as String
        val t = array[2] as String
        val ex = array[3] as Boolean
        val file = array[4] as File?
        val uri = array[5] as Uri?
        val rosterEx = array[6] as Boolean
        val rosterFile = array[7] as File?
        val err = array[8] as String?
        UIState(cf, f, t, ex, file, uri, rosterEx, rosterFile, err)
    }

    private data class UIState(
        val classFilter: String?,
        val from: String,
        val to: String,
        val exporting: Boolean,
        val lastExportFile: File?,
        val lastExportUri: Uri?,
        val rosterExporting: Boolean,
        val lastRosterFile: File?,
        val error: String?,
    )

    val state: StateFlow<ReportsUiState> = combine(dataFlow, uiStateFlow) { data, ui ->
        val (studentList, records, classes) = data

        val activeClasses = classes.map { it.name }
            .filter { name ->
                name.isNotBlank() && records.any { rec ->
                    rec.className == name ||
                    (rec.className.isBlank() && studentList.find { it.id == rec.studentId }?.let { s ->
                        s.classNames.contains(name) || s.className == name
                    } == true)
                }
            }
            .sortedBy { it.lowercase() }

        val filteredStudents = studentList.filter { ui.classFilter == null || it.classNames.contains(ui.classFilter) }
        val recs = records.filter { rec ->
            val student = filteredStudents.find { it.id == rec.studentId }
            student != null && (
                ui.classFilter == null ||
                rec.className == ui.classFilter ||
                (rec.className.isEmpty() && student.className == ui.classFilter)
            )
        }
        val dates = recs.map { it.date }.toSet()
        val sessions = dates.size
        val byStudent = recs.groupBy { it.studentId }
        val stats = filteredStudents.map { s ->
            val r = byStudent[s.id].orEmpty()
            val present = r.count { it.status == "Present" || it.status == "ManualOverride" }
            val late = r.count { it.status == "Late" }
            val absent = r.count { it.status == "Absent" } + (sessions - r.size).coerceAtLeast(0)
            StudentStat(s, present, late, absent, sessions)
        }.sortedBy { it.student.name.lowercase() }
        ReportsUiState(
            classes = activeClasses,
            classFilter = ui.classFilter,
            from = ui.from,
            to = ui.to,
            stats = stats,
            records = recs,
            exporting = ui.exporting,
            lastExportFile = ui.lastExportFile,
            lastExportUri = ui.lastExportUri,
            rosterExporting = ui.rosterExporting,
            lastRosterFile = ui.lastRosterFile,
            error = ui.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    fun onFilter(v: String?) { classFilter.value = v }
    fun onFrom(v: String) { from.value = v }
    fun onTo(v: String) { to.value = v }

    fun exportRoster() {
        viewModelScope.launch {
            rosterExporting.value = true
            error.value = null
            lastRosterFile.value = null
            runCatching {
                rosterExporter.export(classFilter.value)
            }.onSuccess { result ->
                lastRosterFile.value = result.file
                error.value = "Exported ${result.studentCount} students to roster."
            }.onFailure { t ->
                error.value = "Roster export failed: ${t.message}"
            }
            rosterExporting.value = false
        }
    }

    fun importRoster(context: Context, uri: Uri) {
        viewModelScope.launch {
            error.value = null
            runCatching {
                val rows = com.attendancefr.data.imports.ExcelStudentImporter.read(context, uri)
                var imported = 0
                var skipped = 0
                rows.forEach { row ->
                    val added = students.importIfNotExists(
                        studentId = row.rollNumber,
                        name = row.name,
                        classNames = if (row.className.isNotBlank()) listOf(row.className) else emptyList()
                    )
                    if (added) imported++ else skipped++
                }
                "Imported $imported students. $skipped duplicates skipped."
            }.onSuccess { msg ->
                error.value = msg
            }.onFailure { t ->
                error.value = "Import failed: ${t.message}"
            }
        }
    }

    fun export() {
        viewModelScope.launch {
            exporting.value = true
            error.value = null
            lastExportFile.value = null
            lastExportUri.value = null
            runCatching {
                exporter.export(
                    ExcelExporter.ExportRequest(
                        fromDate = from.value,
                        toDate = to.value,
                        className = classFilter.value,
                        allClassNames = state.value.classes,
                    )
                )
            }.onSuccess { result ->
                lastExportFile.value = result.file
                lastExportUri.value = result.publicUri
            }.onFailure { t ->
                val msg = t.message ?: t::class.java.simpleName ?: "Export failed"
                error.value = msg
            }
            exporting.value = false
        }
    }
}
