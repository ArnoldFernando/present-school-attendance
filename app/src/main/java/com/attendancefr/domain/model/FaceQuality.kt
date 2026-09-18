package com.attendancefr.domain.model

data class FaceQuality(
    val accepted: Boolean,
    val reason: String? = null,
    val eyesOpen: Boolean = true,
    val headYaw: Float = 0f,
    val headPitch: Float = 0f,
    val headRoll: Float = 0f,
    val lightingHint: Boolean = false,
)
