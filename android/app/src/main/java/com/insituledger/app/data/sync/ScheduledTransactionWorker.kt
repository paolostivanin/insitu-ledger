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
import com.insituledger.app.util.DateTimeUtil
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
    private val accountDao: AccountDao
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ScheduledTxWorker"
    }

    override suspend fun doWork(): Result {
        val now = LocalDateTime.now()
        val nowStr = DateTimeUtil.formatLocalDateTime(now)

        val due = scheduledDao.getDue(nowStr)
        if (due.isEmpty()) return Result.success()

        Log.d(TAG, "Found ${due.size} due scheduled transaction(s)")

        for (scheduled in due) {
            val txDate = scheduled.nextOccurrence
            val (next, pastUntil) = advanceDate(scheduled.nextOccurrence, scheduled.rrule)
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

    private fun advanceDate(current: String, rrule: String): Pair<String, Boolean> {
        val hasTime = current.contains('T') || current.contains(' ')
        val dateTime = DateTimeUtil.parseFlexibleLocalDateTime(current)

        var freq = ""
        var interval = 1
        var untilStr: String? = null
        for (part in rrule.split(";")) {
            val kv = part.split("=", limit = 2)
            if (kv.size != 2) continue
            when (kv[0]) {
                "FREQ" -> freq = kv[1]
                "INTERVAL" -> kv[1].toIntOrNull()?.let { if (it > 0) interval = it }
                "UNTIL" -> untilStr = kv[1]
            }
        }

        val next = when (freq) {
            "DAILY" -> dateTime.plusDays(interval.toLong())
            "WEEKLY" -> dateTime.plusWeeks(interval.toLong())
            "MONTHLY" -> dateTime.plusMonths(interval.toLong())
            "YEARLY" -> dateTime.plusYears(interval.toLong())
            else -> dateTime.plusMonths(1)
        }

        val pastUntil = untilStr?.let { parseUntil(it) }?.let { next.isAfter(it) } ?: false

        val nextStr = if (hasTime) {
            DateTimeUtil.formatLocalDateTime(next)
        } else {
            next.toLocalDate().toString()
        }
        return nextStr to pastUntil
    }

    // RFC 5545 UNTIL: typical forms are 20261231T235959Z or 20261231; we also
    // tolerate the same set the backend tolerates so round-tripping is safe.
    private fun parseUntil(s: String): LocalDateTime? {
        val patterns = listOf(
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyyMMdd'T'HHmmss",
            "yyyyMMdd",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd"
        )
        for (p in patterns) {
            try {
                val fmt = java.time.format.DateTimeFormatter.ofPattern(p)
                return if (p == "yyyyMMdd" || p == "yyyy-MM-dd") {
                    java.time.LocalDate.parse(s, fmt).atTime(23, 59, 59)
                } else {
                    LocalDateTime.parse(s, fmt)
                }
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }
}
