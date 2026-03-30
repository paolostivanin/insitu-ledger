package com.insituledger.app.data.sync

import androidx.work.*
import com.insituledger.app.data.local.datastore.UserPreferences
import java.util.Calendar
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

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 3)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val delayMillis = target.timeInMillis - now.timeInMillis

        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        workManager.get().enqueueUniquePeriodicWork(
            AUTO_BACKUP_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelAutoBackup() {
        workManager.get().cancelUniqueWork(AUTO_BACKUP_WORK)
    }
}
