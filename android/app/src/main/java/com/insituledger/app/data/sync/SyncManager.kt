package com.insituledger.app.data.sync

import androidx.work.*
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.SyncRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    private val workManager: dagger.Lazy<WorkManager>,
    private val syncRepository: SyncRepository,
    private val prefs: UserPreferences
) {
    companion object {
        const val PERIODIC_SYNC_WORK = "periodic_sync"
        const val ONE_TIME_SYNC_WORK = "one_time_sync"
        const val SCHEDULED_TX_WORK = "scheduled_tx_check"
    }

    fun schedulePeriodicSync() {
        if (prefs.getSyncModeImmediate() != "webapp") return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        workManager.get().enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun triggerImmediateSync() {
        if (prefs.getSyncModeImmediate() != "webapp") return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.get().enqueueUniqueWork(
            ONE_TIME_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    suspend fun syncNow(): Result<Unit> {
        if (prefs.getSyncModeImmediate() != "webapp") {
            return Result.success(Unit)
        }
        return syncRepository.sync()
    }

    fun scheduleScheduledTransactionCheck() {
        if (prefs.getSyncModeImmediate() == "webapp") {
            // Backend is the authoritative materializer when synced — running the
            // local worker too produces duplicate transactions for each occurrence.
            workManager.get().cancelUniqueWork(SCHEDULED_TX_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<ScheduledTransactionWorker>(15, TimeUnit.MINUTES)
            .build()

        workManager.get().enqueueUniquePeriodicWork(
            SCHEDULED_TX_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun triggerImmediateScheduledCheck() {
        if (prefs.getSyncModeImmediate() == "webapp") return
        val request = OneTimeWorkRequestBuilder<ScheduledTransactionWorker>().build()
        workManager.get().enqueue(request)
    }

    fun scheduleDelayedScheduledCheck(delayMillis: Long) {
        if (prefs.getSyncModeImmediate() == "webapp") return
        val request = OneTimeWorkRequestBuilder<ScheduledTransactionWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        workManager.get().enqueue(request)
    }

    fun cancelAll() {
        workManager.get().cancelUniqueWork(PERIODIC_SYNC_WORK)
        workManager.get().cancelUniqueWork(ONE_TIME_SYNC_WORK)
        workManager.get().cancelUniqueWork(SCHEDULED_TX_WORK)
    }
}
