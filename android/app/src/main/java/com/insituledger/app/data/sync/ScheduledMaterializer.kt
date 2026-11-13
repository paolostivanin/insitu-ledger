package com.insituledger.app.data.sync

import androidx.room.withTransaction
import com.insituledger.app.data.local.db.AppDatabase
import com.insituledger.app.data.local.db.dao.AccountDao
import com.insituledger.app.data.local.db.dao.ScheduledTransactionDao
import com.insituledger.app.data.local.db.dao.TransactionDao
import com.insituledger.app.data.local.db.entity.ScheduledTransactionEntity
import com.insituledger.app.data.local.db.entity.TransactionEntity
import com.insituledger.app.util.DateTimeUtil
import com.insituledger.app.util.Rrule
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.LocalDateTime

/**
 * Turns due scheduled transactions into real transactions (local sync mode).
 * Extracted from [ScheduledTransactionWorker] so it is unit-testable without
 * WorkManager or Hilt.
 *
 * - **Catch-up:** re-queries due rows until none are left (at most
 *   [MAX_PASSES] passes), so a schedule several periods overdue — including a
 *   recurrence saved with a past start — is fully caught up in one run.
 * - **Re-read guard:** each occurrence runs in its own transaction that
 *   re-reads the row and skips it unless it is still active, not deleted and
 *   its next_occurrence is the one we queried. Two overlapping runs therefore
 *   can't materialize the same occurrence twice, and the entry plus the
 *   advance are built from the fresh row (an edit made meanwhile is not
 *   reverted).
 * - **Isolation:** a failing row is logged and skipped for the rest of the
 *   run; it never aborts the others.
 */
class ScheduledMaterializer(
    private val database: AppDatabase,
    private val scheduledDao: ScheduledTransactionDao,
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val clock: Clock,
    private val log: (message: String, error: Throwable?) -> Unit = { _, _ -> }
) {
    companion object {
        const val MAX_PASSES = 400
    }

    /** Materializes every due occurrence; returns how many entries were created. */
    suspend fun run(): Int {
        // One "now" for the whole run, so catch-up converges instead of
        // chasing a moving target.
        val nowStr = DateTimeUtil.formatLocalDateTime(LocalDateTime.now(clock))
        val failed = mutableSetOf<Long>()
        var created = 0

        for (pass in 1..MAX_PASSES) {
            val due = scheduledDao.getDue(nowStr).filter { it.id !in failed }
            if (due.isEmpty()) break
            if (pass == 1) log("Found ${due.size} due scheduled transaction(s)", null)

            var progressed = false
            for (snapshot in due) {
                try {
                    if (materializeOne(snapshot, nowStr)) {
                        created++
                        progressed = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += snapshot.id
                    log("Failed to materialize scheduled ${snapshot.id}", e)
                }
            }
            // Every due row was skipped (raced) or failed: re-querying would
            // return the same rows forever.
            if (!progressed) break
        }
        return created
    }

    /**
     * Creates the entry for [snapshot]'s current occurrence and advances the
     * schedule, atomically. Returns false when the row changed since it was
     * queried (another run got there first, or the user edited/paused/deleted
     * it); the next pass sees the fresh row.
     */
    private suspend fun materializeOne(snapshot: ScheduledTransactionEntity, nowStr: String): Boolean =
        database.withTransaction {
            val fresh = scheduledDao.getById(snapshot.id) ?: return@withTransaction false
            if (!fresh.active || fresh.deletedAt != null || fresh.nextOccurrence != snapshot.nextOccurrence) {
                return@withTransaction false
            }

            val (next, pastUntil) = Rrule.advanceDate(fresh.nextOccurrence, fresh.rrule, clock.zone)
            val newCount = fresh.occurrenceCount + 1
            val deactivate = pastUntil ||
                (fresh.maxOccurrences != null && newCount >= fresh.maxOccurrences)

            val minId = transactionDao.getMinId() ?: 0
            val localId = if (minId >= 0) -1 else minId - 1
            transactionDao.upsert(TransactionEntity(
                id = localId,
                accountId = fresh.accountId,
                categoryId = fresh.categoryId,
                userId = fresh.userId,
                type = fresh.type,
                amount = fresh.amount,
                currency = fresh.currency,
                description = fresh.description,
                note = fresh.note,
                date = fresh.nextOccurrence,
                isLocalOnly = true,
                createdByUserId = fresh.createdByUserId,
                createdByName = fresh.createdByName
            ))

            val delta = if (fresh.type == "expense") -fresh.amount else fresh.amount
            accountDao.adjustBalance(fresh.accountId, delta)

            scheduledDao.upsert(fresh.copy(
                nextOccurrence = next,
                occurrenceCount = newCount,
                active = if (deactivate) false else fresh.active,
                deletedAt = if (deactivate) nowStr else fresh.deletedAt
            ))
            log("Materialized scheduled ${fresh.id}, next: $next", null)
            true
        }
}
