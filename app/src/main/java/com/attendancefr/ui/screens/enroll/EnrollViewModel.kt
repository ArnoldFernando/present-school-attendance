package com.attendancefr.ui.screens.enroll

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.attendancefr.data.prefs.SettingsRepository
import com.attendancefr.data.repository.ClassRepository
import com.attendancefr.data.repository.StudentRepository
import com.attendancefr.domain.model.ClassSection
import com.attendancefr.ml.FaceDetectorHelper
import com.attendancefr.ml.FaceEmbeddingEngine
import com.attendancefr.ml.ImageUtils
import com.attendancefr.ml.QualityAnalyzer
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val name: String = "",
    val roll: String = "",
    val classNames: List<String> = emptyList(),
    val classes: List<ClassSection> = emptyList(),
    val pose: PoseStep = PoseStep.Straight,
    val shots: List<CapturedShot> = emptyList(),
    val hint: String = PoseStep.Straight.prompt,
    val busy: Boolean = false,
    val error: String? = null,
    val modelMissing: Boolean = false,
    val saved: Boolean = false,
    val minShots: Int = 3,
    val maxShots: Int = 5,
)

@HiltViewModel
class EnrollViewModel @Inject constructor(
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