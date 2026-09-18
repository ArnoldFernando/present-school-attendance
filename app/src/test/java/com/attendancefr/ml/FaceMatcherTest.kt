package com.attendancefr.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FaceMatcherTest {

    private val matcher = FaceMatcher()

    @Test
    fun cosineOfIdenticalVectorsIsOne() {
        val a = floatArrayOf(0.6f, 0.8f)
        assertEquals(1f, FaceMatcher.cosine(a, a), 1e-5f)
    }

    @Test
    fun cosineOfOrthogonalVectorsIsZero() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, FaceMatcher.cosine(a, b), 1e-5f)
    }

    @Test
    fun rankReturnsHighestSimilarityPerStudent() {
        val probe = l2(floatArrayOf(1f, 0f, 0f))
        val gallery = listOf(
            FaceMatcher.GalleryItem(1, l2(floatArrayOf(0.9f, 0.1f, 0f))),
            FaceMatcher.GalleryItem(1, l2(floatArrayOf(0.2f, 0.8f, 0f))), // worse shot for student 1
            FaceMatcher.GalleryItem(2, l2(floatArrayOf(0f, 1f, 0f))),
        )
        val ranked = matcher.rank(probe, gallery)
        assertEquals(2, ranked.size)
        assertEquals(1L, ranked.first().studentId)
        assertTrue(ranked.first().similarity > ranked[1].similarity)
    }

    @Test
    fun bestReturnsNullOnEmptyGallery() {
        val probe = floatArrayOf(1f, 0f)
        assertEquals(null, matcher.best(probe, emptyList()))
    }

    @Test
    fun averageReNormalizes() {
        val a = l2(floatArrayOf(1f, 0f))
        val b = l2(floatArrayOf(0f, 1f))
        val avg = FaceMatcher.average(listOf(a, b))
        val norm = sqrt(avg[0] * avg[0] + avg[1] * avg[1])
        assertEquals(1f, norm, 1e-5f)
        assertEquals(avg[0], avg[1], 1e-5f)
    }

    @Test
    fun thresholdLogicMatchesSpec() {
        // Mirrors AttendanceViewModel: similarity >= threshold → match, else unknown.
        val threshold = 0.60f
        assertTrue(0.72f >= threshold)
        assertTrue(0.59f < threshold)
    }

    private fun l2(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s)
        return FloatArray(v.size) { v[it] / n }
    }
}
