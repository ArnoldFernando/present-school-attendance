package com.attendancefr.ui.screens.students

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendancefr.domain.model.Student
import com.attendancefr.ui.components.EmptyState
import com.attendancefr.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentsScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onReEnroll: (Long) -> Unit,
    vm: StudentsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Student?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, contentDescription = "Enroll student")
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text("Students", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search name or ID") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = state.classFilter == null,
                        onClick = { vm.onFilter(null) },
                        label = { Text("All") },
                    )
                }
                items(state.classes) { c ->
                    FilterChip(
                        selected = state.classFilter == c.name,
                        onClick = { vm.onFilter(c.name) },
                        label = { Text(c.name) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // Legend
            LegendRow(
                completeCount = state.students.count { it.isComplete() },
                incompleteCount = state.students.count { !it.isComplete() },
            )
            Spacer(Modifier.height(8.dp))

            if (state.students.isEmpty()) {
                EmptyState(
                    title = "No students yet",
                    body = "Enroll a student with 3–5 face photos to start taking attendance offline.",
                    actionLabel = "Enroll first student",
                    onAction = onAdd,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.students, key = { it.id }) { s ->
                        StudentCard(
                            student = s,
                            onEdit = { onEdit(s.id) },
                            onReEnroll = { onReEnroll(s.id) },
                            onDelete = { pendingDelete = s },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${s.name}?") },
            text = { Text("This removes the student, their face embeddings, and attendance history from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(s.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LegendRow(completeCount: Int, incompleteCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LegendItem(
            color = Color(0xFF81C784), // soft green
            label = "Complete ($completeCount)",
        )
        LegendItem(
            color = Color(0xFFFFF176), // soft yellow
            label = "Incomplete ($incompleteCount)",
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StudentCard(
    student: Student,
    onEdit: () -> Unit,
    onReEnroll: () -> Unit,
    onDelete: () -> Unit,
) {
    val isComplete = student.isComplete()
    val cardColor = if (isComplete) {
        Color(0xFF81C784).copy(alpha = 0.25f) // soft green tint
    } else {
        Color(0xFFFFF176).copy(alpha = 0.35f) // soft yellow tint
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = cardColor,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(student.name, style = MaterialTheme.typography.titleMedium)
                val classesText = student.classNames.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: student.className
                Text(
                    "${student.studentId}  ·  $classesText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${student.embeddingCount} face shot${if (student.embeddingCount == 1) "" else "s"}  ·  enrolled ${DateUtils.formatDateTime(student.dateEnrolled)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onReEnroll) {
                Icon(Icons.Outlined.Face, contentDescription = "Re-enroll face")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete")
            }
        }
    }
}

private fun Student.isComplete(): Boolean {
    return name.isNotBlank()
            && studentId.isNotBlank()
            && (classNames.isNotEmpty() || className.isNotBlank())
            && embeddingCount > 0
}
