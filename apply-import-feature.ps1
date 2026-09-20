Set-Location "C:\Users\Arn\Downloads\AttendanceFR\AttendanceFR"

Write-Host "==> Creating importer directory..."
New-Item -ItemType Directory -Force -Path "app/src/main/java/com/attendancefr/data/imports" | Out-Null

# ------------------------------------------------------------------
# 1. Create ExcelStudentImporter.kt
# ------------------------------------------------------------------
Write-Host "==> Writing ExcelStudentImporter.kt..."
$excelText = @'
package com.attendancefr.data.imports

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.WorkbookFactory

data class ImportedStudentRow(
    val name: String,
    val rollNumber: String,
)

object ExcelStudentImporter {
    fun read(context: Context, uri: Uri): List<ImportedStudentRow> {
        val results = mutableListOf<ImportedStudentRow>()

        context.contentResolver.openInputStream(uri)?.use { stream ->
            val workbook = WorkbookFactory.create(stream)
            val sheet = workbook.getSheetAt(0)
            val headerRow = sheet.getRow(0)
                ?: throw IllegalArgumentException("Excel file is empty")

            var nameCol = -1
            var idCol = -1

            headerRow.cellIterator().forEach { cell ->
                val header = cell.stringCellValue.trim().lowercase()
                when {
                    header in setOf("full name", "name", "full_name", "student name") ->
                        nameCol = cell.columnIndex
                    header in setOf("student id", "id", "student_id", "roll number", "roll", "roll_no") ->
                        idCol = cell.columnIndex
                }
            }

            require(nameCol != -1) { "Column 'Full name' not found in Excel" }
            require(idCol != -1) { "Column 'Student ID' not found in Excel" }

            for (i in 1..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                val name = row.getCell(nameCol)?.toString()?.trim() ?: continue
                val roll = row.getCell(idCol)?.toString()?.trim() ?: continue

                if (name.isNotEmpty() && roll.isNotEmpty()) {
                    results.add(ImportedStudentRow(name, roll))
                }
            }
            workbook.close()
        }

        return results.distinctBy { it.rollNumber }
    }
}
'@
Set-Content -Path "app/src/main/java/com/attendancefr/data/imports/ExcelStudentImporter.kt" -Value $excelText

# ------------------------------------------------------------------
# 2. Patch StudentDao.kt
# ------------------------------------------------------------------
$daoPath = "app/src/main/java/com/attendancefr/data/local/dao/StudentDao.kt"
if (Test-Path $daoPath) {
    Write-Host "==> Patching StudentDao.kt..."
    $daoLines = Get-Content $daoPath
    $last = $daoLines.Count - 1
    $newDao = $daoLines[0..($last-1)] + @"
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(students: List<StudentEntity>): List<Long>
"@ + "" + $daoLines[$last]
    $newDao | Set-Content $daoPath
} else {
    Write-Warning "StudentDao.kt not found at $daoPath. Add insertAll manually."
}

# ------------------------------------------------------------------
# 3. Patch StudentRepository.kt
# ------------------------------------------------------------------
$repoPath = "app/src/main/java/com/attendancefr/data/repository/StudentRepository.kt"
if (Test-Path $repoPath) {
    Write-Host "==> Patching StudentRepository.kt..."
    $repoLines = Get-Content $repoPath
    $lastRepo = $repoLines.Count - 1
    $repoInsert = @"
    
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<com.attendancefr.domain.model.Student>> {
        return dao.observeAllWithClasses().map { list ->
            list.map { relation ->
                com.attendancefr.domain.model.Student(
                    id = relation.student.id,
                    studentId = relation.student.studentId,
                    name = relation.student.name,
                    className = relation.student.className,
                    classNames = relation.classes.map { it.name },
                    dateEnrolled = relation.student.dateEnrolled,
                    embeddingCount = relation.student.embeddingCount,
                )
            }
        }
    }

    suspend fun importIfNotExists(studentId: String, name: String, classNames: List<String>): Boolean {
        if (dao.getByRoll(studentId) != null) return false

        val entity = com.attendancefr.data.local.entity.StudentEntity(
            studentId = studentId,
            name = name,
            className = classNames.firstOrNull().orEmpty(),
            dateEnrolled = System.currentTimeMillis(),
            embeddingCount = 0,
        )
        val id = dao.insert(entity)
        classNames.forEach { className ->
            dao.insertStudentClass(com.attendancefr.data.local.entity.StudentClassCrossRef(studentId = id, className = className))
        }
        return true
    }
"@
    $newRepo = $repoLines[0..($lastRepo-1)] + $repoInsert + "" + $repoLines[$lastRepo]
    $newRepo | Set-Content $repoPath
} else {
    Write-Warning "StudentRepository.kt not found at $repoPath. Add observeAll and importIfNotExists manually."
}

