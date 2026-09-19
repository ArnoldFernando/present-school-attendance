package com.attendancefr.ui.screens.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendancefr.ui.components.EmptyState
import com.attendancefr.util.ShareUtils

@Composable
fun ReportsScreen(vm: ReportsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.classFilter == null, onClick = { vm.onFilter(null) }, label = { Text("All classes") })
            state.classes.forEach { name ->
                FilterChip(selected = state.classFilter == name, onClick = { vm.onFilter(name) }, label = { Text(name) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.from,
                onValueChange = vm::onFrom,
                label = { Text("From (YYYY-MM-DD)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.to,
                onValueChange = vm::onTo,
                label = { Text("To (YYYY-MM-DD)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::export, enabled = !state.exporting, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null)
                Text(if (state.exporting) "  Exporting…" else "Export Excel")
            }
            OutlinedButton(
                onClick = {
                    val uri = state.lastExportUri
                    val file = state.lastExportFile
                    when {
                        uri != null -> {
                            ShareUtils.shareUri(
                                context,
                                uri,
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "Share attendance workbook",
                            )
                        }
                        file != null -> {
                            ShareUtils.shareFile(
                                context,
                                file,
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "Share attendance workbook",
                            )
                        }
                    }
                },
                enabled = state.lastExportFile != null || state.lastExportUri != null,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Text("  Share")
            }
        }
        val file = state.lastExportFile
        if (file != null) {
            val location = if (state.lastExportUri != null) {
                "Downloads/Present/${file.name}"
            } else {
                file.absolutePath
            }
            Text("Saved: $location", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Column(Modifier.padding(12.dp).fillMaxWidth()) {
                            Text(stat.student.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${stat.student.studentId}  ·  ${stat.student.className}",
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