package com.attendancefr.ui.screens.attendance

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.attendancefr.data.prefs.SettingsRepository
import com.attendancefr.data.repository.AttendanceRepository
import com.attendancefr.data.repository.ClassRepository
import com.attendancefr.data.repository.StudentRepository
import com.attendancefr.domain.model.AttendanceStatus
import com.attendancefr.domain.model.ClassSection
import com.attendancefr.domain.model.MatchResult
import com.attendancefr.domain.model.Student
import com.attendancefr.ml.FaceDetectorHelper
import com.attendancefr.ml.FaceEmbeddingEngine
import com.attendancefr.ml.FaceMatcher
import com.attendancefr.ml.ImageUtils
import com.attendancefr.ml.QualityAnalyzer
import com.attendancefr.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

data class SessionMark(
    val student: Student,
    val confidence: Float?,
    val alreadyMarked: Boolean,
    val status: AttendanceStatus,
)

data class AttendanceUiState(
    val classes: List<ClassSection> = emptyList(),
    val selectedClass: String = "",
    val threshold: Float = SettingsRepository.DEFAULT_THRESHOLD,
    val modelMissing: Boolean = false,
    val enrolledCount: Int = 0,
    val busy: Boolean = false,
    val hint: String = "Point the camera at a student, then tap Capture.",
    val lastResult: MatchResult? = null,
    val unknownProbe: FloatArray? = null,
    val sessionMarks: List<SessionMark> = emptyList(),
    val autoCapture: Boolean = false,
)

enum class SoundType { SUCCESS, FAILURE }
class SoundEvent(val type: SoundType)

