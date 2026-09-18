package com.attendancefr.domain.model

data class Student(
    val id: Long,
    val studentId: String,
    val name: String,
    val className: String,
    val dateEnrolled: Long,
    val embeddingCount: Int = 0,
)
