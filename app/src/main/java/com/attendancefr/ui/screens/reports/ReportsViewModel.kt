package com.attendancefr.ui.screens.reports

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val lastExport: File? = null,
    val error: String? = null,
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val students: StudentRepository,
    private val attendance: AttendanceRepository,
    classesRepo: ClassRepository,
    private val exporter: ExcelExporter,
) : ViewModel() {

    private val classFilter = MutableStateFlow<String?>(null)
    private val from = MutableStateFlow(DateUtils.minusDays(DateUtils.today(), 30))
    private val to = MutableStateFlow(DateUtils.today())
    private val exporting = MutableStateFlow(false)
    private val lastExport = MutableStateFlow<File?>(null)
    private val error = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recordsFlow = combine(from, to) { f, t -> f to t }
        .flatMapLatest { (f, t) -> attendance.observeInRange(f, t) }

    private data class Filters(
        val classFilter: String?,
        val from: String,
        val to: String,
        val exporting: Boolean,
        val lastExport: File?,
        val error: String?,
    )

    private val filters = combine(classFilter, from, to, exporting, lastExport) { cf, f, t, ex, file ->
        Filters(cf, f, t, ex, file, null)
    }.combine(error) { filt, err -> filt.copy(error = err) }

    val state = combine(
        students.observeStudents(),
        recordsFlow,
        classesRepo.observeAll(),
        filters,
    ) { studentList: List<Student>,
        records: List<AttendanceRecordEntity>,
        classes: List<ClassSection>,
        filt: Filters ->
        val filteredStudents =
            studentList.filter { filt.classFilter == null || it.className == filt.classFilter }
        val recs = records.filter { rec -> filteredStudents.any { it.id == rec.studentId } }
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
            classes = classes.map { it.name },
            classFilter = filt.classFilter,
            from = filt.from,
            to = filt.to,
            stats = stats,
            records = recs,
            exporting = filt.exporting,
            lastExport = filt.lastExport,
            error = filt.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    fun onFilter(v: String?) {
        classFilter.value = v
    }

    fun onFrom(v: String) {
        from.value = v
    }

    fun onTo(v: String) {
        to.value = v
    }

       fun export() {
        viewModelScope.launch {
            exporting.value = true
            error.value = null
            lastExport.value = null
            runCatching {
                exporter.export(
                    ExcelExporter.ExportRequest(
                        fromDate = from.value,
                        toDate = to.value,
                        className = classFilter.value,
                    )
                )
            }.onSuccess {
                lastExport.value = it
            }.onFailure {
                error.value = it.message ?: it::class.java.simpleName ?: "Export failed"
            }
            exporting.value = false
        }
    }
}