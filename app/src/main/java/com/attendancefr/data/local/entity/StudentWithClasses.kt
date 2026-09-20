package com.attendancefr.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.attendancefr.data.local.entity.StudentClassCrossRef
import com.attendancefr.data.local.entity.StudentEntity

data class StudentWithClasses(
    @Embedded val student: StudentEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "studentId"
    )
    val classes: List<StudentClassCrossRef>
)