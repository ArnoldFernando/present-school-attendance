package com.attendancefr.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "students",
    indices = [
        Index(value = ["studentId"], unique = true),
        Index(value = ["className"]),
    ],
)
data class StudentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: String,
    val name: String,
    val className: String,
    val dateEnrolled: Long,
)
