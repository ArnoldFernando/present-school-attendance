package com.attendancefr.data.local

import java.nio.ByteBuffer
import java.nio.ByteOrder

object EmbeddingCodec {
    fun toBytes(vector: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun toFloats(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        buf.asFloatBuffer().get(out)
        return out
    }
}
