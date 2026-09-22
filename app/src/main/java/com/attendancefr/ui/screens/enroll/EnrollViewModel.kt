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
import android.util.Log
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
    val showImportGuide: Boolean = false,
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
    val photoPath: String? = null,
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
                        classes = list.filter { it.name.isNotBlank() },
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
            students.observeStudents().collect { list ->
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

                // Collect unique class names from import and add them to repository
                val importedClasses = rows.map { it.className }.filter { it.isNotBlank() }.distinct()
                importedClasses.forEach { className ->
                    runCatching { classes.add(className.trim()) }
                }

                var count = 0
                rows.forEach { row ->
                    val classList = if (row.className.isNotBlank()) listOf(row.className) else listOf(targetClass)
                    val added = students.importIfNotExists(
                        studentId = row.rollNumber,
                        name = row.name,
                        classNames = classList
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

    fun showImportGuide() = _state.update { it.copy(showImportGuide = true) }
    fun hideImportGuide() = _state.update { it.copy(showImportGuide = false) }

    fun downloadTemplate() {
        viewModelScope.launch {
            try {
                val templateContent = """Full name,Student ID,Class
"Lastname, Firstname MiddleInitial",26-00001,1B
"Delos Santos, Juan A.",26-00002,1B
"Garcia, Maria Clara S.",26-00003,1A
"Lastname, Firstname",26-00004,2A
"""
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val outFile = java.io.File(downloadsDir, "AttendanceFR_Student_Template.csv")
                outFile.writeText(templateContent)
                _state.update { it.copy(error = "Template saved to Downloads: ${outFile.name}", showImportGuide = false) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not save template: ${e.message}") }
            }
        }
    }

    fun clearEnrollment() {
        _state.update {
            EnrollUiState(
                classes = it.classes,
                classNames = it.classNames,
                modelMissing = it.modelMissing,
                photoPath = null,
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
                                                                            android.util.Log.d("FaceThumb", "photoPath before capture: ${s.photoPath}")
                        android.util.Log.d("FaceThumb", "photoPath before capture: ${s.photoPath}")
                        val thumbnailPath = if (s.photoPath == null) {
                            saveFaceThumbnail(scaled, crop)
                        } else null
                        val embedding = engine.embed(scaled, crop)
                        android.util.Log.d("FaceThumb", "thumbnailPath generated: $thumbnailPath")
                        android.util.Log.d("FaceThumb", "thumbnailPath generated: $thumbnailPath")
                        CaptureOutcome.Accepted(embedding, thumbnailPath)
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
                        val savedPath = s.photoPath ?: result.thumbnailPath
                    val nextPose = nextPose(s.pose, nextShots.size, s.maxShots)
                    _state.update {
                                            it.copy(
                        busy = false,
                        photoPath = savedPath,
                            error = null,
                        shots = nextShots,
                        pose = nextPose,
                        hint = if (nextPose == PoseStep.Done)
                            "All set — tap Save student."
                        else nextPose.prompt,
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
                    students.updateDetails(existing, s.roll, s.name, s.classNames, s.photoPath)
                    students.replaceEmbeddings(existing, embeddings, s.photoPath)
                } else {
                    android.util.Log.d("FaceThumb", "Saving student with photoPath: ${s.photoPath}")
                    android.util.Log.d("FaceThumb", "Saving student with photoPath: ${s.photoPath}")
                    students.enroll(s.roll, s.name, s.classNames, embeddings, s.photoPath)
                }
                settings.setLastSelectedClass(s.classNames.firstOrNull().orEmpty())
            }.onSuccess {
                _state.update { it.copy(busy = false, saved = true) }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }
    private fun saveFaceThumbnail(bitmap: android.graphics.Bitmap, faceRect: android.graphics.Rect): String? {
        return try {
            val left = faceRect.left.coerceIn(0, bitmap.width - 1)
            val top = faceRect.top.coerceIn(0, bitmap.height - 1)
            val width = faceRect.width().coerceAtLeast(1).coerceAtMost(bitmap.width - left)
            val height = faceRect.height().coerceAtLeast(1).coerceAtMost(bitmap.height - top)
            val crop = android.graphics.Bitmap.createBitmap(bitmap, left, top, width, height)
            val thumb = android.graphics.Bitmap.createScaledBitmap(crop, 200, 200, true)
            val dir = java.io.File(context.filesDir, "face_thumbnails").apply { mkdirs() }
            val file = java.io.File(dir, "face_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { out ->
                thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (crop !== bitmap) crop.recycle()
            if (thumb !== bitmap) thumb.recycle()
            android.util.Log.d("FaceThumb", "Thumbnail saved to: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("FaceThumb", "Failed to save thumbnail", e)
            null
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
        data class Accepted(val embedding: FloatArray, val thumbnailPath: String? = null) : CaptureOutcome()
        data class Rejected(val reason: String) : CaptureOutcome()
    }
}













