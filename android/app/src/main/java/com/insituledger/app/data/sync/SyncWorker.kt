package com.insituledger.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.insituledger.app.data.local.datastore.UserPreferences
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
        val result = syncRepository.sync()
        if (result.isSuccess) return Result.success()
        // Cap retry attempts. WorkManager's exponential backoff would otherwise
        // keep this worker around forever on a misconfigured server, draining
        // battery and clogging the unique-work slot.
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}
