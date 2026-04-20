package com.insituledger.app.data.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.FileBackupRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val fileBackupRepository: FileBackupRepository,
    private val prefs: UserPreferences
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AutoBackupWorker"
    }

    override suspend fun doWork(): Result {
        val folderUriStr = prefs.getAutoBackupFolderUriImmediate()
            ?: return Result.success()

        val folderUri = Uri.parse(folderUriStr)
        val folder = DocumentFile.fromTreeUri(applicationContext, folderUri)
        if (folder == null || !folder.canWrite()) {
            Log.e(TAG, "Backup folder not accessible")
            return Result.failure()
        }

        val jsonResult = fileBackupRepository.generateBackupJson()
        val json = jsonResult.getOrElse {
            Log.e(TAG, "Failed to generate backup data", it)
            return Result.retry()
        }

        val passphrase = prefs.getBackupPassphrase()
        val plaintext = json.toByteArray(Charsets.UTF_8)
        val payload = if (!passphrase.isNullOrEmpty()) {
            com.insituledger.app.data.repository.BackupCrypto.encrypt(plaintext, passphrase.toCharArray())
        } else {
            plaintext
        }
        // .ilbk extension when encrypted to make it visible in file pickers
        // that the file isn't readable JSON; .json otherwise for back-compat.
        val ext = if (passphrase.isNullOrEmpty()) "json" else "ilbk"
        val mime = if (passphrase.isNullOrEmpty()) "application/json" else "application/octet-stream"

        val today = LocalDate.now()

        if (prefs.getAutoBackupDailyEnabledImmediate()) {
            val fileName = "insitu-backup-daily-$today.$ext"
            writeBackup(folder, fileName, payload, mime)
            cleanupOldBackups(folder, "insitu-backup-daily-", prefs.getAutoBackupDailyRetentionImmediate())
        }

        if (prefs.getAutoBackupWeeklyEnabledImmediate() && today.dayOfWeek == DayOfWeek.MONDAY) {
            val weekNumber = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val fileName = "insitu-backup-weekly-${today.year}-W${weekNumber.toString().padStart(2, '0')}.$ext"
            writeBackup(folder, fileName, payload, mime)
            cleanupOldBackups(folder, "insitu-backup-weekly-", prefs.getAutoBackupWeeklyRetentionImmediate())
        }

        if (prefs.getAutoBackupMonthlyEnabledImmediate() && today.dayOfMonth == 1) {
            val fileName = "insitu-backup-monthly-${today.format(DateTimeFormatter.ofPattern("yyyy-MM"))}.$ext"
            writeBackup(folder, fileName, payload, mime)
            cleanupOldBackups(folder, "insitu-backup-monthly-", prefs.getAutoBackupMonthlyRetentionImmediate())
        }

        return Result.success()
    }

    private fun writeBackup(folder: DocumentFile, fileName: String, payload: ByteArray, mime: String) {
        try {
            folder.findFile(fileName)?.delete()
            val file = folder.createFile(mime, fileName)
            if (file == null) {
                Log.e(TAG, "Failed to create file: $fileName")
                return
            }
            applicationContext.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(payload)
            }
            Log.d(TAG, "Wrote backup: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write backup: $fileName", e)
        }
    }

    private fun cleanupOldBackups(folder: DocumentFile, prefix: String, retention: Int) {
        try {
            val backups = folder.listFiles()
                .filter {
                    val name = it.name ?: return@filter false
                    name.startsWith(prefix) && (name.endsWith(".json") || name.endsWith(".ilbk"))
                }
                .sortedByDescending { it.name }

            if (backups.size > retention) {
                backups.drop(retention).forEach { file ->
                    Log.d(TAG, "Deleting old backup: ${file.name}")
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup backups for prefix: $prefix", e)
        }
    }
}
