package com.insituledger.app.data.sync

import androidx.work.*
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.SyncRepository
import com.insituledger.app.util.DateTimeUtil
import java.time.Clock
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
        // Separate from ONE_TIME_SYNC_WORK so REPLACE here can never cancel a
        // push queued by a repository write (and vice versa).
        const val DELAYED_SYNC_WORK = "delayed_sync"

        /** How long the server scheduler gets to materialize a due schedule before we pull. */
        const val SCHEDULER_PULL_DELAY_MS = 90_000L
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

    /**
     * A sync that runs after [delayMillis], e.g. to pull entries the server
     * scheduler is about to create. Webapp mode only; re-scheduling replaces
     * the pending one.
     */
    fun scheduleDelayedSync(delayMillis: Long) {
        if (prefs.getSyncModeImmediate() != "webapp") return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.get().enqueueUniqueWork(
            DELAYED_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Wake whichever scheduler owns a just-saved schedule whose first
     * occurrence is [nextOccurrence].
     *
     * The delay is wall-clock based (the value's own wall time read in the
     * device zone), matching the worker's SUBSTR(next_occurrence, 1, 16) due
     * check. Due now (including past starts): run the local worker right away
     * and, in webapp mode, pull after the server has had time to materialize.
     * Due later: a local check at that moment. Each call no-ops in the sync
     * mode it doesn't apply to.
     */
    fun kickScheduler(nextOccurrence: String, clock: Clock) {
        val target = DateTimeUtil.parseFormDateOrNull(nextOccurrence, clock.zone)
        val delayMs = target?.let {
            it.toLocalDateTime().atZone(clock.zone).toInstant().toEpochMilli() - clock.millis()
        } ?: 0L
        if (delayMs <= 0) {
            triggerImmediateScheduledCheck()
            scheduleDelayedSync(SCHEDULER_PULL_DELAY_MS)
        } else {
            scheduleDelayedScheduledCheck(delayMs)
        }
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
        workManager.get().cancelUniqueWork(DELAYED_SYNC_WORK)
        workManager.get().cancelUniqueWork(SCHEDULED_TX_WORK)
    }
}
