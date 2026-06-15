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
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val fileBackupRepository: FileBackupRepository,
    private val prefs: UserPreferences,
    private val backupManager: BackupManager,
    private val fileWriter: AutoBackupFileWriter
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AutoBackupWorker"
    }

    override suspend fun doWork(): Result {
        try {
            return runBackup()
        } finally {
            // Always chain the next run, even on failure, so DST drifts can't
            // accumulate and a transient failure doesn't kill the schedule.
            backupManager.rescheduleAfterRun()
        }
    }

    private suspend fun runBackup(): Result {
        val attemptAt = System.currentTimeMillis()
        prefs.saveAutoBackupLastAttemptAt(attemptAt)
        val folderUriStr = prefs.getAutoBackupFolderUriImmediate()
            ?: return Result.success()

        val folderUri = Uri.parse(folderUriStr)
        val folder = DocumentFile.fromTreeUri(applicationContext, folderUri)
        if (folder == null || !folder.canWrite()) {
            Log.e(TAG, "Backup folder not accessible")
            return Result.failure()
        }
        val backupFolder = DocumentBackupFolder(applicationContext, folder)

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
        // v1.20.0: every run gets a unique timestamp suffix so we never need
        // to replace a prior good backup. Two runs the same day (e.g. retry
        // after a transient failure) end up as two distinct files; retention
        // keeps only the newest N. This removes the SAF rename race entirely.
        val runStamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))

        // Each class is independent: a write failure of the daily backup must
        // not block the weekly or skip retention of the others. Any failure
        // bubbles up to a retry so the user's last-good copy is preserved.
        var anyFailed = false
        var anyWritten = false

        if (prefs.getAutoBackupDailyEnabledImmediate()) {
            val prefix = "insitu-backup-daily-"
            val fileName = "$prefix$today-T$runStamp.$ext"
            if (fileWriter.writeAndCleanup(
                    backupFolder, prefix, fileName, payload, mime,
                    prefs.getAutoBackupDailyRetentionImmediate()
                )) {
                anyWritten = true
            } else {
                anyFailed = true
            }
        }

        if (prefs.getAutoBackupWeeklyEnabledImmediate() && today.dayOfWeek == DayOfWeek.MONDAY) {
            val prefix = "insitu-backup-weekly-"
            val fileName = AutoBackupNaming.weeklyFileName(today, runStamp, ext)
            if (fileWriter.writeAndCleanup(
                    backupFolder, prefix, fileName, payload, mime,
                    prefs.getAutoBackupWeeklyRetentionImmediate()
                )) {
                anyWritten = true
            } else {
                anyFailed = true
            }
        }

        if (prefs.getAutoBackupMonthlyEnabledImmediate() && today.dayOfMonth == 1) {
            val prefix = "insitu-backup-monthly-"
            val fileName = "$prefix${today.format(DateTimeFormatter.ofPattern("yyyy-MM"))}-T$runStamp.$ext"
            if (fileWriter.writeAndCleanup(
                    backupFolder, prefix, fileName, payload, mime,
                    prefs.getAutoBackupMonthlyRetentionImmediate()
                )) {
                anyWritten = true
            } else {
                anyFailed = true
            }
        }

        if (anyWritten) prefs.saveAutoBackupLastSuccessfulAt(System.currentTimeMillis())
        return if (anyFailed) Result.retry() else Result.success()
    }
}
