package com.attendancefr.ui.screens.manual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendancefr.data.repository.AttendanceRepository
import com.attendancefr.data.repository.ClassRepository
import com.attendancefr.data.repository.StudentRepository
import com.attendancefr.domain.model.AttendanceStatus
import com.attendancefr.domain.model.Student
import com.attendancefr.ui.components.EmptyState
import com.attendancefr.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ManualRow(
    val student: Student,
    val status: AttendanceStatus?,
    val isManual: Boolean,
    val confidence: Float?,
)

data class ManualUiState(
    val rows: List<ManualRow> = emptyList(),
    val classes: List<String> = emptyList(),
    val classFilter: String = "",
    val query: String = "",
    val date: String = DateUtils.today(),
)

@HiltViewModel
class ManualOverrideViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val students: StudentRepository,
    private val attendance: AttendanceRepository,
    classesRepo: ClassRepository,
) : ViewModel() {

    private val initialClass = savedStateHandle.get<String>("className").orEmpty()
    private val query = MutableStateFlow("")
    private val classFilter = MutableStateFlow(initialClass)
    private val refresh = MutableStateFlow(0)

    val state = combine(
        students.observeStudents(),
        attendance.observeByDate(DateUtils.today()),
        classesRepo.observeAll(),
        query,
        classFilter,
    ) { studentList, records, classes, q, filter ->
        val recMap = records.associateBy { it.studentId }
        val rows = studentList
            .filter { filter.isBlank() || it.classNames.contains(filter) }
            .filter { q.isBlank() || it.name.contains(q, true) || it.studentId.contains(q, true) }
            .map { s ->
                val rec = recMap[s.id]
                ManualRow(
                    student = s,
                    status = rec?.status,
                    isManual = rec?.isManual == true,
                    confidence = rec?.matchConfidence,
                )
            }
        ManualUiState(
            rows = rows,
            classes = classes.map { it.name },
            classFilter = filter,
            query = q,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManualUiState(classFilter = initialClass))

    fun onQuery(v: String) { query.value = v }
    fun onFilter(v: String) { classFilter.value = v }

    fun mark(studentId: Long, status: AttendanceStatus) {
        viewModelScope.launch {
            attendance.mark(
                studentId = studentId,
                className = classFilter.value,
                status = status,
                confidence = null,
                isManual = true,
            )
            refresh.value = refresh.value + 1
        }
    }
}

@Composable
fun ManualOverrideScreen(
    onDone: () -> Unit,
    vm: ManualOverrideViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Manual override", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done") }
        }
        Text(
            "Always available as a fallback. Marking here overwrites today's automatic match.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search") },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Search, contentDescription = null) },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.classFilter.isBlank(),
                onClick = { vm.onFilter("") },
                label = { Text("All") },
            )
            state.classes.forEach { name ->
                FilterChip(
                    selected = state.classFilter == name,
                    onClick = { vm.onFilter(name) },
                    label = { Text(name) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (state.rows.isEmpty()) {
            EmptyState(
                title = "No students",
                body = "Enroll students first, then you can mark them present, absent, or late by hand.",
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.rows, key = { it.student.id }) { row ->
                    ManualRowCard(row = row, onMark = { status -> vm.mark(row.student.id, status) })
                }
            }
        }
    }
}

@Composable
private fun ManualRowCard(row: ManualRow, onMark: (AttendanceStatus) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(row.student.name, style = MaterialTheme.typography.titleMedium)
        Text(
            buildString {
                append(row.student.studentId)
                append("  ·  ")
                append(row.student.className)
                row.status?.let {
                    append("  ·  ")
                    append(it.name)
                    if (row.isManual) append(" (manual)")
                    row.confidence?.let { c -> append("  ${"%.2f".format(c)}") }
                } ?: append("  ·  not marked")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = row.status == AttendanceStatus.Present,
                onClick = { onMark(AttendanceStatus.Present) },
                label = { Text("Present") },
            )
            FilterChip(
                selected = row.status == AttendanceStatus.Late,
                onClick = { onMark(AttendanceStatus.Late) },
                label = { Text("Late") },
            )
            FilterChip(
                selected = row.status == AttendanceStatus.Absent,
                onClick = { onMark(AttendanceStatus.Absent) },
                label = { Text("Absent") },
            )
        }
    }
}
