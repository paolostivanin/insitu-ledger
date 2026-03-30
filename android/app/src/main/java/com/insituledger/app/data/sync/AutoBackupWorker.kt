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

        val today = LocalDate.now()

        if (prefs.getAutoBackupDailyEnabledImmediate()) {
            val fileName = "insitu-backup-daily-$today.json"
            writeBackup(folder, fileName, json)
            cleanupOldBackups(folder, "insitu-backup-daily-", prefs.getAutoBackupDailyRetentionImmediate())
        }

        if (prefs.getAutoBackupWeeklyEnabledImmediate() && today.dayOfWeek == DayOfWeek.MONDAY) {
            val weekNumber = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val fileName = "insitu-backup-weekly-${today.year}-W${weekNumber.toString().padStart(2, '0')}.json"
            writeBackup(folder, fileName, json)
            cleanupOldBackups(folder, "insitu-backup-weekly-", prefs.getAutoBackupWeeklyRetentionImmediate())
        }

        if (prefs.getAutoBackupMonthlyEnabledImmediate() && today.dayOfMonth == 1) {
            val fileName = "insitu-backup-monthly-${today.format(DateTimeFormatter.ofPattern("yyyy-MM"))}.json"
            writeBackup(folder, fileName, json)
            cleanupOldBackups(folder, "insitu-backup-monthly-", prefs.getAutoBackupMonthlyRetentionImmediate())
        }

        return Result.success()
    }

    private fun writeBackup(folder: DocumentFile, fileName: String, json: String) {
        try {
            folder.findFile(fileName)?.delete()
            val file = folder.createFile("application/json", fileName)
            if (file == null) {
                Log.e(TAG, "Failed to create file: $fileName")
                return
            }
            applicationContext.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            }
            Log.d(TAG, "Wrote backup: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write backup: $fileName", e)
        }
    }

    private fun cleanupOldBackups(folder: DocumentFile, prefix: String, retention: Int) {
        try {
            val backups = folder.listFiles()
                .filter { it.name?.startsWith(prefix) == true && it.name?.endsWith(".json") == true }
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
