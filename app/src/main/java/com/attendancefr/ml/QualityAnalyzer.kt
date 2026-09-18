package com.attendancefr.ml

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

/**
 * Cheap on-device checks that ML Kit does not provide: exposure (mean luma)
 * and a Laplacian-style sharpness score. Used as a *hint* layer on top of
 * the face-geometry gate; we never silently accept a dark/blurry enrollment.
 */
object QualityAnalyzer {

    data class Lighting(
        val meanLuma: Float,
        val tooDark: Boolean,
        val tooBright: Boolean,
    ) {
        val ok: Boolean get() = !tooDark && !tooBright
        val hint: String? = when {
            tooDark -> "Move to better lighting — the face is too dark."
            tooBright -> "Move out of harsh backlight / glare."
            else -> null
        }
    }

    fun lighting(bitmap: Bitmap, region: Rect? = null): Lighting {
        val box = region?.let {
            Rect(
                it.left.coerceIn(0, bitmap.width - 1),
                it.top.coerceIn(0, bitmap.height - 1),
                it.right.coerceIn(1, bitmap.width),
                it.bottom.coerceIn(1, bitmap.height),
            )
        } ?: Rect(0, 0, bitmap.width, bitmap.height)
        val w = (box.width()).coerceAtLeast(1)
        val h = (box.height()).coerceAtLeast(1)
        val sampleW = w.coerceAtMost(64)
        val sampleH = h.coerceAtMost(64)
        val cropped = Bitmap.createBitmap(bitmap, box.left, box.top, w, h)
        val scaled = Bitmap.createScaledBitmap(cropped, sampleW, sampleH, true)
        if (cropped !== bitmap && cropped !== scaled) cropped.recycle()
        val pixels = IntArray(sampleW * sampleH)
        scaled.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
        if (scaled !== bitmap) scaled.recycle()
        var sum = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        val mean = (sum / pixels.size).toFloat()
        return Lighting(meanLuma = mean, tooDark = mean < 42f, tooBright = mean > 230f)
    }

    /**
     * Variance of a 4-neighbour Laplacian on a 64×64 grayscale crop.
     * Empirically, indoor classroom shots of a still face score ~80–400;
     * motion blur / out-of-focus often land below ~45.
     */
    fun sharpness(bitmap: Bitmap, region: Rect? = null): Float {
        val box = region?.let {
            Rect(
                it.left.coerceIn(0, bitmap.width - 1),
                it.top.coerceIn(0, bitmap.height - 1),
                it.right.coerceIn(1, bitmap.width),
                it.bottom.coerceIn(1, bitmap.height),
            )
        } ?: Rect(0, 0, bitmap.width, bitmap.height)
        val w0 = box.width().coerceAtLeast(1)
        val h0 = box.height().coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(bitmap, box.left, box.top, w0, h0)
        val w = 64
        val h = 64
        val scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
        if (cropped !== bitmap && cropped !== scaled) cropped.recycle()
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        val gray = FloatArray(w * h) { i ->
            val p = pixels[i]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = abs(-4 * gray[i] + gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w])
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        val mean = sum / n
        return ((sumSq / n) - mean * mean).toFloat()
    }

    const val MIN_SHARPNESS = 48f
}