# ------------------------------------------------------------------
# 4. Overwrite EnrollViewModel.kt
# ------------------------------------------------------------------
Write-Host "==> Overwriting EnrollViewModel.kt..."
$vmText = @'
package com.attendancefr.ui.screens.enroll

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.attendancefr.data.imports.ExcelStudentImporter
import com.attendancefr.data.prefs.SettingsRepository
import com.attendancefr.data.repository.ClassRepository
import com.attendancefr.data.repository.StudentRepository
import com.attendancefr.domain.model.ClassSection
import com.attendancefr.ml.FaceDetectorHelper
import com.attendancefr.ml.FaceEmbeddingEngine
import com.attendancefr.ml.ImageUtils
import com.attendancefr.ml.QualityAnalyzer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class PoseStep(val prompt: String, val expectedYaw: Float) {
    Straight("Look straight at the camera", 0f),
    Left("Turn slightly left", -22f),
    Right("Turn slightly right", 22f),
    TiltUp("Chin slightly up", 0f),
    Done("All photos captured", 0f),
}

data class CapturedShot(
    val pose: PoseStep,
    val embedding: FloatArray,
)

data class EnrollUiState(
    val reenrollStudentId: Long? = null,
    val isManualEnrollment: Boolean = false,
    val name: String = "",
    val roll: String = "",
    val classNames: List<String> = emptyList(),
    val classes: List<ClassSection> = emptyList(),
    val students: List<com.attendancefr.domain.model.Student> = emptyList(),
    val pose: PoseStep = PoseStep.Straight,
    val shots: List<CapturedShot> = emptyList(),
    val hint: String = PoseStep.Straight.prompt,
    val busy: Boolean = false,
    val isImporting: Boolean = false,
    val error: String? = null,
    val modelMissing: Boolean = false,
    val saved: Boolean = false,
    val minShots: Int = 3,
    val maxShots: Int = 5,
)

