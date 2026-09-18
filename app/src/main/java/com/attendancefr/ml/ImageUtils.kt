package com.attendancefr.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object ImageUtils {

    /**
     * Convert an ImageProxy (YUV_420_888) to an ARGB_8888 Bitmap, applying
     * the sensor rotation so ML Kit / TFLite see an upright image.
     *
     * Reliable path: YUV → NV21 → JPEG → Bitmap → rotate. On a 640×480
     * mid-range frame this is well under 30 ms.
     */
    fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val nv21 = yuv420888ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val bytes = out.toByteArray()
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + ySize / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        var output = 0
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            output = ySize
        } else {
            val yRow = ByteArray(yRowStride)
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(yRow, 0, minOf(yRowStride, yBuffer.remaining()))
                System.arraycopy(yRow, 0, nv21, output, width)
                output += width
            }
        }

        val vBuffer = vPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride
        val vRow = ByteArray(vRowStride)
        val uRow = ByteArray(uRowStride)
        for (row in 0 until height / 2) {
            vBuffer.position(row * vRowStride)
            uBuffer.position(row * uRowStride)
            vBuffer.get(vRow, 0, minOf(vRowStride, vBuffer.remaining()))
            uBuffer.get(uRow, 0, minOf(uRowStride, uBuffer.remaining()))
            var col = 0
            while (col < width / 2) {
                nv21[output++] = vRow[col * vPixelStride]
                nv21[output++] = uRow[col * uPixelStride]
                col++
            }
        }
        return nv21
    }

    fun downscaleIfNeeded(src: Bitmap, maxSide: Int = 720): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
