package com.attendancefr.data.backup

import android.content.Context
import com.attendancefr.data.local.AttendanceDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AttendanceDatabase,
) {
    fun backupsDir(): File = File(context.filesDir, "backups").apply { mkdirs() }

    fun createBackup(): File {
        checkpoint()

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outFile = File(backupsDir(), "attendancefr-$stamp.afrbak")

        val dbFile = context.getDatabasePath(AttendanceDatabase.NAME)
        val wal = File(dbFile.path + "-wal")
        val shm = File(dbFile.path + "-shm")

        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            listOf(dbFile, wal, shm).filter { it.exists() }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return outFile
    }

    /**
     * Restores from either:
     * 1. A .afrbak ZIP file (the proper backup format)
     * 2. A raw .db file (user picked the extracted database)
     * 3. A .afrbak.zip file (some apps rename shared backups)
     */
    fun restore(backupFile: File) {
        require(backupFile.exists()) { "Backup file not found." }

        val isZip = backupFile.name.endsWith(".afrbak")
                || backupFile.name.endsWith(".afrbak.zip")
                || backupFile.name.endsWith(".zip")

        checkpoint()
        database.close()

        val dbFile = context.getDatabasePath(AttendanceDatabase.NAME)
        val wal = File(dbFile.path + "-wal")
        val shm = File(dbFile.path + "-shm")

        wal.delete()
        shm.delete()

        if (isZip) {
            // Restore from ZIP
            ZipInputStream(FileInputStream(backupFile)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = when {
                        entry.name.endsWith("-wal") -> wal
                        entry.name.endsWith("-shm") -> shm
                        else -> dbFile
                    }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } else {
            // User picked a raw .db file - copy it directly
            backupFile.inputStream().use { input ->
                dbFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        // Ensure files exist
        if (!dbFile.exists()) {
            throw IllegalStateException("Restore failed: database file was not created.")
        }

        throw NeedsRestart()
    }

    private fun checkpoint() {
        try {
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        } catch (_: Exception) {
            // Database may already be closed.
        }
    }

    class NeedsRestart : RuntimeException("Database restored - process restart required.")
}
