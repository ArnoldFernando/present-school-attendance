package com.attendancefr.ui.screens.students

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.attendancefr.data.repository.ClassRepository
import com.attendancefr.data.repository.StudentRepository
import com.attendancefr.domain.model.ClassSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StudentEditUiState(
    val name: String = "",
    val roll: String = "",
    val classNames: List<String> = emptyList(),
    val availableClasses: List<ClassSection> = emptyList(),
    val saved: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class StudentEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val students: StudentRepository,
    private val classes: ClassRepository,
) : ViewModel() {
    val id: Long = savedStateHandle.get<Long>("id") ?: 0L

    private val _state = MutableStateFlow(StudentEditUiState())
    val state: StateFlow<StudentEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = students.getById(id) ?: return@launch
            _state.update {
                it.copy(
                    name = s.name,
                    roll = s.studentId,
                    classNames = s.classNames.ifEmpty { listOf(s.className) }
                )
            }
        }
        viewModelScope.launch {
            classes.observeAll().collect { list ->
                _state.update { it.copy(availableClasses = list) }
            }
        }
    }

    fun onName(v: String) = _state.update { it.copy(name = v, error = null) }
    fun onRoll(v: String) = _state.update { it.copy(roll = v, error = null) }

    fun onClassSelect(v: String) = _state.update { s ->
        if (v in s.classNames) s else s.copy(classNames = s.classNames + v, error = null)
    }

    fun onClassRemove(v: String) = _state.update { s ->
        s.copy(classNames = s.classNames - v, error = null)
    }

    fun addClass(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            runCatching { classes.add(trimmed) }
                .onSuccess {
                    _state.update { s ->
                        if (trimmed in s.classNames) s else s.copy(classNames = s.classNames + trimmed)
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun save() {
        val s = _state.value
        if (s.name.isBlank() || s.roll.isBlank() || s.classNames.isEmpty()) {
            _state.update { it.copy(error = "Name, student ID and at least one class are required.") }
            return
        }
        viewModelScope.launch {
            runCatching { students.updateDetails(id, s.roll, s.name, s.classNames) }
                .onSuccess { _state.update { it.copy(saved = true) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudentEditScreen(
    onDone: () -> Unit,
    onReEnroll: (Long) -> Unit,
    vm: StudentEditViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Edit student", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.name,
            onValueChange = vm::onName,
            label = { Text("Full name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.roll,
            onValueChange = vm::onRoll,
            label = { Text("Student ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Text("Classes / sections", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        if (state.classNames.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                state.classNames.forEach { name ->
                    InputChip(
                        selected = true,
                        onClick = { vm.onClassRemove(name) },
                        label = { Text(name) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Remove",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        var expanded by remember { mutableStateOf(false) }
        var newName by remember { mutableStateOf("") }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = if (state.classNames.isEmpty()) "Select class(es)" else "${state.classNames.size} selected",
                onValueChange = {},
                readOnly = true,
                label = { Text("Add existing class") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                singleLine = true,
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.availableClasses.map { it.name }.filter { it !in state.classNames }.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            vm.onClassSelect(name)
                            expanded = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New class") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            TextButton(
                onClick = {
                    if (newName.isNotBlank()) {
                        vm.addClass(newName)
                        newName = ""
                    }
                }
            ) { Text("Add") }
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = vm::save, modifier = Modifier.fillMaxWidth()) { Text("Save changes") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onReEnroll(vm.id) }, modifier = Modifier.fillMaxWidth()) {
            Text("Re-enroll face")
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}