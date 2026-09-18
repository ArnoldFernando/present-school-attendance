package com.attendancefr.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.attendancefr.domain.model.FaceQuality
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Thin wrapper around ML Kit Face Detection (on-device). Used both for the
 * live preview overlay and as a gate before we crop a face for embedding.
 *
 * Performance notes: FAST mode is enough for bounding boxes + Euler angles
 * + eye-open probabilities, and stays well under a frame on mid-range
 * hardware. ACCURATE mode is used only on still captures during enrollment.
 */
class FaceDetectorHelper {

    private val liveDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
    )

    private val stillDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.20f)
            .build()
    )

    suspend fun detectLive(image: InputImage): List<Face> = detect(liveDetector, image)

    suspend fun detectStill(bitmap: Bitmap, rotationDegrees: Int = 0): List<Face> {
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        return detect(stillDetector, image)
    }

    private suspend fun detect(detector: FaceDetector, image: InputImage): List<Face> =
        suspendCancellableCoroutine { cont ->
            detector.process(image)
                .addOnSuccessListener { faces -> if (cont.isActive) cont.resume(faces) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }

    /**
     * Enrollment / capture quality gate.
     *
     * Rules (tuned for a classroom tablet held ~0.6–1.2 m from the student):
     *  - exactly one face
     *  - face occupies at least 18% of the shorter image side
     *  - eyes-open probability ≥ 0.4 (ML Kit reports 0 when unknown)
     *  - head yaw/pitch within the requested pose window
     */
    fun assessQuality(
        faces: List<Face>,
        imageWidth: Int,
        imageHeight: Int,
        expectedYaw: Float = 0f,
        yawTolerance: Float = 18f,
        pitchTolerance: Float = 20f,
    ): FaceQuality {
        if (faces.isEmpty()) {
            return FaceQuality(accepted = false, reason = "No face detected. Hold the camera at eye level.")
        }
        if (faces.size > 1) {
            return FaceQuality(
                accepted = false,
                reason = "Multiple faces in frame (${faces.size}). Isolate one student.",
            )
        }
        val face = faces.first()
        val box = face.boundingBox
        val shortSide = min(imageWidth, imageHeight).toFloat().coerceAtLeast(1f)
        val faceShort = min(box.width(), box.height()).toFloat()
        if (faceShort / shortSide < 0.18f) {
            return FaceQuality(accepted = false, reason = "Move closer so the face fills the guide.")
        }

        val cx = box.centerX().toFloat()
        val cy = box.centerY().toFloat()
        val offX = abs(cx - imageWidth / 2f) / imageWidth
        val offY = abs(cy - imageHeight / 2f) / imageHeight
        if (offX > 0.28f || offY > 0.30f) {
            return FaceQuality(accepted = false, reason = "Center the face in the frame.")
        }

        val leftEye = face.leftEyeOpenProbability ?: 1f
        val rightEye = face.rightEyeOpenProbability ?: 1f
        val eyesOpen = leftEye >= 0.35f && rightEye >= 0.35f
        if (!eyesOpen) {
            return FaceQuality(
                accepted = false,
                reason = "Eyes appear closed. Look at the camera.",
                eyesOpen = false,
            )
        }

        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX
        val roll = face.headEulerAngleZ
        if (abs(yaw - expectedYaw) > yawTolerance) {
            val hint = if (expectedYaw > 5f) "Turn slightly right."
            else if (expectedYaw < -5f) "Turn slightly left."
            else "Look straight at the camera."
            return FaceQuality(
                accepted = false,
                reason = hint,
                eyesOpen = true,
                headYaw = yaw,
                headPitch = pitch,
                headRoll = roll,
            )
        }
        if (abs(pitch) > pitchTolerance) {
            return FaceQuality(
                accepted = false,
                reason = "Keep your chin level — don't look too far up or down.",
                eyesOpen = true,
                headYaw = yaw,
                headPitch = pitch,
                headRoll = roll,
            )
        }

        // Very dark / very bright frames tend to produce tiny, noisy boxes.
        // We cannot read luma here (no bitmap), so we only flag extreme sizes.
        val lightingHint = faceShort / shortSide > 0.85f

        return FaceQuality(
            accepted = true,
            eyesOpen = true,
            headYaw = yaw,
            headPitch = pitch,
            headRoll = roll,
            lightingHint = lightingHint,
        )
    }

    /**
     * Expand the ML Kit box by [paddingRatio] and clamp to the bitmap so the
     * crop still contains hairline / jaw — MobileFaceNet was trained on
     * loosely cropped faces, not tight boxes.
     */
    fun paddedCropRect(box: Rect, imageWidth: Int, imageHeight: Int, paddingRatio: Float = 0.25f): Rect {
        val padX = (box.width() * paddingRatio).toInt()
        val padY = (box.height() * paddingRatio).toInt()
        val left = max(0, box.left - padX)
        val top = max(0, box.top - padY)
        val right = min(imageWidth, box.right + padX)
        val bottom = min(imageHeight, box.bottom + padY)
        return Rect(left, top, right, bottom)
    }

    fun close() {
        liveDetector.close()
        stillDetector.close()
    }
}
