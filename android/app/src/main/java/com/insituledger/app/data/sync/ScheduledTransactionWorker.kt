package com.insituledger.app.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.TransactionEntity
import com.insituledger.app.util.DateTimeUtil
import com.insituledger.app.util.Rrule
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDateTime

@HiltWorker
class ScheduledTransactionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val database: AppDatabase,
    private val scheduledDao: ScheduledTransactionDao,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val prefs: UserPreferences
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ScheduledTxWorker"
    }

    override suspend fun doWork(): Result {
        // In webapp sync mode the backend is the authoritative materializer.
        // Belt-and-suspenders against a worker enqueued before the SyncManager
        // gate landed, or one already running when the user switched modes.
        if (prefs.getSyncModeImmediate() == "webapp") return Result.success()

        val now = LocalDateTime.now()
        val nowStr = DateTimeUtil.formatLocalDateTime(now)

        val due = scheduledDao.getDue(nowStr)
        if (due.isEmpty()) return Result.success()

        Log.d(TAG, "Found ${due.size} due scheduled transaction(s)")

        for (scheduled in due) {
            val txDate = scheduled.nextOccurrence
            val (next, pastUntil) = Rrule.advanceDate(scheduled.nextOccurrence, scheduled.rrule)
            val newCount = scheduled.occurrenceCount + 1
            val deactivate = pastUntil ||
                (scheduled.maxOccurrences != null && newCount >= scheduled.maxOccurrences)

            database.withTransaction {
                // Generate a local negative ID for the new transaction
                val minId = transactionDao.getMinId() ?: 0
                val localId = if (minId >= 0) -1 else minId - 1

                val transaction = TransactionEntity(
                    id = localId,
                    accountId = scheduled.accountId,
                    categoryId = scheduled.categoryId,
                    userId = scheduled.userId,
                    type = scheduled.type,
                    amount = scheduled.amount,
                    currency = scheduled.currency,
                    description = scheduled.description,
                    note = scheduled.note,
                    date = txDate,
                    isLocalOnly = true,
                    createdByUserId = scheduled.createdByUserId
                )
                transactionDao.upsert(transaction)

                // Update account balance
                val delta = if (scheduled.type == "expense") -scheduled.amount else scheduled.amount
                accountDao.adjustBalance(scheduled.accountId, delta)

                // Advance next_occurrence and track occurrences
                scheduledDao.upsert(scheduled.copy(
                    nextOccurrence = next,
                    occurrenceCount = newCount,
                    active = if (deactivate) false else scheduled.active,
                    deletedAt = if (deactivate) nowStr else scheduled.deletedAt
                ))
            }

            Log.d(TAG, "Materialized scheduled ${scheduled.id}, next: $next")
        }

        return Result.success()
    }
}
