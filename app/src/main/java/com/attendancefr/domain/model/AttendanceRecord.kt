package com.attendancefr.domain.model

data class AttendanceRecord(
    val id: Long,
    val studentId: Long,
    val studentName: String,
    val studentRoll: String,
    val className: String,
    val date: String,
    val timestamp: Long,
    val status: AttendanceStatus,
    val matchConfidence: Float?,
    val isManual: Boolean,
)
