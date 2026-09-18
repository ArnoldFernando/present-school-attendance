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
        Index(value = ["studentId", "date"], unique = true),
        Index(value = ["date"]),
        Index(value = ["studentId"]),
    ],
)
data class AttendanceRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    /** ISO-8601 local date, e.g. "2025-04-12". */
    val date: String,
    val timestamp: Long,
    /** AttendanceStatus.name */
    val status: String,
    val matchConfidence: Float?,
    val isManual: Boolean,
)
