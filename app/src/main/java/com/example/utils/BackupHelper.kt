package com.example.utils

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import com.example.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupHelper {
    private const val DB_NAME = "electricity_billing_db"

    /** دمج معاملات WAL داخل ملف SQLite قبل نسخه حتى تكون النسخة مكتملة بذاتها. */
    private suspend fun checkpointDatabase(context: Context) {
        runCatching {
            val database = AppDatabase.getDatabase(context)
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { }
        }.onFailure { it.printStackTrace() }
    }

    suspend fun backupDatabase(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            checkpointDatabase(context)
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
            checkpointDatabase(context)
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
            checkpointDatabase(context)
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
        val tempDir = File(context.cacheDir, "db_restore_temp")
        try {
            if (tempDir.exists()) tempDir.deleteRecursively()
            check(tempDir.mkdirs() || tempDir.isDirectory) { "تعذر إنشاء مجلد مؤقت للاستعادة" }

            // استخراج ملف قاعدة البيانات فقط؛ لا نستعيد ملفات WAL/SHM القديمة.
            var extracted = false
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name == DB_NAME) {
                            val outFile = File(tempDir, DB_NAME)
                            FileOutputStream(outFile).use { output -> zis.copyTo(output) }
                            extracted = true
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: return@withContext false

            val stagedDb = File(tempDir, DB_NAME)
            if (!extracted || !stagedDb.isFile || stagedDb.length() <= 0L) {
                tempDir.deleteRecursively()
                return@withContext false
            }

            // فحص سلامة SQLite ورقم إصدار المخطط قبل لمس قاعدة البيانات الحالية.
            val validationDb = SQLiteDatabase.openDatabase(
                stagedDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            val isValid = try {
                val cursor = validationDb.rawQuery("PRAGMA quick_check", null)
                val result = cursor.use { if (it.moveToFirst()) it.getString(0) else "" }
                result == "ok" && validationDb.version in 4..14
            } finally {
                validationDb.close()
            }
            if (!isValid) {
                tempDir.deleteRecursively()
                return@withContext false
            }

            val dbFile = context.getDatabasePath(DB_NAME)
            val parent = dbFile.parentFile ?: error("مسار قاعدة البيانات غير صالح")
            check(parent.exists() || parent.mkdirs()) { "تعذر الوصول إلى مجلد قاعدة البيانات" }
            val replacement = File(parent, "$DB_NAME.restore")
            stagedDb.copyTo(replacement, overwrite = true)

            // إغلاق Room أولاً، ثم حذف ملفات WAL/SHM لمنع تعارضها مع النسخة المستعادة.
            AppDatabase.closeForRestore()
            File(parent, "$DB_NAME-wal").delete()
            File(parent, "$DB_NAME-shm").delete()
            if (dbFile.exists() && !dbFile.delete()) error("تعذر إغلاق ملف قاعدة البيانات القديم")
            if (!replacement.renameTo(dbFile)) {
                replacement.copyTo(dbFile, overwrite = true)
                replacement.delete()
            }

            tempDir.deleteRecursively()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            tempDir.deleteRecursively()
            false
        }
    }
}
