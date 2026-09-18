package com.attendancefr.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Runs a bundled MobileFaceNet (or compatible) TFLite model to produce a
 * L2-normalized face embedding.
 *
 * Expected input: 112×112 RGB, float32, typically in [-1, 1].
 * Expected output: 128-d or 512-d float32 vector.
 *
 * The model file must live at `assets/mobile_face_net.tflite`. See SETUP.md.
 * If the file is missing we fail with a clear exception rather than crashing
 * inside the Interpreter constructor.
 */
class FaceEmbeddingEngine(private val context: Context) {

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    var embeddingDim: Int = 128
        private set

    val isModelAvailable: Boolean
        get() = try {
            context.assets.open(MODEL_ASSET).close()
            true
        } catch (_: Exception) {
            false
        }

    @Synchronized
    fun ensureLoaded() {
        if (interpreter != null) return
        if (!isModelAvailable) {
            throw IllegalStateException(
                "TFLite model missing. Copy mobile_face_net.tflite into app/src/main/assets/. See SETUP.md."
            )
        }
        val model = FileUtil.loadMappedFile(context, MODEL_ASSET)
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        val interp = Interpreter(model, options)
        val outShape = interp.getOutputTensor(0).shape()
        embeddingDim = outShape.last()
        interpreter = interp
    }

    /**
     * Crop [faceRect] from [bitmap], resize to 112×112, run the model, L2-normalize.
     * Must be called off the main thread.
     */
    fun embed(bitmap: Bitmap, faceRect: Rect): FloatArray {
        ensureLoaded()
        val interp = interpreter ?: error("Interpreter not loaded")
        val crop = safeCrop(bitmap, faceRect)
        val resized = Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true)
        if (crop !== bitmap && crop !== resized) crop.recycle()

        val input = bitmapToBuffer(resized)
        if (resized !== bitmap) resized.recycle()

        val output = Array(1) { FloatArray(embeddingDim) }
        interp.run(input, output)
        return l2Normalize(output[0])
    }

    fun close() {
        synchronized(this) {
            interpreter?.close()
            interpreter = null
        }
    }

    private fun safeCrop(src: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, src.width - 1)
        val top = rect.top.coerceIn(0, src.height - 1)
        val width = (rect.width()).coerceAtLeast(1).coerceAtMost(src.width - left)
        val height = (rect.height()).coerceAtLeast(1).coerceAtMost(src.height - top)
        return Bitmap.createBitmap(src, left, top, width, height)
    }

    /**
     * MobileFaceNet convention: (pixel / 127.5) - 1.0 → [-1, 1].
     * If you swap in a different model that expects [0, 1] ImageNet
     * normalization, change this function and document it in SETUP.md.
     */
    private fun bitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)
            buf.putFloat(r / 127.5f - 1f)
            buf.putFloat(g / 127.5f - 1f)
            buf.putFloat(b / 127.5f - 1f)
        }
        buf.rewind()
        return buf
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(v.size) { i -> v[i] / norm }
    }

    companion object {
        const val MODEL_ASSET = "mobile_face_net.tflite"
        const val INPUT_SIZE = 112
    }
}
