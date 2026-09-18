package com.attendancefr.ui.camera

import android.graphics.RectF
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

data class DetectedFaceBox(
    val box: RectF,
    val trackingId: Int?,
    val eyesOpen: Boolean,
    val yaw: Float,
    val pitch: Float,
)

/**
 * Live CameraX preview with an ML Kit bounding-box overlay. Analysis runs on
 * a single-thread executor; we drop frames if the previous one is still
 * in flight so mid-range devices stay at interactive FPS.
 */
@Composable
fun FaceCameraPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    onFaces: (List<DetectedFaceBox>, frameWidth: Int, frameHeight: Int) -> Unit = { _, _, _ -> },
    overlayHintColor: Color = Color(0xFF4ADE80),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
    var boxes by remember { mutableStateOf<List<DetectedFaceBox>>(emptyList()) }
    var frameW by remember { mutableStateOf(1) }
    var frameH by remember { mutableStateOf(1) }

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

    LaunchedEffect(lensFacing) {
        val provider = context.awaitCameraProvider()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
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
                    val mapped = faces.map { it.toBox() }
                    boxes = mapped
                    frameW = w
                    frameH = h
                    onFaces(mapped, w, h)
                }
                .addOnCompleteListener {
                    inFlight = false
                    proxy.close()
                }
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.bindWithSelector(lifecycleOwner, selector, preview, analysis)
    }

    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        FaceOverlay(
            boxes = boxes,
            frameWidth = frameW,
            frameHeight = frameH,
            color = overlayHintColor,
            modifier = Modifier.fillMaxSize(),
            mirror = lensFacing == CameraSelector.LENS_FACING_FRONT,
        )
    }
}

@Composable
fun FaceOverlay(
    boxes: List<DetectedFaceBox>,
    frameWidth: Int,
    frameHeight: Int,
    color: Color,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
) {
    Canvas(modifier) {
        if (frameWidth <= 0 || frameHeight <= 0) return@Canvas
        val sx = size.width / frameWidth
        val sy = size.height / frameHeight
        boxes.forEach { face ->
            val rect = face.box
            val left = if (mirror) frameWidth - rect.right else rect.left
            val right = if (mirror) frameWidth - rect.left else rect.right
            val top = rect.top
            val bottom = rect.bottom

            val l = left * sx
            val t = top * sy
            val w = (right - left) * sx
            val h = (bottom - top) * sy

            val stroke = if (face.eyesOpen) color else Color(0xFFFBBF24)
            drawRect(
                color = stroke,
                topLeft = Offset(l, t),
                size = Size(w, h),
                style = Stroke(width = 4f),
            )
            // Corner ticks for a "scanner" feel.
            val tick = minOf(w, h) * 0.18f
            val c = stroke
            drawLine(c, Offset(l, t), Offset(l + tick, t), 8f)
            drawLine(c, Offset(l, t), Offset(l, t + tick), 8f)
            drawLine(c, Offset(l + w, t), Offset(l + w - tick, t), 8f)
            drawLine(c, Offset(l + w, t), Offset(l + w, t + tick), 8f)
            drawLine(c, Offset(l, t + h), Offset(l + tick, t + h), 8f)
            drawLine(c, Offset(l, t + h), Offset(l, t + h - tick), 8f)
            drawLine(c, Offset(l + w, t + h), Offset(l + w - tick, t + h), 8f)
            drawLine(c, Offset(l + w, t + h), Offset(l + w, t + h - tick), 8f)
        }
    }
}

private fun Face.toBox(): DetectedFaceBox {
    val b = boundingBox
    val leftOpen = leftEyeOpenProbability ?: 1f
    val rightOpen = rightEyeOpenProbability ?: 1f
    return DetectedFaceBox(
        box = RectF(b),
        trackingId = trackingId,
        eyesOpen = leftOpen >= 0.35f && rightOpen >= 0.35f,
        yaw = headEulerAngleY,
        pitch = headEulerAngleX,
    )
}