package com.attendancefr.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "student_classes",
    primaryKeys = ["studentId", "className"],
    indices = [Index(value = ["className"])]
)
data class StudentClassCrossRef(
    val studentId: Long,
    val className: String
)