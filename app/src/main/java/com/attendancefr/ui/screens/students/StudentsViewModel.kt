package com.attendancefr.ui.screens.students

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.attendancefr.data.repository.ClassRepository
import com.attendancefr.data.repository.StudentRepository
import com.attendancefr.domain.model.ClassSection
import com.attendancefr.domain.model.Student
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StudentsUiState(
    val students: List<Student> = emptyList(),
    val classes: List<ClassSection> = emptyList(),
    val query: String = "",
    val classFilter: String? = null,
)

@HiltViewModel
class StudentsViewModel @Inject constructor(
    private val students: StudentRepository,
    private val classes: ClassRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val classFilter = MutableStateFlow<String?>(null)

    val state: StateFlow<StudentsUiState> = combine(
        students.observeStudents(),
        classes.observeAll(),
        query,
        classFilter,
    ) { list, cls, q, filter ->
                   val filtered = list.filter { s ->
                (filter == null || s.classNames.contains(filter)) &&
                    (q.isBlank() || s.name.contains(q, true) || s.studentId.contains(q, true))
            }
        StudentsUiState(students = filtered, classes = cls, query = q, classFilter = filter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudentsUiState())

    fun onQuery(v: String) { query.value = v }
    fun onFilter(v: String?) { classFilter.value = v }

    fun delete(id: Long) {
        viewModelScope.launch { students.delete(id) }
    }
}
