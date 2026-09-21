package com.attendancefr.ui.screens.reports

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendancefr.ui.components.EmptyState
import com.attendancefr.util.ShareUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.Description
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onNavigateToExports: () -> Unit,
    vm: ReportsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Reports", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Attendance rate per student over the selected range. Days with at least one captured record count as a session; unmarked students on those days are implied absent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = state.classFilter == null,
                    onClick = { vm.onFilter(null) },
                    label = { Text("All classes") }
                )
                state.classes.forEach { name ->
                    FilterChip(
                        selected = state.classFilter == name,
                        onClick = { vm.onFilter(name) },
                        label = { Text(name) }
                    )
                }
            }
        Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = state.from,
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,  // prevents the field from consuming clicks
                    label = { Text("From") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { showFromPicker = true }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = state.to,
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("To") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { showToPicker = true }
                )
            }
        }

        if (showFromPicker) {
            val pickerState = rememberDatePickerState(
                initialDisplayMode = DisplayMode.Picker,
                initialSelectedDateMillis = try {
                    java.time.LocalDate.parse(state.from, isoFormatter)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                } catch (_: Exception) {
                    System.currentTimeMillis()
                }
            )
            DatePickerDialog(
                onDismissRequest = { showFromPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                                .format(isoFormatter)
                            vm.onFrom(date)
                        }
                        showFromPicker = false
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showFromPicker = false }) { Text("Cancel") }
                }
            ) {
                DatePicker(state = pickerState)
            }
        }

        if (showToPicker) {
            val pickerState = rememberDatePickerState(
                initialDisplayMode = DisplayMode.Picker,
                initialSelectedDateMillis = try {
                    java.time.LocalDate.parse(state.to, isoFormatter)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                } catch (_: Exception) {
                    System.currentTimeMillis()
                }
            )
            DatePickerDialog(
                onDismissRequest = { showToPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                                .format(isoFormatter)
                            vm.onTo(date)
                        }
                        showToPicker = false
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showToPicker = false }) { Text("Cancel") }
                }
            ) {
                DatePicker(state = pickerState)
            }
        }

        Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::export, enabled = !state.exporting, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null)
                Text(if (state.exporting) "  Exporting…" else "Export Excel")
            }
            OutlinedButton(
                onClick = onNavigateToExports,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null)
                Text("  View Exports")
            }
        }
        val file = state.lastExportFile
        if (file != null) {
            val location = if (state.lastExportUri != null) {
                "Downloads/Present/${file.name}"
            } else {
                file.absolutePath
            }
            Text(
                "Saved: $location",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        if (state.stats.isEmpty()) {
            EmptyState(
                title = "No attendance yet",
                body = "Take attendance or mark students manually, then return here to review rates and export a workbook.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.stats, key = { it.student.id }) { stat ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            Modifier
                                .padding(12.dp)
                                .fillMaxWidth()
                        ) {
                            Text(stat.student.name, style = MaterialTheme.typography.titleMedium)
                            val classesText =
                                stat.student.classNames.takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ") ?: stat.student.className
                            Text(
                                "${stat.student.studentId}  ·  $classesText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (stat.rate / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Present ${stat.present}  ·  Late ${stat.late}  ·  Absent ${stat.absent}  ·  ${"%.0f".format(stat.rate)}%",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}