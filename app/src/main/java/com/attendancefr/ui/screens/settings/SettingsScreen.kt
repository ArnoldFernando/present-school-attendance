package com.attendancefr.ui.screens.settings

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendancefr.data.backup.BackupManager
import com.attendancefr.data.export.StudentFaceExporter
import com.attendancefr.data.imports.StudentFaceImporter
import com.attendancefr.data.prefs.SettingsRepository
import com.attendancefr.data.repository.ClassRepository
import com.attendancefr.domain.model.ClassSection
import com.attendancefr.ml.FaceEmbeddingEngine
import com.attendancefr.util.ShareUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class SettingsUiState(
    val threshold: Float = SettingsRepository.DEFAULT_THRESHOLD,
    val classes: List<ClassSection> = emptyList(),
    val modelAvailable: Boolean = false,
    val embeddingDim: Int? = null,
    val message: String? = null,
    val lastStudentExport: File? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val classes: ClassRepository,
    private val backup: BackupManager,
    private val engine: FaceEmbeddingEngine,
    private val studentFaceExporter: StudentFaceExporter,
    private val studentFaceImporter: StudentFaceImporter,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val _lastStudentExport = MutableStateFlow<File?>(null)

    val state = combine(
        settings.confidenceThreshold,
        classes.observeAll(),
        message,
        _lastStudentExport,
    ) { threshold, cls, msg, exportFile ->
        SettingsUiState(
            threshold = threshold,
            classes = cls,
            modelAvailable = engine.isModelAvailable,
            embeddingDim = if (engine.isModelAvailable) {
                runCatching {
                    engine.ensureLoaded()
                    engine.embeddingDim
                }.getOrNull()
            } else null,
            message = msg,
            lastStudentExport = exportFile,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThreshold(v: Float) {
        viewModelScope.launch { settings.setConfidenceThreshold(v) }
    }

    fun addClass(name: String) {
        viewModelScope.launch {
            runCatching { classes.add(name) }
                .onFailure { message.value = it.message }
        }
    }

    fun renameClass(id: Long, name: String) {
        viewModelScope.launch { classes.rename(id, name) }
    }

    fun deleteClass(id: Long) {
        viewModelScope.launch { classes.delete(id) }
    }

    fun backupNow(): File? {
        return try {
            val file = backup.createBackup()
            message.value = "Backup saved: ${file.name}"
            file
        } catch (t: Throwable) {
            message.value = t.message
            null
        }
    }

    fun restoreFrom(file: File, onNeedRestart: () -> Unit) {
        viewModelScope.launch {
            try {
                backup.restore(file)
            } catch (_: BackupManager.NeedsRestart) {
                onNeedRestart()
            } catch (t: Throwable) {
                message.value = t.message
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    fun exportStudentFaces() {
        viewModelScope.launch {
            try {
                val result = studentFaceExporter.export()
                _lastStudentExport.value = result.file
                message.value = "Exported ${result.studentCount} students with ${result.embeddingCount} face embeddings."
            } catch (t: Throwable) {
                message.value = "Export failed: ${t.message}"
            }
        }
    }

    fun importStudentFaces(uri: Uri) {
        viewModelScope.launch {
            try {
                val result = studentFaceImporter.import(uri)
                message.value = "Imported ${result.studentsImported} students, ${result.embeddingsImported} embeddings. ${result.studentsSkipped} duplicates skipped."
            } catch (t: Throwable) {
                message.value = "Import failed: ${t.message}"
            }
        }
    }

    fun shareLastStudentExport(context: android.content.Context) {
        val file = _lastStudentExport.value ?: return
        ShareUtils.shareFile(context, file, "application/json", "Share AttendanceFR student faces")
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var newClass by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf<File?>(null) }
    var pendingBackup by remember { mutableStateOf<File?>(null) }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ext = uri.lastPathSegment?.substringAfterLast(".", "afrbak") ?: "afrbak"; val tmp = File(context.cacheDir, "restore-import.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { input.copyTo(it) }
        }
        confirmRestore = tmp
    }

    val faceExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { vm.importStudentFaces(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("Match confidence threshold", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Cosine similarity required to auto-mark a student present. Default 0.60. Raise it if you see false positives; lower it if genuine students are flagged unknown. See ACCURACY_NOTES.md.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("${"%.2f".format(state.threshold)}", style = MaterialTheme.typography.headlineSmall)
                Slider(
                    value = state.threshold,
                    onValueChange = vm::setThreshold,
                    valueRange = 0.30f..0.95f,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("On-device model", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.modelAvailable)
                        "mobile_face_net.tflite loaded" + (state.embeddingDim?.let { " . ${it}-d embeddings" } ?: "")
                    else
                        "Model file missing. Copy mobile_face_net.tflite into app/src/main/assets/ and rebuild. Details in SETUP.md.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.modelAvailable) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("Classes / sections", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                state.classes.forEach { c ->
                    var editing by remember(c.id) { mutableStateOf(c.name) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = editing,
                            onValueChange = { editing = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        TextButton(onClick = { vm.renameClass(c.id, editing) }) { Text("Save") }
                        TextButton(onClick = { vm.deleteClass(c.id) }) { Text("Delete") }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newClass,
                        onValueChange = { newClass = it },
                        label = { Text("New class") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Button(onClick = {
                        if (newClass.isNotBlank()) {
                            vm.addClass(newClass)
                            newClass = ""
                        }
                    }) { Text("Add") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Full database backup copies everything including attendance history. Student faces export only enrollments and embeddings (no attendance records).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        val file = vm.backupNow()
                        pendingBackup = file
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create .afrbak backup") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        pendingBackup?.let {
                            ShareUtils.shareFile(context, it, "application/zip", "Share AttendanceFR backup")
                        }
                    },
                    enabled = pendingBackup != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share last .afrbak backup") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { restorePicker.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restore from .afrbak file") }

                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(16.dp))

                Text("Student faces only", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = vm::exportStudentFaces,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export students + faces") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.shareLastStudentExport(context) },
                    enabled = state.lastStudentExport != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share last student export") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { faceExportLauncher.launch("application/json") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import students + faces") }
            }
        }

        state.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "AttendanceFR v0.1.0  .  fully offline  .  data never leaves this device unless you export it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    confirmRestore?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Replace all local data?") },
            text = { Text("Restoring a backup overwrites students, embeddings, and attendance on this device. The app will close and you should reopen it.") },
            confirmButton = {
                TextButton(onClick = {
                    val activity = context as? Activity
                    vm.restoreFrom(file) {
                        activity?.let {
                            val intent = it.packageManager.getLaunchIntentForPackage(it.packageName)
                            it.finishAffinity()
                            if (intent != null) it.startActivity(intent)
                        }
                    }
                    confirmRestore = null
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
}

