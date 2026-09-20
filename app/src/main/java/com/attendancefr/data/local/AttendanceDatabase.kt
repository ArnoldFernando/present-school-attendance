package com.attendancefr.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.attendancefr.data.local.dao.AttendanceDao
import com.attendancefr.data.local.dao.ClassSectionDao
import com.attendancefr.data.local.dao.FaceEmbeddingDao
import com.attendancefr.data.local.dao.StudentDao
import com.attendancefr.data.local.entity.AttendanceRecordEntity
import com.attendancefr.data.local.entity.ClassSectionEntity
import com.attendancefr.data.local.entity.FaceEmbeddingEntity
import com.attendancefr.data.local.entity.StudentClassCrossRef
import com.attendancefr.data.local.entity.StudentEntity

@Database(
    entities = [
        StudentEntity::class,
        FaceEmbeddingEntity::class,
        AttendanceRecordEntity::class,
        ClassSectionEntity::class,
        StudentClassCrossRef::class,
    ],
    version = 5,
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

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_classes` (
                `studentId` INTEGER NOT NULL,
                `className` TEXT NOT NULL,
                PRIMARY KEY(`studentId`, `className`),
                FOREIGN KEY(`studentId`) REFERENCES `students`(`id`) 
                ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_student_classes_className` ON `student_classes` (`className`)"
        )

        database.execSQL(
            """
            INSERT INTO student_classes (studentId, className) 
            SELECT id, className FROM students 
            WHERE className IS NOT NULL AND className != ''
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS `student_classes`")

        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_classes` (
                `studentId` INTEGER NOT NULL,
                `className` TEXT NOT NULL,
                PRIMARY KEY(`studentId`, `className`)
            )
            """.trimIndent()
        )

        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_student_classes_className` ON `student_classes` (`className`)"
        )

        database.execSQL(
            """
            INSERT INTO student_classes (studentId, className) 
            SELECT id, className FROM students 
            WHERE className IS NOT NULL AND className != ''
            """.trimIndent()
        )
    }
}


val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 1. Add the new className column (default empty string for existing records)
        database.execSQL("ALTER TABLE attendance_records ADD COLUMN className TEXT NOT NULL DEFAULT ''")

        // 2. Drop the old unique index on (studentId, date)
        database.execSQL("DROP INDEX IF EXISTS index_attendance_records_studentId_date")

        // 3. Create the new unique index on (studentId, date, className)
        database.execSQL(
            "CREATE UNIQUE INDEX index_attendance_records_studentId_date_className ON attendance_records(studentId, date, className)"
        )

        // 4. Add index on the new className column
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_attendance_records_className ON attendance_records(className)"
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 1. Delete old empty-className records that would conflict with existing non-empty ones
        database.execSQL("""
            DELETE FROM attendance_records 
            WHERE className = '' 
            AND EXISTS (
                SELECT 1 FROM attendance_records ar2
                WHERE ar2.studentId = attendance_records.studentId
                AND ar2.date = attendance_records.date
                AND ar2.className != ''
            )
        """.trimIndent())

        // 2. Backfill remaining empty className records with student's primary class
        database.execSQL("""
            UPDATE attendance_records 
            SET className = (
                SELECT className FROM students WHERE students.id = attendance_records.studentId
            )
            WHERE className = ''
        """.trimIndent())

        // 3. Delete any still-empty records (student was deleted, no primary class to use)
        database.execSQL("DELETE FROM attendance_records WHERE className = ''")
    }
}