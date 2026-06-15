package com.insituledger.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.SyncPushException
import com.insituledger.app.data.repository.SyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val prefs: UserPreferences
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (prefs.getSyncModeImmediate() != "webapp") return Result.success()
        return decideResult(syncRepository.sync(), runAttemptCount)
    }

    companion object {
        const val MAX_ATTEMPTS = 5

        // Pure decision function so the retry-classification logic can be
        // unit-tested without WorkManager / Context plumbing. Permanent
        // failures (validation 4xx, blocked-by-unsynced-CREATE) can never
        // succeed by retrying; bail immediately so WorkManager doesn't waste
        // battery on five rounds of the same 400. Transient failures (5xx,
        // network) get the existing MAX_ATTEMPTS cap.
        internal fun decideResult(
            syncResult: kotlin.Result<Unit>,
            runAttemptCount: Int
        ): Result {
            if (syncResult.isSuccess) return Result.success()
            val ex = syncResult.exceptionOrNull() as? SyncPushException
            val canRetry = ex?.canRetry ?: true // unknown error → assume retryable
            if (!canRetry) return Result.failure()
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}
