package com.attendancefr.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.attendancefr.data.local.dao.AttendanceDao
import com.attendancefr.data.local.dao.ClassSectionDao
import com.attendancefr.data.local.dao.FaceEmbeddingDao
import com.attendancefr.data.local.dao.StudentDao
import com.attendancefr.data.local.entity.AttendanceRecordEntity
import com.attendancefr.data.local.entity.ClassSectionEntity
import com.attendancefr.data.local.entity.FaceEmbeddingEntity
import com.attendancefr.data.local.entity.StudentEntity

@Database(
    entities = [
        StudentEntity::class,
        FaceEmbeddingEntity::class,
        AttendanceRecordEntity::class,
        ClassSectionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AttendanceDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun faceEmbeddingDao(): FaceEmbeddingDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun classSectionDao(): ClassSectionDao

    companion object {
        const val NAME = "attendance_fr.db"
    }
}
