package com.attendancefr.ui.screens.attendance

import android.view.ViewGroup
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.attendancefr.domain.model.MatchResult
import com.attendancefr.ui.camera.DetectedFaceBox
import com.attendancefr.ui.camera.FaceOverlay
import com.attendancefr.ui.camera.awaitCameraProvider
import com.attendancefr.ui.camera.bindWithFallback
import com.attendancefr.ui.camera.takeBitmap
import com.attendancefr.ui.components.CameraPermissionGate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import android.media.ToneGenerator
import android.media.AudioManager


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(
    onManual: (String) -> Unit,
    vm: AttendanceViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    
    LaunchedEffect(Unit) {
        vm.soundEvents.collect { event ->
            when (event.type) {
                SoundType.SUCCESS -> {
                    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 250)
                }
                SoundType.FAILURE -> {
                    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                    tone.startTone(ToneGenerator.TONE_PROP_NACK, 400)
                }
            }
        }
    }
    CameraPermissionGate {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Take attendance", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { onManual(state.selectedClass) }) {
                    Icon(Icons.Outlined.ListAlt, contentDescription = "Manual override")
                }
            }
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ClassDropdown(
                    classes = state.classes.map { it.name },
                    selected = state.selectedClass,
                    onSelect = vm::selectClass,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Auto", style = MaterialTheme.typography.labelSmall)
                    Switch(checked = state.autoCapture, onCheckedChange = vm::setAutoCapture)
                }
            }
            Text(
                "${state.enrolledCount} faces enrolled  ·  threshold ${"%.2f".format(state.threshold)}  ·  ${vm.todayLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (state.modelMissing) {
                Text(
                    "Model missing — capture will fail until mobile_face_net.tflite is in assets/. See SETUP.md.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LiveCapture(
                hint = state.hint,
                busy = state.busy,
                auto = state.autoCapture,
                last = state.lastResult,
                onCaptureBitmap = { bmp, auto -> vm.capture(bmp, fromAuto = auto) },
            )
            SessionStrip(state.sessionMarks)
        }

        val unknown = state.lastResult as? MatchResult.Unknown
        if (unknown != null) {
            UnknownFaceDialog(
                closestName = unknown.closestStudent?.name,
                confidence = unknown.confidence,
                onDismiss = vm::dismissUnknown,
                onManual = {
                    vm.dismissUnknown()
                    onManual(state.selectedClass)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassDropdown(
    classes: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected.ifBlank { "Select class" },
            onValueChange = {},
            readOnly = true,
            label = { Text("Class") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (classes.isEmpty()) {
                DropdownMenuItem(text = { Text("No classes yet — enroll a student first") }, onClick = { expanded = false })
            }
            classes.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(name); expanded = false })
            }
        }
    }
}

