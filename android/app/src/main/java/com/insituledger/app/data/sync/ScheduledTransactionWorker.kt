package com.insituledger.app.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock

@HiltWorker
class ScheduledTransactionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val database: AppDatabase,
    private val scheduledDao: ScheduledTransactionDao,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val prefs: UserPreferences,
    private val clock: Clock
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ScheduledTxWorker"
    }

    override suspend fun doWork(): Result {
        // In webapp sync mode the backend is the authoritative materializer.
        // Belt-and-suspenders against a worker enqueued before the SyncManager
        // gate landed, or one already running when the user switched modes.
        if (prefs.getSyncModeImmediate() == "webapp") return Result.success()

        ScheduledMaterializer(
            database, scheduledDao, transactionDao, accountDao, clock,
            log = { message, error ->
                if (error != null) Log.w(TAG, message, error) else Log.d(TAG, message)
            }
        ).run()

        return Result.success()
    }
}
