package com.attendancefr.ui.screens.enroll

import android.view.ViewGroup
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.attendancefr.ui.camera.awaitCameraProvider
import com.attendancefr.ui.camera.bindWithFallback
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

    CameraPermissionGate {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    if (state.reenrollStudentId == null) "Enroll student" else "Re-enroll face",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Capture 3–5 photos at slightly different angles. The app rejects blurry, off-center, or eyes-closed shots.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.name,
                    onValueChange = vm::onName,
                    label = { Text("Full name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = state.reenrollStudentId == null || true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.roll,
                    onValueChange = vm::onRoll,
                    label = { Text("Student ID / roll number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
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
                    onCapture = vm::capture,
                )
                Spacer(Modifier.height(8.dp))
                ShotDots(count = state.shots.size, max = state.maxShots)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = vm::save,
                    enabled = !state.busy && state.shots.size >= state.minShots,
                    modifier = Modifier.weight(1f),
                ) { Text("Save student") }
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

    LaunchedEffect(Unit) {
        val provider = context.awaitCameraProvider()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        provider.bindWithFallback(lifecycleOwner, preview, imageCapture)
    }

    Column {
        Text(hint, style = MaterialTheme.typography.titleMedium)
        Text("Photo ${shots.coerceAtMost(max)} / $max  ·  ${pose.prompt}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(MaterialTheme.shapes.medium)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
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