@HiltViewModel
class EnrollViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val students: StudentRepository,
    private val classes: ClassRepository,
    private val detector: FaceDetectorHelper,
    private val engine: FaceEmbeddingEngine,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val existingId: Long? = savedStateHandle.get<String>("studentId")?.toLongOrNull()

    private val _state = MutableStateFlow(EnrollUiState(reenrollStudentId = existingId))
    val state: StateFlow<EnrollUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(modelMissing = !engine.isModelAvailable) }
        viewModelScope.launch {
            classes.observeAll().collect { list ->
                _state.update { s ->
                    s.copy(
                        classes = list,
                        classNames = s.classNames.ifEmpty { listOfNotNull(list.firstOrNull()?.name) },
                    )
                }
            }
        }
        if (existingId != null) {
            viewModelScope.launch {
                val s = students.getById(existingId) ?: return@launch
                _state.update {
                    it.copy(
                        name = s.name,
                        roll = s.studentId,
                        classNames = s.classNames.ifEmpty { listOf(s.className) }
                    )
                }
            }
        }
        loadStudents()
    }

    fun loadStudents() {
        viewModelScope.launch {
            students.observeAll().collect { list ->
                _state.update { it.copy(students = list) }
            }
        }
    }

    fun importStudents(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, error = null) }
            try {
                val rows = withContext(Dispatchers.IO) {
                    ExcelStudentImporter.read(context, uri)
                }
                val targetClass = _state.value.classes.firstOrNull()?.name
                    ?: run {
                        _state.update {
                            it.copy(isImporting = false, error = "Create at least one class before importing.")
                        }
                        return@launch
                    }

                var count = 0
                rows.forEach { row ->
                    val added = students.importIfNotExists(
                        studentId = row.rollNumber,
                        name = row.name,
                        classNames = listOf(targetClass)
                    )
                    if (added) count++
                }
                _state.update {
                    it.copy(
                        isImporting = false,
                        error = "Imported $count students. ${rows.size - count} duplicates skipped."
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isImporting = false, error = e.message ?: "Import failed") }
            }
        }
    }

    fun startManualEnrollment() {
        _state.update {
            it.copy(
                isManualEnrollment = true,
                reenrollStudentId = null,
                name = "",
                roll = "",
                shots = emptyList(),
                pose = PoseStep.Straight,
                hint = PoseStep.Straight.prompt,
                error = null,
                saved = false,
            )
        }
    }

    fun selectStudentForEnrollment(student: com.attendancefr.domain.model.Student) {
        _state.update {
            it.copy(
                reenrollStudentId = student.id,
                name = student.name,
                roll = student.studentId,
                classNames = student.classNames.ifEmpty { listOf(student.className) },
                shots = emptyList(),
                pose = PoseStep.Straight,
                hint = PoseStep.Straight.prompt,
                error = null,
                saved = false,
            )
        }
    }

    fun clearEnrollment() {
        _state.update {
            EnrollUiState(
                classes = it.classes,
                classNames = it.classNames,
                modelMissing = it.modelMissing,
                minShots = it.minShots,
                maxShots = it.maxShots,
                students = it.students,
            )
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
        viewModelScope.launch {
            val trimmed = name.trim()
            runCatching { classes.add(trimmed) }
                .onSuccess {
                    _state.update { s ->
                        if (trimmed in s.classNames) s else s.copy(classNames = s.classNames + trimmed)
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun capture(bitmap: Bitmap) {
        val s = _state.value
        if (s.busy || s.shots.size >= s.maxShots) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, hint = "Checking photo…") }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val scaled = ImageUtils.downscaleIfNeeded(bitmap, 960)
                    val faces = detector.detectStill(scaled)
                    val quality = detector.assessQuality(
                        faces = faces,
                        imageWidth = scaled.width,
                        imageHeight = scaled.height,
                        expectedYaw = s.pose.expectedYaw,
                        yawTolerance = if (s.pose == PoseStep.Straight) 14f else 20f,
                    )
                    if (!quality.accepted) {
                        CaptureOutcome.Rejected(quality.reason ?: "Photo not accepted.")
                    } else {
                        val face = faces.first()
                        val crop = detector.paddedCropRect(face.boundingBox, scaled.width, scaled.height)
                        val lighting = QualityAnalyzer.lighting(scaled, crop)
                        if (!lighting.ok) {
                            CaptureOutcome.Rejected(lighting.hint ?: "Move to better lighting.")
                        } else if (QualityAnalyzer.sharpness(scaled, crop) < QualityAnalyzer.MIN_SHARPNESS) {
                            CaptureOutcome.Rejected("Photo looks blurry. Hold still and tap again.")
                        } else {
                            val embedding = engine.embed(scaled, crop)
                            CaptureOutcome.Accepted(embedding)
                        }
                    }
                }.getOrElse { t -> CaptureOutcome.Rejected(t.message ?: "Capture failed.") }
            }
            when (result) {
                is CaptureOutcome.Rejected -> _state.update {
                    it.copy(busy = false, error = result.reason, hint = result.reason)
                }
                is CaptureOutcome.Accepted -> {
                    val nextShots = s.shots + CapturedShot(s.pose, result.embedding)
                    val nextPose = nextPose(s.pose, nextShots.size, s.maxShots)
                    _state.update {
                        it.copy(
                            busy = false,
                            shots = nextShots,
                            pose = nextPose,
                            hint = if (nextPose == PoseStep.Done)
                                "All set — tap Save student."
                            else nextPose.prompt,
                            error = null,
                        )
                    }
                }
            }
        }
    }

    fun save() {
        val s = _state.value
        if (s.name.isBlank() || s.roll.isBlank() || s.classNames.isEmpty()) {
            _state.update { it.copy(error = "Name, student ID and at least one class are required.") }
            return
        }
        if (s.shots.size < s.minShots) {
            _state.update { it.copy(error = "Capture at least ${s.minShots} photos.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val embeddings = s.shots.map { it.embedding }
                val existing = s.reenrollStudentId
                if (existing != null) {
                    students.updateDetails(existing, s.roll, s.name, s.classNames)
                    students.replaceEmbeddings(existing, embeddings)
                } else {
                    students.enroll(s.roll, s.name, s.classNames, embeddings)
                }
                settings.setLastSelectedClass(s.classNames.firstOrNull().orEmpty())
            }.onSuccess {
                _state.update { it.copy(busy = false, saved = true) }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    private fun nextPose(current: PoseStep, count: Int, max: Int): PoseStep {
        if (count >= max) return PoseStep.Done
        return when (current) {
            PoseStep.Straight -> PoseStep.Left
            PoseStep.Left -> PoseStep.Right
            PoseStep.Right -> if (count >= 3) PoseStep.Done else PoseStep.TiltUp
            PoseStep.TiltUp -> PoseStep.Done
            PoseStep.Done -> PoseStep.Done
        }
    }

    private sealed class CaptureOutcome {
        data class Accepted(val embedding: FloatArray) : CaptureOutcome()
        data class Rejected(val reason: String) : CaptureOutcome()
    }
}
'@
Set-Content -Path "app/src/main/java/com/attendancefr/ui/screens/enroll/EnrollViewModel.kt" -Value $vmText

# ------------------------------------------------------------------
# 5. Overwrite EnrollScreen.kt
# ------------------------------------------------------------------
Write-Host "==> Overwriting EnrollScreen.kt..."
$screenText = @'
package com.attendancefr.ui.screens.enroll

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendancefr.domain.model.Student
import com.attendancefr.ui.camera.awaitCameraProvider
import com.attendancefr.ui.camera.takeBitmap
import com.attendancefr.ui.components.CameraPermissionGate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollScreen(
    onDone: () -> Unit,
    vm: EnrollViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    LaunchedEffect(state.error) {
        state.error?.let { snack.showSnackbar(it) }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { vm.importStudents(it) }
    }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }

    CameraPermissionGate {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                if (!state.isManualEnrollment && state.reenrollStudentId == null) {
                    Text("Students", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { pickFile.launch("*/*") },
                        enabled = !state.isImporting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isImporting) "Importing…" else "Import student list from Excel")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = vm::startManualEnrollment,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Enroll new student")
                    }
                    Spacer(Modifier.height(16.dp))
                    StudentStatusList(
                        students = state.students,
                        onEnrollFace = vm::selectStudentForEnrollment,
                    )
                } else {
                    Text(
                        if (state.reenrollStudentId == null) "Enroll student" else "Enroll face",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "Capture 3–5 photos at slightly different angles. The app rejects blurry, off-center, or eyes-closed shots.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    if (state.reenrollStudentId != null) {
                        TextButton(
                            onClick = vm::clearEnrollment,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Back to list") }
                    }

                    OutlinedTextField(
                        value = state.name,
                        onValueChange = vm::onName,
                        label = { Text("Full name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = state.reenrollStudentId == null,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.roll,
                        onValueChange = vm::onRoll,
                        label = { Text("Student ID / roll number") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = state.reenrollStudentId == null,
                    )
                    Spacer(Modifier.height(8.dp))
                    MultiClassPicker(
                        classes = state.classes.map { it.name },
                        selected = state.classNames,
                        onSelect = vm::onClassSelect,
                        onRemove = vm::onClassRemove,
                        onAdd = vm::addClass,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (state.modelMissing) {
                        Text(
                            "TFLite model is missing. Copy mobile_face_net.tflite into app/src/main/assets/ (see SETUP.md). You can still fill in details, but photo capture will fail until the model is present.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    CapturePanel(
                        pose = state.pose,
                        hint = state.hint,
                        shots = state.shots.size,
                        max = state.maxShots,
                        busy = state.busy,
                        lensFacing = lensFacing,
                        onToggleCamera = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                                CameraSelector.LENS_FACING_BACK
                            } else {
                                CameraSelector.LENS_FACING_FRONT
                            }
                        },
                        onCapture = vm::capture,
                    )
                    Spacer(Modifier.height(8.dp))
                    ShotDots(count = state.shots.size, max = state.maxShots)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isManualEnrollment || state.reenrollStudentId != null) {
                    TextButton(
                        onClick = vm::clearEnrollment,
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel") }
                    Button(
                        onClick = vm::save,
                        enabled = !state.busy && state.shots.size >= state.minShots && state.name.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            when {
                                state.reenrollStudentId != null -> "Update face"
                                else -> "Save student"
                            }
                        )
                    }
                } else {
                    TextButton(
                        onClick = onDone,
                        modifier = Modifier.weight(1f),
                    ) { Text("Close") }
                }
            }
            SnackbarHost(snack)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MultiClassPicker(
    classes: List<String>,
    selected: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Text("Classes / sections", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    if (selected.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            selected.forEach { name ->
                InputChip(
                    selected = true,
                    onClick = { onRemove(name) },
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
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (selected.isEmpty()) "Select class(es)" else "${selected.size} selected",
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
            classes.filter { it !in selected }.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(name)
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
                    onAdd(newName)
                    newName = ""
                }
            }
        ) { Text("Add") }
    }
}

@Composable
private fun CapturePanel(
    pose: PoseStep,
    hint: String,
    shots: Int,
    max: Int,
    busy: Boolean,
    lensFacing: Int,
    onToggleCamera: () -> Unit,
    onCapture: (android.graphics.Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val scope = rememberCoroutineScope()
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(lensFacing) {
        val provider = context.awaitCameraProvider()
        val preview = androidx.camera.core.Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val selector = androidx.camera.core.CameraSelector.Builder()
            .requireLensFacing(lensFacing).build()
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(hint, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Photo ${shots.coerceAtMost(max)} / $max  ·  ${pose.prompt}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onToggleCamera) {
                Icon(
                    imageVector = Icons.Outlined.FlipCameraAndroid,
                    contentDescription = "Toggle camera"
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(MaterialTheme.shapes.medium)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (busy) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = {
                scope.launch {
                    runCatching { imageCapture.takeBitmap(context) }
                        .onSuccess { onCapture(it) }
                }
            },
            enabled = !busy && pose != PoseStep.Done,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Processing…" else "Capture this pose") }
    }
}

@Composable
private fun ShotDots(count: Int, max: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(max) { i ->
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (i < count) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (i < count) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StudentStatusList(
    students: List<Student>,
    onEnrollFace: (Student) -> Unit,
) {
    if (students.isEmpty()) {
        Text(
            "No students found. Import an Excel file or enroll a new student.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        students.forEach { student ->
            val isActive = student.embeddingCount > 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(student.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        student.studentId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActive) {
                    InputChip(
                        selected = true,
                        onClick = { },
                        label = { Text("Active") },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                } else {
                    Button(onClick = { onEnrollFace(student) }) {
                        Text("Enroll face")
                    }
                }
            }
        }
    }
}
'@
Set-Content -Path "app/src/main/java/com/attendancefr/ui/screens/enroll/EnrollScreen.kt" -Value $screenText

# ------------------------------------------------------------------
# 6. Patch app/build.gradle.kts dependencies
# ------------------------------------------------------------------
$gradlePath = "app/build.gradle.kts"
if (Test-Path $gradlePath) {
    Write-Host "==> Patching app/build.gradle.kts dependencies..."
    $gradleText = Get-Content $gradlePath -Raw
    $gradleText = $gradleText -replace "(dependencies\s*\{)", "`$1`n    implementation(`"org.apache.poi:poi:5.2.3`")`n    implementation(`"org.apache.poi:poi-ooxml:5.2.3`")`n    implementation(`"org.apache.poi:poi-ooxml-lite:5.2.3`")`n    implementation(`"com.fasterxml.aalto-xml:aalto-xml:1.3.2`")"
    Set-Content $gradlePath $gradleText
} else {
    Write-Warning "app/build.gradle.kts not found."
}

Write-Host ""
Write-Host "========================================"
Write-Host "Done."
Write-Host "Manual step remaining:"
Write-Host "Add this packaging block inside android { } in app/build.gradle.kts if not already present:"
Write-Host ""
Write-Host '    packaging {'
Write-Host '        resources {'
Write-Host '            excludes += "/META-INF/DEPENDENCIES"'
Write-Host '            excludes += "/META-INF/LICENSE"'
Write-Host '            excludes += "/META-INF/LICENSE.txt"'
Write-Host '            excludes += "/META-INF/license.txt"'
Write-Host '            excludes += "/META-INF/NOTICE"'
Write-Host '            excludes += "/META-INF/NOTICE.txt"'
Write-Host '            excludes += "/META-INF/notice.txt"'
Write-Host '        }'
Write-Host '    }'
Write-Host ""
Write-Host "Then build with: .\gradlew.bat :app:installDebug"
Write-Host "========================================"