package com.attendancefr.ml

import kotlin.math.sqrt

/**
 * Cosine similarity between a probe embedding and a gallery of enrolled
 * embeddings. Because embeddings are L2-normalized, cosine == dot product.
 *
 * Matching strategy: for each student, take the *maximum* similarity across
 * that student's stored embeddings (typically 3–5 enrollment shots). Using
 * max rather than mean is more robust to a single poor enrollment photo.
 */
class FaceMatcher {

    data class GalleryItem(
        val studentId: Long,
        val embedding: FloatArray,
    )

    data class RankedMatch(
        val studentId: Long,
        val similarity: Float,
    )

    fun rank(probe: FloatArray, gallery: List<GalleryItem>): List<RankedMatch> {
        if (gallery.isEmpty()) return emptyList()
        val best = HashMap<Long, Float>()
        for (item in gallery) {
            val sim = cosine(probe, item.embedding)
            val prev = best[item.studentId]
            if (prev == null || sim > prev) best[item.studentId] = sim
        }
        return best.entries
            .map { RankedMatch(it.key, it.value) }
            .sortedByDescending { it.similarity }
    }

    fun best(probe: FloatArray, gallery: List<GalleryItem>): RankedMatch? =
        rank(probe, gallery).firstOrNull()

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Float {
            val n = minOf(a.size, b.size)
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in 0 until n) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            val denom = sqrt(na) * sqrt(nb)
            return if (denom < 1e-12f) 0f else (dot / denom).coerceIn(-1f, 1f)
        }

        /**
         * Average several embeddings then re-normalize. Useful if you want a
         * single prototype per student instead of storing every shot.
         */
        fun average(vectors: List<FloatArray>): FloatArray {
            require(vectors.isNotEmpty())
            val dim = vectors.first().size
            val acc = FloatArray(dim)
            for (v in vectors) {
                for (i in 0 until dim) acc[i] += v[i]
            }
            val inv = 1f / vectors.size
            for (i in 0 until dim) acc[i] *= inv
            var sum = 0f
            for (x in acc) sum += x * x
            val norm = sqrt(sum).coerceAtLeast(1e-12f)
            for (i in 0 until dim) acc[i] /= norm
            return acc
        }
    }
}
