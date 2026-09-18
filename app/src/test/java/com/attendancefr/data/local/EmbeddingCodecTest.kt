package com.attendancefr.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingCodecTest {
    @Test
    fun roundTripPreservesValues() {
        val original = floatArrayOf(-1f, 0f, 0.5f, 1.25f, 3.14159f)
        val bytes = EmbeddingCodec.toBytes(original)
        assertEquals(original.size * 4, bytes.size)
        val restored = EmbeddingCodec.toFloats(bytes)
        assertArrayEquals(original, restored, 1e-6f)
    }

    @Test
    fun emptyVectorRoundTrips() {
        val bytes = EmbeddingCodec.toBytes(floatArrayOf())
        assertEquals(0, bytes.size)
        assertEquals(0, EmbeddingCodec.toFloats(bytes).size)
    }
}