@Composable
private fun LiveCapture(
    hint: String,
    busy: Boolean,
    auto: Boolean,
    last: MatchResult?,
    onCaptureBitmap: (android.graphics.Bitmap, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val scope = rememberCoroutineScope()
    var boxes by remember { mutableStateOf<List<DetectedFaceBox>>(emptyList()) }
    var frameW by remember { mutableStateOf(1) }
    var frameH by remember { mutableStateOf(1) }
    var lastAutoFaceCount by remember { mutableStateOf(0) }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build()
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            detector.close()
        }
    }

    LaunchedEffect(Unit) {
        val provider = context.awaitCameraProvider()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var inFlight = false
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            if (inFlight) {
                proxy.close()
                return@setAnalyzer
            }
            val media = proxy.image
            if (media == null) {
                proxy.close()
                return@setAnalyzer
            }
            inFlight = true
            val rotation = proxy.imageInfo.rotationDegrees
            val image = InputImage.fromMediaImage(media, rotation)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    val w = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
                    val h = if (rotation == 90 || rotation == 270) proxy.width else proxy.height
                    boxes = faces.map { it.toDetected() }
                    frameW = w
                    frameH = h
                }
                .addOnCompleteListener {
                    inFlight = false
                    proxy.close()
                }
        }
        provider.bindWithFallback(lifecycleOwner, preview, imageCapture, analysis)
    }

    // Auto-trigger when a single stable face is in frame.
    LaunchedEffect(auto, boxes, busy) {
        val count = boxes.size
        if (auto && !busy && count == 1 && lastAutoFaceCount != 1) {
            lastAutoFaceCount = 1
            runCatching { imageCapture.takeBitmap(context) }
                .onSuccess { onCaptureBitmap(it, true) }
        }
        if (count != 1) lastAutoFaceCount = count
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(MaterialTheme.shapes.medium)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            FaceOverlay(
                boxes = boxes,
                frameWidth = frameW,
                frameHeight = frameH,
                color = overlayColor(last),
                modifier = Modifier.fillMaxSize(),
            )
            ResultBadge(last, Modifier.align(Alignment.TopCenter).padding(12.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    runCatching { imageCapture.takeBitmap(context) }
                        .onSuccess { onCaptureBitmap(it, false) }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Matching…" else "Capture & match") }
    }
}

@Composable
private fun ResultBadge(last: MatchResult?, modifier: Modifier = Modifier) {
    val (label, color) = when (last) {
        is MatchResult.Matched ->
            (if (last.alreadyMarked) "Already marked: ${last.student.name}" else "✓ ${last.student.name}") to Color(0xFF166534)
        is MatchResult.Unknown -> "Unknown face" to Color(0xFF92400E)
        is MatchResult.NoFace -> "No face" to Color(0xFF6B7280)
        is MatchResult.MultipleFaces -> "Multiple faces" to Color(0xFF92400E)
        is MatchResult.PoorQuality -> last.reason to Color(0xFF9A3412)
        is MatchResult.Error -> last.message to Color(0xFF991B1B)
        null -> return
    }
    Surface(modifier = modifier, color = color, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (last is MatchResult.Matched) Icons.Outlined.CheckCircle else Icons.Outlined.HelpOutline,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun overlayColor(last: MatchResult?): Color = when (last) {
    is MatchResult.Matched -> Color(0xFF4ADE80)
    is MatchResult.Unknown -> Color(0xFFFBBF24)
    is MatchResult.PoorQuality, is MatchResult.MultipleFaces -> Color(0xFFF97316)
    else -> Color(0xFF4ADE80)
}

@Composable
private fun SessionStrip(marks: List<SessionMark>) {
    if (marks.isEmpty()) return
    LazyRow(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(marks.takeLast(12).reversed()) { m ->
            FilterChip(
                selected = !m.alreadyMarked,
                onClick = {},
                label = { Text("${m.student.name}${if (m.alreadyMarked) " · already" else ""}") },
            )
        }
    }
}

@Composable
private fun UnknownFaceDialog(
    closestName: String?,
    confidence: Float,
    onDismiss: () -> Unit,
    onManual: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.HelpOutline, contentDescription = null) },
        title = { Text("Unknown face") },
        text = {
            Text(
                buildString {
                    append("Best automatic match was below the confidence threshold (${"%.2f".format(confidence)}).")
                    if (!closestName.isNullOrBlank()) append(" Closest enrolled student: $closestName.")
                    append(" Assign manually or dismiss — the app will not guess.")
                }
            )
        },
        confirmButton = { Button(onClick = onManual) { Text("Assign manually") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Dismiss") } },
    )
}

private fun Face.toDetected(): DetectedFaceBox {
    val b = boundingBox
    val l = leftEyeOpenProbability ?: 1f
    val r = rightEyeOpenProbability ?: 1f
    return DetectedFaceBox(
        box = android.graphics.RectF(b),
        trackingId = trackingId,
        eyesOpen = l >= 0.35f && r >= 0.35f,
        yaw = headEulerAngleY,
        pitch = headEulerAngleX,
    )
}
