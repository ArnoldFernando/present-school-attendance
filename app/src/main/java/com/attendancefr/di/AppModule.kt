package com.attendancefr.di

import android.content.Context
import androidx.room.Room
import com.attendancefr.data.local.AttendanceDatabase
import com.attendancefr.data.local.MIGRATION_1_2
import com.attendancefr.data.local.MIGRATION_2_3
import com.attendancefr.data.local.MIGRATION_3_4
import com.attendancefr.data.local.MIGRATION_4_5
import com.attendancefr.data.local.MIGRATION_5_6
import com.attendancefr.data.local.dao.AttendanceDao
import com.attendancefr.data.local.dao.ClassSectionDao
import com.attendancefr.data.local.dao.FaceEmbeddingDao
import com.attendancefr.data.local.dao.StudentDao
import com.attendancefr.ml.FaceDetectorHelper
import com.attendancefr.ml.FaceEmbeddingEngine
import com.attendancefr.ml.FaceMatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AttendanceDatabase =
        Room.databaseBuilder(context, AttendanceDatabase::class.java, AttendanceDatabase.NAME)
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()

    @Provides
    fun provideStudentDao(db: AttendanceDatabase): StudentDao = db.studentDao()

    @Provides
    fun provideFaceEmbeddingDao(db: AttendanceDatabase): FaceEmbeddingDao = db.faceEmbeddingDao()

    @Provides
    fun provideAttendanceDao(db: AttendanceDatabase): AttendanceDao = db.attendanceDao()

    @Provides
    fun provideClassSectionDao(db: AttendanceDatabase): ClassSectionDao = db.classSectionDao()

    @Provides
    @Singleton
    fun provideFaceDetectorHelper(): FaceDetectorHelper = FaceDetectorHelper()

    @Provides
    @Singleton
    fun provideFaceEmbeddingEngine(@ApplicationContext context: Context): FaceEmbeddingEngine =
        FaceEmbeddingEngine(context)

    @Provides
    @Singleton
    fun provideFaceMatcher(): FaceMatcher = FaceMatcher()
}

