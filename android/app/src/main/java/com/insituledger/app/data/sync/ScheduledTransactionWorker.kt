package com.insituledger.app.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.TransactionEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@HiltWorker
class ScheduledTransactionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val database: AppDatabase,
    private val scheduledDao: ScheduledTransactionDao,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ScheduledTxWorker"
    }

    override suspend fun doWork(): Result {
        val now = LocalDateTime.now()
        val nowStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

        val due = scheduledDao.getDue(nowStr)
        if (due.isEmpty()) return Result.success()

        Log.d(TAG, "Found ${due.size} due scheduled transaction(s)")

        for (scheduled in due) {
            val txDate = scheduled.nextOccurrence
            val next = advanceDate(scheduled.nextOccurrence, scheduled.rrule)
            val newCount = scheduled.occurrenceCount + 1
            val deactivate = scheduled.maxOccurrences != null && newCount >= scheduled.maxOccurrences

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
                    date = txDate,
                    isLocalOnly = true
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

    private fun advanceDate(current: String, rrule: String): String {
        val hasTime = current.contains("T")
        val dateTime = if (hasTime) {
            LocalDateTime.parse(current, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        } else {
            LocalDate.parse(current, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()
        }

        var freq = ""
        var interval = 1
        for (part in rrule.split(";")) {
            val kv = part.split("=", limit = 2)
            if (kv.size != 2) continue
            when (kv[0]) {
                "FREQ" -> freq = kv[1]
                "INTERVAL" -> kv[1].toIntOrNull()?.let { if (it > 0) interval = it }
            }
        }

        val next = when (freq) {
            "DAILY" -> dateTime.plusDays(interval.toLong())
            "WEEKLY" -> dateTime.plusWeeks(interval.toLong())
            "MONTHLY" -> dateTime.plusMonths(interval.toLong())
            "YEARLY" -> dateTime.plusYears(interval.toLong())
            else -> dateTime.plusMonths(1)
        }

        return if (hasTime) {
            next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        } else {
            next.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        }
    }
}