@HiltViewModel
class AttendanceViewModel @Inject constructor(
    private val students: StudentRepository,
    private val attendance: AttendanceRepository,
    private val classes: ClassRepository,
    private val settings: SettingsRepository,
    private val detector: FaceDetectorHelper,
    private val engine: FaceEmbeddingEngine,
    private val matcher: FaceMatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(AttendanceUiState())
    val state: StateFlow<AttendanceUiState> = _state.asStateFlow()
    
    private val _soundEvents = MutableSharedFlow<SoundEvent>(extraBufferCapacity = 1)
    val soundEvents: SharedFlow<SoundEvent> = _soundEvents.asSharedFlow()

    @Volatile private var gallery: List<FaceMatcher.GalleryItem> = emptyList()
    @Volatile private var studentIndex: Map<Long, Student> = emptyMap()
    @Volatile private var lastAutoTs: Long = 0L

    init {
        _state.update { it.copy(modelMissing = !engine.isModelAvailable) }
        viewModelScope.launch {
            classes.observeAll().collect { list ->
                val last = settings.lastSelectedClass.first()
                _state.update { s ->
                    val pick = when {
                        s.selectedClass.isNotBlank() -> s.selectedClass
                        last.isNotBlank() && list.any { it.name == last } -> last
                        else -> list.firstOrNull()?.name.orEmpty()
                    }
                    s.copy(classes = list, selectedClass = pick)
                }
            }
        }
        viewModelScope.launch {
            settings.confidenceThreshold.collect { t ->
                _state.update { it.copy(threshold = t) }
            }
        }
        viewModelScope.launch {
            students.observeStudents().collect { list ->
                studentIndex = list.associateBy { it.id }
                _state.update { it.copy(enrolledCount = list.count { it.embeddingCount > 0 }) }
                reloadGallery()
            }
        }
    }

    fun selectClass(name: String) {
        _state.update { it.copy(selectedClass = name) }
        viewModelScope.launch { settings.setLastSelectedClass(name) }
    }

    fun setAutoCapture(enabled: Boolean) = _state.update { it.copy(autoCapture = enabled) }

    fun dismissUnknown() = _state.update { it.copy(lastResult = null, unknownProbe = null) }

    fun capture(bitmap: Bitmap, fromAuto: Boolean = false) {
        val now = System.currentTimeMillis()
        if (fromAuto && now - lastAutoTs < 1600L) return
        if (_state.value.busy) return
        lastAutoTs = now
        viewModelScope.launch {
            _state.update { it.copy(busy = true, hint = "Matching…") }
            val outcome = withContext(Dispatchers.Default) { matchBitmap(bitmap) }
            handle(outcome)
        }
    }

        fun assignUnknown(student: Student) {
        viewModelScope.launch {
            val className = _state.value.selectedClass
            val result = attendance.mark(
                studentId = student.id,
                className = className,
                status = AttendanceStatus.Present,
                confidence = null,
                isManual = true,
            )
            val already = result is AttendanceRepository.MarkOutcome.AlreadyMarked
            _state.update {
                it.copy(
                    lastResult = MatchResult.Matched(student, 0f, already),
                    unknownProbe = null,
                    hint = if (already) "${student.name} already marked today in $className" else "Marked ${student.name} (manual)",
                    sessionMarks = it.sessionMarks + SessionMark(
                        student, null, already, AttendanceStatus.Present
                    ),
                )
            }
        }
    }

    private suspend fun reloadGallery() {
        val pairs = students.getAllEmbeddings()
        gallery = pairs.map { (id, vec) -> FaceMatcher.GalleryItem(id, vec) }
    }

    private suspend fun matchBitmap(bitmap: Bitmap): MatchResult {
        if (!engine.isModelAvailable) {
            return MatchResult.Error("TFLite model missing. See SETUP.md.")
        }
        return try {
            val scaled = ImageUtils.downscaleIfNeeded(bitmap, 720)
            val faces = detector.detectStill(scaled)
                        when {
                faces.isEmpty() -> MatchResult.NoFace
                else -> {
                    // If multiple faces, process only the largest one
                    val targetFace = if (faces.size > 1) {
                        faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: faces.first()
                    } else {
                        faces.first()
                    }
                    val quality = detector.assessQuality(listOf(targetFace), scaled.width, scaled.height)
                    if (!quality.accepted) {
                        MatchResult.PoorQuality(quality.reason ?: "Poor face quality")
                    } else {
                        val crop = detector.paddedCropRect(
                            targetFace.boundingBox,
                            scaled.width,
                            scaled.height,
                        )
                        val lighting = QualityAnalyzer.lighting(scaled, crop)
                        if (!lighting.ok) {
                            MatchResult.PoorQuality(lighting.hint ?: "Move to better lighting.")
                        } else {
                            val probe = engine.embed(scaled, crop)
                                                        val className = _state.value.selectedClass
                            val scoped = if (className.isBlank()) gallery
                            else gallery.filter { studentIndex[it.studentId]?.classNames?.contains(className) == true }
                            val best = matcher.best(probe, scoped)
                            val threshold = _state.value.threshold
                            if (best == null) {
                                MatchResult.Unknown(0f, null)
                            } else {
                                val student = studentIndex[best.studentId]
                                if (student == null) {
                                    MatchResult.Unknown(best.similarity, null)
                                } else if (best.similarity >= threshold) {
                                    MatchResult.Matched(student, best.similarity, false)
                                } else {
                                    MatchResult.Unknown(best.similarity, student)
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            MatchResult.Error(t.message ?: "Match failed")
        }
    }

    private suspend fun handle(result: MatchResult) {
        when (result) {
                  is MatchResult.Matched -> {
                _soundEvents.emit(SoundEvent(SoundType.SUCCESS))
                val className = _state.value.selectedClass
                val already = attendance.alreadyMarkedToday(result.student.id, className)
                if (!already) {
                    attendance.mark(
                        studentId = result.student.id,
                        className = className,
                        status = AttendanceStatus.Present,
                        confidence = result.confidence,
                        isManual = false,
                    )
                }
                _state.update {
                    it.copy(
                        busy = false,
                        lastResult = result.copy(alreadyMarked = already),
                        hint = if (already)
                            "${result.student.name} already marked today in $className"
                        else "Present: ${result.student.name}  (${fmt(result.confidence)})",
                        sessionMarks = it.sessionMarks + SessionMark(
                            result.student, result.confidence, already, AttendanceStatus.Present
                        ),
                    )
                }
            }
                        is MatchResult.Unknown -> {
                _soundEvents.emit(SoundEvent(SoundType.FAILURE))
                _state.update {
                    it.copy(
                        busy = false,
                        lastResult = result,
                        hint = "Unknown face — tap to review",
                    )
                }
            }
            is MatchResult.NoFace -> _state.update {
                it.copy(busy = false, lastResult = result, hint = "No face detected. Hold at eye level.")
            }
            is MatchResult.MultipleFaces -> _state.update {
                it.copy(
                    busy = false,
                    lastResult = result,
                    hint = "Multiple faces (${result.count}). Isolate one student.",
                )
            }
                        is MatchResult.PoorQuality -> {
                _soundEvents.emit(SoundEvent(SoundType.FAILURE))
                _state.update {
                    it.copy(
                        busy = false,
                        lastResult = result,
                        hint = lightingHint(result.reason),
                    )
                }
            }
                        is MatchResult.Error -> {
                _soundEvents.emit(SoundEvent(SoundType.FAILURE))
                _state.update {
                    it.copy(busy = false, lastResult = result, hint = result.message)
                }
            }
        }
    }

    private fun lightingHint(reason: String): String {
        val lower = reason.lowercase()
        return if ("close" in lower || "light" in lower || "dark" in lower) {
            "$reason  Move to better lighting if the image looks noisy."
        } else reason
    }

    private fun fmt(v: Float) = String.format("%.2f", v)

    fun todayLabel(): String = DateUtils.pretty(DateUtils.today())
}
