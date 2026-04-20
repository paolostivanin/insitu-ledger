package com.insituledger.app.data.sync

import androidx.work.*
import com.insituledger.app.data.local.datastore.UserPreferences
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    private val workManager: dagger.Lazy<WorkManager>,
    private val prefs: UserPreferences
) {
    companion object {
        const val AUTO_BACKUP_WORK = "auto_backup"
        // 3:00 AM local time. Recomputed before each enqueue so DST transitions
        // can't drift the wall-clock fire time across the year.
        private val FIRE_AT: LocalTime = LocalTime.of(3, 0)
    }

    fun scheduleAutoBackup() {
        val folderUri = prefs.getAutoBackupFolderUriImmediate()
        val anyEnabled = prefs.getAutoBackupDailyEnabledImmediate()
                || prefs.getAutoBackupWeeklyEnabledImmediate()
                || prefs.getAutoBackupMonthlyEnabledImmediate()

        if (folderUri == null || !anyEnabled) {
            cancelAutoBackup()
            return
        }

        enqueueNext(ExistingWorkPolicy.REPLACE)
    }

    // Called by AutoBackupWorker after a run to chain the next one. Uses
    // APPEND_OR_REPLACE so the enqueue doesn't cancel the worker that is
    // currently invoking us.
    fun rescheduleAfterRun() {
        enqueueNext(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueueNext(policy: ExistingWorkPolicy) {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var target = LocalDateTime.of(LocalDate.now(zone), FIRE_AT)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val delayMs = target.atZone(zone).toInstant().toEpochMilli() -
                now.atZone(zone).toInstant().toEpochMilli()

        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        workManager.get().enqueueUniqueWork(AUTO_BACKUP_WORK, policy, request)
    }

    fun cancelAutoBackup() {
        workManager.get().cancelUniqueWork(AUTO_BACKUP_WORK)
    }
}
