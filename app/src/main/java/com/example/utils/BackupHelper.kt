package com.example.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import android.database.sqlite.SQLiteDatabase
import com.example.data.database.AppDatabase

object BackupHelper {
    private const val DB_NAME = "electricity_billing_db"

    suspend fun backupDatabase(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val dbFile = context.getDatabasePath(DB_NAME)
            val walFile = File(dbFile.parent, "$DB_NAME-wal")
            val shmFile = File(dbFile.parent, "$DB_NAME-shm")

            context.contentResolver.openOutputStream(uri)?.use { os ->
                ZipOutputStream(os).use { zos ->
                    val filesToBackup = listOf(dbFile, walFile, shmFile)
                    for (file in filesToBackup) {
                        if (file.exists()) {
                            val entry = ZipEntry(file.name)
                            zos.putNextEntry(entry)
                            FileInputStream(file).use { fis ->
                                fis.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun createBackupFile(context: Context, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            targetFile.parentFile?.mkdirs()
            val dbFile = context.getDatabasePath(DB_NAME)
            val walFile = File(dbFile.parent, "$DB_NAME-wal")
            val shmFile = File(dbFile.parent, "$DB_NAME-shm")
            FileOutputStream(targetFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    listOf(dbFile, walFile, shmFile).forEach { file ->
                        if (file.exists()) {
                            zos.putNextEntry(ZipEntry(file.name))
                            FileInputStream(file).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun createBackupFileForSharing(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val dbFile = context.getDatabasePath(DB_NAME)
            val walFile = File(dbFile.parent, "$DB_NAME-wal")
            val shmFile = File(dbFile.parent, "$DB_NAME-shm")

            val backupFile = File(context.cacheDir, "electricity_billing_backup_${System.currentTimeMillis()}.zip")
            FileOutputStream(backupFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val filesToBackup = listOf(dbFile, walFile, shmFile)
                    for (file in filesToBackup) {
                        if (file.exists()) {
                            val entry = ZipEntry(file.name)
                            zos.putNextEntry(entry)
                            FileInputStream(file).use { fis ->
                                fis.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }
            backupFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun restoreDatabase(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "db_restore_${System.currentTimeMillis()}")
        val rollbackDir = File(tempDir, "rollback")
        var roomClosed = false

        try {
            tempDir.mkdirs()
            val allowedNames = setOf(DB_NAME, "$DB_NAME-wal", "$DB_NAME-shm")
            val extractedNames = mutableSetOf<String>()

            // Extract only the three expected SQLite files; reject paths and duplicate entries.
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext false
            input.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name
                            if (name !in allowedNames || name.contains("/") || name.contains("\\\\") ||
                                !extractedNames.add(name)
                            ) {
                                throw IllegalArgumentException("ملف النسخة الاحتياطية غير صالح")
                            }
                            val outFile = File(tempDir, name)
                            FileOutputStream(outFile).use { output ->
                                val buffer = ByteArray(8192)
                                var total = 0L
                                while (true) {
                                    val count = zis.read(buffer)
                                    if (count <= 0) break
                                    total += count
                                    if (total > 512L * 1024L * 1024L) {
                                        throw IllegalArgumentException("حجم ملف قاعدة البيانات أكبر من المسموح")
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            val stagedDb = File(tempDir, DB_NAME)
            if (!stagedDb.exists() || stagedDb.length() <= 0L) {
                throw IllegalArgumentException("النسخة الاحتياطية لا تحتوي على قاعدة بيانات")
            }
            validateDatabase(stagedDb)

            val dbFile = context.getDatabasePath(DB_NAME)
            val walFile = File(dbFile.parent, "$DB_NAME-wal")
            val shmFile = File(dbFile.parent, "$DB_NAME-shm")
            rollbackDir.mkdirs()

            // Stop Room before touching the active SQLite files.
            AppDatabase.closeDatabaseForRestore()
            roomClosed = true

            listOf(dbFile, walFile, shmFile).forEach { current ->
                if (current.exists()) current.copyTo(File(rollbackDir, current.name), overwrite = true)
            }

            try {
                dbFile.delete()
                walFile.delete()
                shmFile.delete()

                stagedDb.copyTo(dbFile, overwrite = true)
                val stagedWal = File(tempDir, "$DB_NAME-wal")
                if (stagedWal.exists()) stagedWal.copyTo(walFile, overwrite = true)

                // Opening SQLite after the copy recovers WAL and verifies the installed database.
                validateDatabase(dbFile)
                true
            } catch (restoreError: Exception) {
                // Roll back to the pre-restore database if any step fails.
                dbFile.delete()
                walFile.delete()
                shmFile.delete()
                listOf(DB_NAME, "$DB_NAME-wal", "$DB_NAME-shm").forEach { name ->
                    val saved = File(rollbackDir, name)
                    if (saved.exists()) saved.copyTo(File(dbFile.parent, name), overwrite = true)
                }
                throw restoreError
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            tempDir.deleteRecursively()
            // Keep the database closed; the UI already asks the user to restart the app.
            // This prevents stale Room references from continuing against replaced files.
            if (!roomClosed) {
                // No active database instance was replaced.
            }
        }
    }

    private fun validateDatabase(file: File) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            val version = database.version
            if (version < 4 || version > 14) {
                throw IllegalArgumentException("إصدار قاعدة البيانات غير متوافق مع هذا التطبيق")
            }
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                    throw IllegalArgumentException("قاعدة البيانات داخل النسخة الاحتياطية تالفة")
                }
            }
        } finally {
            database.close()
        }
    }
}
