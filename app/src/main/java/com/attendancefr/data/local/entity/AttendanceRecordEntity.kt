package com.attendancefr.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attendance_records",
    foreignKeys = [
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["studentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["studentId", "date", "className"], unique = true),
        Index(value = ["date"]),
        Index(value = ["studentId"]),
        Index(value = ["className"]),
    ],
)
data class AttendanceRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    val date: String,
    val className: String,
    val timestamp: Long,
    val status: String,
    val matchConfidence: Float?,
    val isManual: Boolean,
)