package com.attendancefr.ui.screens.students

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.attendancefr.data.repository.StudentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StudentEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val students: StudentRepository,
) : ViewModel() {
    val id: Long = savedStateHandle.get<Long>("id") ?: 0L

    var name by mutableStateOf("")
        private set
    var roll by mutableStateOf("")
        private set
    var className by mutableStateOf("")
        private set
    var saved by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            val s = students.getById(id) ?: return@launch
            name = s.name
            roll = s.studentId
            className = s.className
        }
    }

    fun onName(v: String) { name = v }
    fun onRoll(v: String) { roll = v }
    fun onClass(v: String) { className = v }

    fun save() {
        if (name.isBlank() || roll.isBlank() || className.isBlank()) {
            error = "All fields are required."
            return
        }
        viewModelScope.launch {
            runCatching { students.updateDetails(id, roll, name, className) }
                .onSuccess { saved = true }
                .onFailure { error = it.message }
        }
    }
}

@Composable
fun StudentEditScreen(
    onDone: () -> Unit,
    onReEnroll: (Long) -> Unit,
    vm: StudentEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(vm.saved) { if (vm.saved) onDone() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Edit student", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(vm.name, vm::onName, label = { Text("Full name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(vm.roll, vm::onRoll, label = { Text("Student ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(vm.className, vm::onClass, label = { Text("Class / section") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        vm.error?.let {
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
