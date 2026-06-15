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
import java.time.temporal.IsoFields

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val fileBackupRepository: FileBackupRepository,
    private val prefs: UserPreferences,
    private val backupManager: BackupManager
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
        // v1.20.0: every run gets a unique timestamp suffix so we never need
        // to replace a prior good backup. Two runs the same day (e.g. retry
        // after a transient failure) end up as two distinct files; retention
        // keeps only the newest N. This removes the SAF rename race entirely.
        val runStamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))

        // Each class is independent: a write failure of the daily backup must
        // not block the weekly or skip retention of the others. Any failure
        // bubbles up to a retry so the user's last-good copy is preserved.
        var anyFailed = false

        if (prefs.getAutoBackupDailyEnabledImmediate()) {
            val prefix = "insitu-backup-daily-"
            val fileName = "$prefix$today-T$runStamp.$ext"
            if (writeBackup(folder, prefix, fileName, payload, mime)) {
                cleanupOldBackups(folder, prefix, prefs.getAutoBackupDailyRetentionImmediate())
            } else {
                anyFailed = true
            }
        }

        if (prefs.getAutoBackupWeeklyEnabledImmediate() && today.dayOfWeek == DayOfWeek.MONDAY) {
            val prefix = "insitu-backup-weekly-"
            val weekNumber = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val fileName = "$prefix${today.year}-W${weekNumber.toString().padStart(2, '0')}-T$runStamp.$ext"
            if (writeBackup(folder, prefix, fileName, payload, mime)) {
                cleanupOldBackups(folder, prefix, prefs.getAutoBackupWeeklyRetentionImmediate())
            } else {
                anyFailed = true
            }
        }

        if (prefs.getAutoBackupMonthlyEnabledImmediate() && today.dayOfMonth == 1) {
            val prefix = "insitu-backup-monthly-"
            val fileName = "$prefix${today.format(DateTimeFormatter.ofPattern("yyyy-MM"))}-T$runStamp.$ext"
            if (writeBackup(folder, prefix, fileName, payload, mime)) {
                cleanupOldBackups(folder, prefix, prefs.getAutoBackupMonthlyRetentionImmediate())
            } else {
                anyFailed = true
            }
        }

        return if (anyFailed) Result.retry() else Result.success()
    }

    // Write-then-rename via a per-run .tmp sibling. fileName is unique per
    // run (timestamp suffix) so we NEVER delete an existing target before
    // attempting the rename — the previous good backup is always preserved
    // even when SAF's renameTo fails. Stale .tmp orphans from prior failed
    // runs of the same backup class are swept up front so they don't pile up.
    // Returns true on success; the caller skips retention cleanup on false.
    private fun writeBackup(
        folder: DocumentFile,
        prefix: String,
        fileName: String,
        payload: ByteArray,
        mime: String
    ): Boolean {
        sweepTmpOrphans(folder, prefix)

        val tmpName = "$fileName.tmp"
        val tmpFile = try {
            folder.createFile(mime, tmpName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create temp file: $tmpName", e)
            return false
        } ?: run {
            Log.e(TAG, "Failed to create temp file: $tmpName")
            return false
        }

        val wrote = try {
            applicationContext.contentResolver.openOutputStream(tmpFile.uri).use { out ->
                if (out == null) {
                    Log.e(TAG, "Failed to open output stream for: $tmpName")
                    false
                } else {
                    out.write(payload)
                    out.flush()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write temp backup: $tmpName", e)
            false
        }

        if (!wrote) {
            try { tmpFile.delete() } catch (_: Exception) {}
            return false
        }

        return try {
            if (!tmpFile.renameTo(fileName)) {
                Log.e(TAG, "Failed to rename $tmpName -> $fileName (tmp left in place for sweep)")
                return false
            }
            Log.d(TAG, "Wrote backup: $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize backup: $fileName", e)
            false
        }
    }

    private fun sweepTmpOrphans(folder: DocumentFile, prefix: String) {
        try {
            folder.listFiles()
                .filter {
                    val name = it.name ?: return@filter false
                    name.startsWith(prefix) && name.endsWith(".tmp")
                }
                .forEach { tmp ->
                    Log.d(TAG, "Sweeping orphan tmp: ${tmp.name}")
                    try { tmp.delete() } catch (_: Exception) {}
                }
        } catch (_: Exception) {
            // Listing failure isn't fatal — the new write will still proceed.
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
