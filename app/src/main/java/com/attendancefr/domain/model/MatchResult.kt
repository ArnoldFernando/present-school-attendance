package com.attendancefr.domain.model

sealed class MatchResult {
    data class Matched(
        val student: Student,
        val confidence: Float,
        val alreadyMarked: Boolean,
    ) : MatchResult()

    data class Unknown(
        val confidence: Float,
        val closestStudent: Student?,
    ) : MatchResult()

    data object NoFace : MatchResult()
    data class MultipleFaces(val count: Int) : MatchResult()
    data class PoorQuality(val reason: String) : MatchResult()
    data class Error(val message: String) : MatchResult()
}
